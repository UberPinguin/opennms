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

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

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
import org.opennms.netmgt.dao.api.AlarmDao;
import org.opennms.netmgt.model.OnmsAlarm;
import org.opennms.netmgt.model.OnmsSeverity;
import org.opennms.netmgt.model.TroubleTicketState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.util.Date;

public class AlarmLifecyleManager {
    private static final Logger LOG = LoggerFactory.getLogger(AlarmLifecyleManager.class);

    private final KieSession kieSession;
    private final AlarmDao alarmDao;
    private final Ticketer ticketer;
    private final SessionPseudoClock clock;

    private Map<Integer, FactHandle> alarmIdToFactHandle = new HashMap<>();

    public AlarmLifecyleManager(AlarmDao alarmDao, Ticketer ticketer) {
        this(alarmDao, ticketer, false);
    }

    public AlarmLifecyleManager(AlarmDao alarmDao, Ticketer ticketer, boolean usePseudoClock) {
        this.alarmDao = Objects.requireNonNull(alarmDao);
        this.ticketer= Objects.requireNonNull(ticketer);
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
        kieSession.setGlobal("alarmLifecyleManager", this);
        kieSession.insert(ticketer);

        if (usePseudoClock) {
            this.clock = kieSession.getSessionClock();
        } else {
            this.clock = null;
        }
    }

    public void destroy() {
        kieSession.halt();
    }

    public void onAlarm(OnmsAlarm alarm) {
        FactHandle fact = alarmIdToFactHandle.get(alarm.getId());
        if (fact == null) {
            fact = kieSession.insert(alarm);
            alarmIdToFactHandle.put(alarm.getId(), fact);
        } else {
            kieSession.update(fact, alarm);
        }
        kieSession.fireAllRules();
    }

    public void clearAlarm(OnmsAlarm alarm) {
        alarm.setSeverity(OnmsSeverity.CLEARED);
        onAlarm(alarm);
    }

    public void deleteAlarm(OnmsAlarm alarm) {
        alarmDao.delete(alarm);
        final FactHandle fact = alarmIdToFactHandle.remove(alarm.getId());
        if (fact != null) {
            kieSession.delete(fact);
        }
    }

    public void unclearAlarm(OnmsAlarm alarm) {
        alarm.setSeverity(OnmsSeverity.get(alarm.getLastEvent().getEventSeverity()));
        onAlarm(alarm);
    }

    public void ackAlarmAndCreateTicket(OnmsAlarm alarm) {
        alarm.setAlarmAckUser("admin");
        alarm.setAlarmAckTime(new Date());
        alarm.setTTicketState(TroubleTicketState.CREATE_PENDING);
        ticketer.createTicket(alarm);
        onAlarm(alarm);
    }

    public void updateTicket(OnmsAlarm alarm) {
        ticketer.updateTicket(alarm);
    }

    public SessionPseudoClock getClock() {
        return clock;
    }

    public void tick() {
        kieSession.fireAllRules();
    }
}
