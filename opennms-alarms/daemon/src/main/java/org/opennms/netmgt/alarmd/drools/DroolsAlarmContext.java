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

package org.opennms.netmgt.alarmd.drools;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
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
import org.opennms.core.logging.Logging;
import org.opennms.netmgt.alarmd.AlarmLifecycleListenerManager;
import org.opennms.netmgt.alarmd.Alarmd;
import org.opennms.netmgt.alarmd.api.AlarmLifecycleListener;
import org.opennms.netmgt.model.OnmsAlarm;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;

import com.google.common.collect.Sets;

public class DroolsAlarmContext implements AlarmLifecycleListener {
    private static final Logger LOG = LoggerFactory.getLogger(DroolsAlarmContext.class);

    @Autowired
    private AlarmService alarmService;

    @Autowired
    private AlarmTicketerService alarmTicketerService;

    @Autowired
    private AlarmLifecycleListenerManager alarmLifecycleListenerManager;

    private boolean usePseudoClock = false;

    private boolean useManualTick = false;

    private KieSession kieSession;

    private SessionPseudoClock clock;

    private final Map<Integer, AlarmAndFact> alarmsById = new HashMap<>();

    public void start() {
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
        kieSession.setGlobal("alarmService", alarmService);

        if (usePseudoClock) {
            this.clock = kieSession.getSessionClock();
        } else {
            this.clock = null;
        }

        alarmsById.clear();

        kieSession.insert(alarmTicketerService);
        alarmLifecycleListenerManager.addAlarmLifecyleListener(this);

        if (!useManualTick) {
            new Thread(() -> {
                Logging.putPrefix(Alarmd.NAME);
                LOG.debug("Firing until halt.");
                kieSession.fireUntilHalt();
                LOG.debug("Halt.");
            }, Alarmd.NAME + "-Drools-Firing").start();
        }
    }

    public void stop() {
        if (kieSession != null) {
            kieSession.halt();
            kieSession = null;
        }
        alarmLifecycleListenerManager.removeAlarmLifecycleListener(this);
    }

    @Override
    public synchronized void handleAlarmSnapshot(List<OnmsAlarm> alarms) {
        LOG.debug("Handling snapshot for {} alarms.", alarms.size());
        final Map<Integer, OnmsAlarm> alarmsInDbById = alarms.stream()
                .filter(a -> a.getId() != null)
                .collect(Collectors.toMap(OnmsAlarm::getId, a -> a));

        final Set<Integer> alarmIdsInDb = alarmsInDbById.keySet();
        final Set<Integer> alarmIdsInWorkingMem = alarmsById.keySet();

        final Set<Integer> alarmIdsToAdd = Sets.difference(alarmIdsInDb, alarmIdsInWorkingMem);
        final Set<Integer> alarmIdsToRemove = Sets.difference(alarmIdsInWorkingMem, alarmIdsInDb);
        final Set<Integer> alarmIdsToUpdate = Sets.newHashSet(Sets.intersection(alarmIdsInWorkingMem, alarmIdsInDb));

        for (Integer alarmIdToRemove : alarmIdsToRemove) {
            handleDeletedAlarm(alarmIdToRemove);
        }
        for (Integer alarmIdToAdd : alarmIdsToAdd) {
            handleNewOrUpdatedAlarm(alarmsInDbById.get(alarmIdToAdd));
        }
        for (Integer alarmIdToUpdate : alarmIdsToUpdate) {
            handleNewOrUpdatedAlarm(alarmsInDbById.get(alarmIdToUpdate));
        }
    }

    @Override
    public synchronized void handleNewOrUpdatedAlarm(OnmsAlarm alarm) {
        final AlarmAndFact alarmAndFact = alarmsById.get(alarm.getId());
        if (alarmAndFact == null) {
            final FactHandle fact = kieSession.insert(alarm);
            alarmsById.put(alarm.getId(), new AlarmAndFact(alarm, fact));
        } else if (Objects.equals(alarm.getLastEventTime(), alarmAndFact.getAlarm().getLastEventTime())) {
            kieSession.update(alarmAndFact.getFact(), alarm);
        } else {
            // If the time field changes we need to remove and re-insert the object, rather than update it
            // Remove
            kieSession.delete(alarmAndFact.getFact());
            // Reinsert
            final FactHandle fact = kieSession.insert(alarm);
            alarmAndFact.setFact(fact);
        }
    }

    @Override
    public synchronized void handleDeletedAlarm(int alarmId, String reductionKey) {
        handleDeletedAlarm(alarmId);
    }

    private void handleDeletedAlarm(int alarmId) {
        final AlarmAndFact alarmAndFact = alarmsById.remove(alarmId);
        if (alarmAndFact != null) {
            kieSession.delete(alarmAndFact.getFact());
        }
    }

    private static class AlarmAndFact {
        private OnmsAlarm alarm;
        private FactHandle fact;

        public AlarmAndFact(OnmsAlarm alarm, FactHandle fact) {
            this.alarm = alarm;
            this.fact = fact;
        }

        public OnmsAlarm getAlarm() {
            return alarm;
        }

        public void setAlarm(OnmsAlarm alarm) {
            this.alarm = alarm;
        }

        public FactHandle getFact() {
            return fact;
        }

        public void setFact(FactHandle fact) {
            this.fact = fact;
        }
    }

    public SessionPseudoClock getClock() {
        return clock;
    }

    public void tick() {
        kieSession.fireAllRules();
    }

    public void setUsePseudoClock(boolean usePseudoClock) {
        this.usePseudoClock = usePseudoClock;
    }

    public void setUseManualTick(boolean useManualTick) {
        this.useManualTick = useManualTick;
    }

    public void setAlarmService(AlarmService alarmService) {
        this.alarmService = alarmService;
    }

    public void setAlarmTicketerService(AlarmTicketerService alarmTicketerService) {
        this.alarmTicketerService = alarmTicketerService;
    }

    public void setAlarmLifecycleListenerManager(AlarmLifecycleListenerManager alarmLifecycleListenerManager) {
        this.alarmLifecycleListenerManager = alarmLifecycleListenerManager;
    }
}
