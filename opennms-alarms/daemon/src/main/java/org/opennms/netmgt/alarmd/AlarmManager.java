/*******************************************************************************
 * This file is part of OpenNMS(R).
 *
 * Copyright (C) 2018 The OpenNMS Group, Inc.
 * OpenNMS(R) is Copyright (C) 1999-2018 The OpenNMS Group, Inc.
 *
 * OpenNMS(R) is a registered trademark of The OpenNMS Group, Inc.
 *
 * OpenNMS(R) is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License,
 * or (at your option) any later version.
 *
 * OpenNMS(R) is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with OpenNMS(R).  If not, see:
 *      http://www.gnu.org/licenses/
 *
 * For more information contact:
 *     OpenNMS(R) Licensing <license@opennms.org>
 *     http://www.opennms.org/
 *     http://www.opennms.com/
 *******************************************************************************/

package org.opennms.netmgt.alarmd;

import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.drools.core.time.SessionPseudoClock;
import org.kie.api.KieBase;
import org.kie.api.KieBaseConfiguration;
import org.kie.api.KieServices;
import org.kie.api.conf.EventProcessingOption;
import org.kie.api.runtime.KieContainer;
import org.kie.api.runtime.KieSession;
import org.kie.api.runtime.KieSessionConfiguration;
import org.kie.api.runtime.conf.ClockTypeOption;
import org.kie.api.runtime.rule.FactHandle;
import org.opennms.netmgt.alarmd.api.AlarmLifecycleListener;
import org.opennms.netmgt.dao.api.AlarmDao;
import org.opennms.netmgt.events.api.EventConstants;
import org.opennms.netmgt.events.api.EventForwarder;
import org.opennms.netmgt.model.OnmsAlarm;
import org.opennms.netmgt.model.OnmsSeverity;
import org.opennms.netmgt.model.TroubleTicketState;
import org.opennms.netmgt.model.events.EventBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionOperations;

import com.google.common.collect.Sets;

public class AlarmManager implements AlarmLifecycleListener, AlarmService, InitializingBean {
    private static final Logger LOG = LoggerFactory.getLogger(AlarmManager.class);

    @Autowired
    private AlarmDao alarmDao;

    @Autowired
    private TransactionOperations transactionOperations;

    @Autowired
    private EventForwarder eventForwarder;

    @Autowired
    private AlarmTicketerService ticketer;

    @Autowired
    private AlarmLifecycleListenerManager alarmLifecycleListenerManager;

    private final KieSession kieSession;
    private final SessionPseudoClock clock;

    private Map<Integer, FactHandle> alarmIdToFactHandle = new HashMap<>();

    public AlarmManager() {
        this(false);
    }

    public AlarmManager(boolean usePseudoClock) {
        final KieServices ks = KieServices.Factory.get();
        final KieContainer kcont = ks.newKieClasspathContainer(getClass().getClassLoader());
        final KieBaseConfiguration kbaseConfig = ks.newKieBaseConfiguration();
        kbaseConfig.setOption(EventProcessingOption.STREAM);
        final KieBase kbase = kcont.newKieBase("alarmKBase", kbaseConfig);

        final KieSessionConfiguration kieSessionConfig = KieServices.Factory.get().newKieSessionConfiguration();
        if (usePseudoClock) {
            kieSessionConfig.setOption(ClockTypeOption.get("pseudo"));
        }
        kieSession = kbase.newKieSession(kieSessionConfig, null);
        kieSession.setGlobal("alarmManager", this);

        if (usePseudoClock) {
            this.clock = kieSession.getSessionClock();
        } else {
            this.clock = null;
        }
    }

    @Override
    public void afterPropertiesSet() {
        kieSession.insert(ticketer);
        alarmLifecycleListenerManager.addAlarmLifecyleListener(this);
    }

    public void destroy() {
        kieSession.halt();
        alarmLifecycleListenerManager.removeAlarmLifecycleListener(this);
    }

    @Override
    public synchronized void handleAlarmSnapshot(List<OnmsAlarm> alarms) {
        LOG.info("Handling snapshot for {} alarms.", alarms.size());
        final Map<Integer, OnmsAlarm> alarmsInDbById = alarms.stream()
                .filter(a -> a.getId() != null)
                .collect(Collectors.toMap(OnmsAlarm::getId, a -> a));

        final Set<Integer> alarmIdsInDb = alarmsInDbById.keySet();
        final Set<Integer> alarmIdsInWorkingMem = alarmIdToFactHandle.keySet();

        final Set<Integer> alarmIdsToAdd = Sets.difference(alarmIdsInDb, alarmIdsInWorkingMem);
        final Set<Integer> alarmIdsToRemove = Sets.difference(alarmIdsInWorkingMem, alarmIdsInDb);
        final Set<Integer> alarmIdsToUpdate = Sets.newHashSet(Sets.intersection(alarmIdsInWorkingMem, alarmIdsInDb));

        for (Integer alarmIdToRemove : alarmIdsToRemove) {
            final FactHandle fact = alarmIdToFactHandle.remove(alarmIdToRemove);
            if (fact != null) {
                kieSession.delete(fact);
            }
        }
        for (Integer alarmIdToAdd : alarmIdsToAdd) {
            final FactHandle fact = kieSession.insert(alarmsInDbById.get(alarmIdToAdd));
            alarmIdToFactHandle.put(alarmIdToAdd, fact);
        }
        for (Integer alarmIdToUpdate : alarmIdsToUpdate) {
            // Remove
            FactHandle fact = alarmIdToFactHandle.remove(alarmIdToUpdate);
            if (fact != null) {
                kieSession.delete(fact);
            }
            // Reinsert
            kieSession.insert(alarmsInDbById.get(alarmIdToUpdate));
            alarmIdToFactHandle.put(alarmIdToUpdate, fact);
            // FIXME: If the time field changes, we need to remove and re-insert rather than update?
            //final FactHandle fact = alarmIdToFactHandle.get(alarmIdToUpdate);
            //kieSession.update(fact, alarmsInDbById.get(alarmIdToUpdate));
        }
        kieSession.fireAllRules();
    }

    @Override
    public synchronized void handleNewOrUpdatedAlarm(OnmsAlarm alarm) {
        FactHandle fact = alarmIdToFactHandle.get(alarm.getId());
        if (fact == null) {
            fact = kieSession.insert(alarm);
            alarmIdToFactHandle.put(alarm.getId(), fact);
        } else {
            // Remove
            kieSession.delete(fact);
            // Reinsert
            fact = kieSession.insert(alarm);
            alarmIdToFactHandle.put(alarm.getId(), fact);
            // FIXME: If the time field changes, we need to remove and re-insert rather than update?
            //kieSession.update(fact, alarm);
        }
        kieSession.fireAllRules();
    }

    @Override
    public synchronized void handleDeletedAlarm(int alarmId, String reductionKey) {
        final FactHandle fact = alarmIdToFactHandle.remove(alarmId);
        if (fact != null) {
            kieSession.delete(fact);
            kieSession.fireAllRules();
        }
    }

    @Override
    public void clearAlarm(OnmsAlarm alarm, long currentTime) {
        LOG.info("Clearing alarm with id: {} with severity: {}", alarm.getId(), alarm.getSeverity());
        // Perform action
        final OnmsAlarm updatedAlarm = transactionOperations.execute((t) -> {
            final OnmsAlarm alarmInTrans = alarmDao.get(alarm.getId());
            if (alarmInTrans == null) {
                LOG.warn("Alarm disappeared: {}. Skipping clear.", alarm);
                return null;
            }
            alarmInTrans.setSeverity(OnmsSeverity.CLEARED);
            alarmInTrans.setLastAutomationTime(new Date(currentTime));
            alarmDao.update(alarmInTrans);
            return alarmInTrans;
        });
        // Update working memory
        if (updatedAlarm == null) {
            handleDeletedAlarm(alarm.getId(), alarm.getReductionKey());
        } else {
            handleNewOrUpdatedAlarm(updatedAlarm);
        }
        // Notify
        eventForwarder.sendNow(new EventBuilder(EventConstants.ALARM_CLEARED_UEI, Alarmd.NAME)
                .addParam("alarmId", alarm.getId())
                .addParam("alarmEventUei", alarm.getLastEvent() != null ? alarm.getLastEvent().getEventUei() : null)
                .getEvent());
    }

    @Override
    public void deleteAlarm(OnmsAlarm alarm) {
        LOG.info("Deleting alarm with id: {} with severity: {}", alarm.getId(), alarm.getSeverity());
        // Perform action
        final OnmsAlarm updatedAlarm = transactionOperations.execute((t) -> {
            final OnmsAlarm alarmInTrans = alarmDao.get(alarm.getId());
            if (alarmInTrans == null) {
                LOG.warn("Alarm disappeared: {}. Skipping delete.", alarm);
                return null;
            }
            alarmDao.delete(alarm);
            return null;
        });
        // Update working memory
        handleDeletedAlarm(alarm.getId(), alarm.getReductionKey());
        // Notify
        eventForwarder.sendNow(new EventBuilder(EventConstants.ALARM_DELETED_EVENT_UEI, Alarmd.NAME)
                .addParam("alarmId", alarm.getId())
                .addParam("alarmEventUei", alarm.getLastEvent() != null ? alarm.getLastEvent().getEventUei() : null)
                .addParam("alarmReductionKey", alarm.getReductionKey())
                .getEvent());
    }

    @Override
    public void unclearAlarm(OnmsAlarm alarm) {
        LOG.info("Un-clearing alarm with id: {}", alarm.getId());
        // Perform action
        final OnmsAlarm updatedAlarm = transactionOperations.execute((t) -> {
            final OnmsAlarm alarmInTrans = alarmDao.get(alarm.getId());
            if (alarmInTrans == null) {
                LOG.warn("Alarm disappeared: {}. Skipping clear.", alarm);
                return null;
            }
            alarmInTrans.setSeverity(OnmsSeverity.get(alarmInTrans.getLastEvent().getEventSeverity()));
            alarmDao.update(alarmInTrans);
            return alarmInTrans;
        });
        // Update working memory
        if (updatedAlarm == null) {
            handleDeletedAlarm(alarm.getId(), alarm.getReductionKey());
        } else {
            handleNewOrUpdatedAlarm(updatedAlarm);
        }
        // Notify
        eventForwarder.sendNow(new EventBuilder(EventConstants.ALARM_UNCLEARED_UEI, Alarmd.NAME)
                .addParam("alarmId", alarm.getId())
                .addParam("alarmEventUei", alarm.getLastEvent() != null ? alarm.getLastEvent().getEventUei() : null)
                .getEvent());
    }

    @Override
    public void ackAlarmAndCreateTicket(OnmsAlarm alarm) {
        // Perform action
        final OnmsAlarm updatedAlarm = transactionOperations.execute((t) -> {
            final OnmsAlarm alarmInTrans = alarmDao.get(alarm.getId());
            if (alarmInTrans == null) {
                LOG.warn("Alarm disappeared: {}. Skipping clear.", alarm);
                return null;
            }
            alarmInTrans.setAlarmAckUser("admin");
            alarmInTrans.setAlarmAckTime(new Date());
            alarmInTrans.setTTicketState(TroubleTicketState.CREATE_PENDING);
            alarmDao.update(alarmInTrans);
            return alarmInTrans;
        });
        // Update working memory
        if (updatedAlarm == null) {
            handleDeletedAlarm(alarm.getId(), alarm.getReductionKey());
        } else {
            handleNewOrUpdatedAlarm(updatedAlarm);
        }
        // Notify TODO: FIXME: Send alarm acknowledged event
        eventForwarder.sendNow(new EventBuilder(EventConstants.ALARM_UPDATED_WITH_REDUCED_EVENT_UEI, Alarmd.NAME)
                .addParam("alarmId", alarm.getId())
                .addParam("alarmEventUei", alarm.getLastEvent() != null ? alarm.getLastEvent().getEventUei() : null)
                .getEvent());
        createTicket(alarm);
    }

    @Override
    public boolean isTicketingEnabled() {
        return ticketer.isTicketingEnabled();
    }

    @Override
    public void createTicket(OnmsAlarm alarm) {
        ticketer.createTicket(alarm);
    }

    @Override
    public void updateTicket(OnmsAlarm alarm) {
        ticketer.updateTicket(alarm);
    }

    @Override
    public void closeTicket(OnmsAlarm alarm) {
        ticketer.closeTicket(alarm);
    }

    public SessionPseudoClock getClock() {
        return clock;
    }

    public void tick() {
        kieSession.fireAllRules();
    }

    public void setAlarmDao(AlarmDao alarmDao) {
        this.alarmDao = alarmDao;
    }

    public void setTransactionOperations(TransactionOperations transactionOperations) {
        this.transactionOperations = transactionOperations;
    }

    public void setEventForwarder(EventForwarder eventForwarder) {
        this.eventForwarder = eventForwarder;
    }

    public void setTicketer(AlarmTicketerService ticketer) {
        this.ticketer = ticketer;
    }

    public void setAlarmLifecycleListenerManager(AlarmLifecycleListenerManager alarmLifecycleListenerManager) {
        this.alarmLifecycleListenerManager = alarmLifecycleListenerManager;
    }
}
