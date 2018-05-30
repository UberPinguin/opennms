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

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.nullValue;
import static org.hamcrest.core.IsEqual.equalTo;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Objects;

import org.hamcrest.Description;
import org.hamcrest.TypeSafeMatcher;
import org.junit.Test;
import org.opennms.netmgt.events.api.EventConstants;
import org.opennms.netmgt.model.OnmsAlarm;
import org.opennms.netmgt.model.OnmsSeverity;
import org.opennms.netmgt.model.events.EventBuilder;
import org.opennms.netmgt.xml.event.AlarmData;
import org.opennms.netmgt.xml.event.Event;

public class AlarmdNextGenIT {

    /**
     * Verifies the basic life-cycle of a trigger, followed by a clear.
     */
    @Test
    public void canTriggerAndClearAlarm() {
        Scenario scenario = new ScenarioBuilder()
                .withNodeDownEvent(1, 1)
                .withNodeUpEvent(2, 1)
                .build();
        ScenarioResults results = play(scenario);

        // Verify the set of alarms at various points in time

        // t=0, no alarms
        assertThat(results.getAlarms(0), hasSize(0));
        // t=1, a single problem alarm
        assertThat(results.getAlarms(1), hasSize(1));
        assertThat(results.getProblemAlarm(1), hasSeverity(OnmsSeverity.WARNING));
        // t=2, a (cleared) problem and a resolution
        assertThat(results.getAlarms(2), hasSize(2));
        assertThat(results.getProblemAlarm(1), hasSeverity(OnmsSeverity.CLEARED));
        assertThat(results.getResolutionAlarm(1), hasSeverity(OnmsSeverity.CLEARED));
        // t=∞
        assertThat(results.getAlarms(-1), hasSize(0));

        // Now verify the state changes for the particular alarms

        // the problem
        List<State> problemStates = results.getStateChangesForAlarmWithId(results.getProblemAlarm(1).getId());
        assertThat(problemStates, hasSize(3)); // warning, cleared, deleted
        // state 0 at t=1
        assertThat(problemStates.get(0).getTime(), equalTo(1));
        assertThat(problemStates.get(0).getAlarm(), hasSeverity(OnmsSeverity.WARNING));
        // state 1 at t=2
        assertThat(problemStates.get(1).getTime(), equalTo(2));
        assertThat(problemStates.get(1).getAlarm(), hasSeverity(OnmsSeverity.CLEARED));
        assertThat(problemStates.get(1).getAlarm().getCounter(), equalTo(1));
        // state 2 at t=102
        assertThat(problemStates.get(2).getTime(), equalTo(102));
        assertThat(problemStates.get(2).getAlarm(), nullValue());

        // the resolution
        List<State> resolutionStates = results.getStateChangesForAlarmWithId(results.getResolutionAlarm(1).getId());
        assertThat(resolutionStates, hasSize(2)); // cleared, deleted
        // state 0 at t=2
        assertThat(resolutionStates.get(0).getTime(), equalTo(2));
        assertThat(resolutionStates.get(0).getAlarm(), hasSeverity(OnmsSeverity.WARNING));
        // state 1 at t=100
        assertThat(resolutionStates.get(1).getTime(), equalTo(100));
        assertThat(resolutionStates.get(1).getAlarm(), nullValue());
    }

    private ScenarioResults play(Scenario scenario) {
        return new ScenarioResults();
    }

    private static class Scenario {
        private final List<Event> events;

        public Scenario(List<Event> events) {
            this.events = new ArrayList<>(events);
            this.events.sort(Comparator.comparing(Event::getTime));
        }

        public List<Event> getEvents() {
            return events;
        }
    }

    private static class ScenarioResults {

        public List<OnmsAlarm> getAlarms(int t) {
            return Collections.emptyList();
        }

        public OnmsAlarm getProblemAlarm(int t) {
            return null;
        }

        public OnmsAlarm getResolutionAlarm(int t) {
            return null;
        }


        public OnmsAlarm getNotificationAlarm(int t) {
            return null;
        }

        public List<State> getStateChangesForAlarmWithId(Integer id) {
            return Collections.emptyList();
        }
    }

    private static class StateChanges {
        List<State> getStates() {
            return Collections.emptyList();
        }
    }

    private static class State {
        long time;
        OnmsAlarm alarm;

        public long getTime() {
            return time;
        }

        public OnmsAlarm getAlarm() {
            return alarm;
        }
    }

    private static class ScenarioBuilder {
        private final List<Event> events = new ArrayList<>();

        public ScenarioBuilder withNodeDownEvent(long time, int nodeId) {
            EventBuilder builder = new EventBuilder(EventConstants.NODE_UP_EVENT_UEI, "test");
            builder.setTime(new Date(time));
            builder.setNodeid(nodeId);
            builder.setSeverity("Normal");

            AlarmData data = new AlarmData();
            data.setAlarmType(2);
            data.setReductionKey(String.format("%s:%d", EventConstants.NODE_UP_EVENT_UEI, nodeId));
            data.setClearKey(String.format("%s:%d", EventConstants.NODE_DOWN_EVENT_UEI, nodeId));
            builder.setAlarmData(data);

            builder.setLogDest("logndisplay");
            builder.setLogMessage("testing");
            events.add(builder.getEvent());
            return this;
        }

        public ScenarioBuilder withNodeUpEvent(long time, int nodeId) {
            EventBuilder builder = new EventBuilder(EventConstants.NODE_UP_EVENT_UEI, "test");
            builder.setTime(new Date(time));
            builder.setNodeid(nodeId);
            builder.setSeverity("Normal");

            AlarmData data = new AlarmData();
            data.setAlarmType(2);
            data.setReductionKey(String.format("%s:%d", EventConstants.NODE_UP_EVENT_UEI, nodeId));
            data.setClearKey(String.format("%s:%d", EventConstants.NODE_DOWN_EVENT_UEI, nodeId));
            builder.setAlarmData(data);

            builder.setLogDest("logndisplay");
            builder.setLogMessage("testing");
            events.add(builder.getEvent());
            return this;
        }

        public Scenario build() {
            return new Scenario(events);
        }
    }

    private static class HasSeverity extends TypeSafeMatcher<OnmsAlarm> {
        private final OnmsSeverity severity;

        public HasSeverity(OnmsSeverity severity) {
            this.severity = severity;
        }

        @Override
        protected boolean matchesSafely(OnmsAlarm alarm) {
            return Objects.equals(severity, alarm.getSeverity());
        }

        @Override
        public void describeTo(Description description) {
            description.appendText("has severity " + severity);
        }
    }

    private HasSeverity hasSeverity(OnmsSeverity severity) {
        return new HasSeverity(severity);
    }

}
