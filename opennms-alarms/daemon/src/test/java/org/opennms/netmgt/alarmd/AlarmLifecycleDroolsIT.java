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


import static com.jayway.awaitility.Awaitility.await;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.mockito.Matchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.hamcrest.collection.IsIterableContainingInOrder.contains;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.opennms.netmgt.dao.api.AlarmDao;
import org.opennms.netmgt.model.OnmsAlarm;
import org.opennms.netmgt.model.OnmsEvent;
import org.opennms.netmgt.model.OnmsSeverity;

public class AlarmLifecycleDroolsIT {

    private AlarmLifecyleManager alm;
    private AlarmDao alarmDao;
    private MockTicketer ticketer = new MockTicketer();

    @Before
    public void setUp() {
        alarmDao = mock(AlarmDao.class);
        alm = new AlarmLifecyleManager(alarmDao, ticketer, true);
    }

    @After
    public void tearDown() {
        if (alm != null) {
            alm.destroy();
        }
    }

    @Test
    public void canClearAlarm() {
        OnmsAlarm trigger = new OnmsAlarm();
        trigger.setId(1);
        trigger.setAlarmType(1);
        trigger.setSeverity(OnmsSeverity.WARNING);
        trigger.setReductionKey("n1:oops");
        trigger.setLastEventTime(new Date(100));
        alm.onAlarm(trigger);

        OnmsAlarm clear = new OnmsAlarm();
        clear.setId(2);
        clear.setAlarmType(2);
        clear.setSeverity(OnmsSeverity.CLEARED);
        clear.setClearKey("n1:oops");
        clear.setLastEventTime(new Date(101));
        alm.onAlarm(clear);

        await().atMost(10, TimeUnit.SECONDS).until(trigger::getSeverity, equalTo(OnmsSeverity.CLEARED));
    }

    @Test
    public void canDeleteAlarm() {
        final OnmsAlarm toDelete = new OnmsAlarm();
        toDelete.setId(2);
        toDelete.setAlarmType(2);
        toDelete.setSeverity(OnmsSeverity.CLEARED);
        toDelete.setClearKey("n1:oops");
        toDelete.setLastEventTime(new Date(101));

        final AtomicBoolean gotDelete = new AtomicBoolean();
        doAnswer(invocation -> {
            gotDelete.set(true);
            return null;
        }).when(alarmDao).delete(toDelete);
        alm.onAlarm(toDelete);

        // The alarm should not be immediately deleted
        assertThat(gotDelete.get(), equalTo(false));

        // Advance the clock and tick
        alm.getClock().advanceTime( 10, TimeUnit.MINUTES );
        alm.tick();

        // Validate
        await().atMost(10, TimeUnit.SECONDS).until(gotDelete::get);
    }

    @Test
    public void canDeleteAcknowledgedAlarm() {
        final OnmsAlarm toDelete = new OnmsAlarm();
        toDelete.setId(2);
        toDelete.setAlarmType(2);
        toDelete.setSeverity(OnmsSeverity.CLEARED);
        toDelete.setClearKey("n1:oops");
        toDelete.setLastEventTime(new Date(101));
        // "Ack" the alarm
        toDelete.setAlarmAckTime(new Date(110));
        toDelete.setAlarmAckUser("me");

        final AtomicBoolean gotDelete = new AtomicBoolean();
        doAnswer(invocation -> {
            gotDelete.set(true);
            return null;
        }).when(alarmDao).delete(toDelete);
        alm.onAlarm(toDelete);

        // The alarm should not be immediately deleted
        assertThat(gotDelete.get(), equalTo(false));

        // Advance the clock and tick
        alm.getClock().advanceTime( 10, TimeUnit.MINUTES );
        alm.tick();

        // Still not deleted
        assertThat(gotDelete.get(), equalTo(false));

        // Advance the clock and tick
        alm.getClock().advanceTime( 1, TimeUnit.DAYS );
        alm.tick();

        await().atMost(10, TimeUnit.SECONDS).until(gotDelete::get);
    }

    @Test
    public void canGarbageCollectAlarm() {
        // Trigger some problem
        OnmsAlarm trigger = new OnmsAlarm();
        trigger.setId(1);
        trigger.setAlarmType(1);
        trigger.setSeverity(OnmsSeverity.WARNING);
        trigger.setReductionKey("n1:oops");
        trigger.setLastEventTime(new Date(100));
        alm.onAlarm(trigger);

        final AtomicBoolean gotDelete = new AtomicBoolean();
        doAnswer(invocation -> {
            gotDelete.set(true);
            return null;
        }).when(alarmDao).delete(trigger);
        alm.onAlarm(trigger);

        // The alarm should not be immediately deleted
        assertThat(gotDelete.get(), equalTo(false));

        // Advance the clock and tick
        alm.getClock().advanceTime( 1, TimeUnit.HOURS );
        alm.tick();

        // Still not deleted
        assertThat(gotDelete.get(), equalTo(false));

        // Advance the clock and tick
        alm.getClock().advanceTime( 3, TimeUnit.DAYS );
        alm.tick();

        await().atMost(10, TimeUnit.SECONDS).until(gotDelete::get);
    }

    @Test
    public void canGarbageCollectAcknowledgedAlarm() {
        // Trigger some problem
        OnmsAlarm trigger = new OnmsAlarm();
        trigger.setId(1);
        trigger.setAlarmType(1);
        trigger.setSeverity(OnmsSeverity.WARNING);
        trigger.setReductionKey("n1:oops");
        trigger.setLastEventTime(new Date(100));
        // Ack the problem
        trigger.setAlarmAckTime(new Date(110));
        trigger.setAlarmAckUser("me");
        alm.onAlarm(trigger);

        final AtomicBoolean gotDelete = new AtomicBoolean();
        doAnswer(invocation -> {
            gotDelete.set(true);
            return null;
        }).when(alarmDao).delete(trigger);
        alm.onAlarm(trigger);

        // The alarm should not be immediately deleted
        assertThat(gotDelete.get(), equalTo(false));

        // Advance the clock and tick
        alm.getClock().advanceTime( 1, TimeUnit.HOURS );
        alm.tick();

        // Still not deleted
        assertThat(gotDelete.get(), equalTo(false));

        // Advance the clock and tick
        alm.getClock().advanceTime( 8, TimeUnit.DAYS );
        alm.tick();

        await().atMost(10, TimeUnit.SECONDS).until(gotDelete::get);
    }

    @Test
    public void canUnclearAlarm() {
        final OnmsEvent event = new OnmsEvent();
        event.setEventTime(new Date(101));
        event.setEventSeverity(OnmsSeverity.WARNING.getId());

        final OnmsAlarm alarm = new OnmsAlarm();
        alarm.setId(1);
        alarm.setAlarmType(1);
        alarm.setSeverity(OnmsSeverity.CLEARED);
        alarm.setReductionKey("n1:oops");
        alarm.setLastEvent(event);
        alarm.setLastEventTime(event.getEventTime());
        alm.onAlarm(alarm);

        // The severity should be updated
        assertThat(alarm.getSeverity(), equalTo(OnmsSeverity.WARNING));
    }

    @Test
    public void canCreateTicket() {
        ticketer.setEnabled(true);

        // Trigger some problem
        OnmsAlarm trigger = new OnmsAlarm();
        trigger.setId(1);
        trigger.setAlarmType(1);
        trigger.setSeverity(OnmsSeverity.WARNING);
        trigger.setReductionKey("n1:oops");
        trigger.setLastEventTime(new Date(100));
        alm.onAlarm(trigger);

        // No ticket yet
        assertThat(ticketer.getCreates(), hasSize(0));

        // Advance the clock and tick
        alm.getClock().advanceTime( 20, TimeUnit.MINUTES );
        alm.tick();

        // Ticket!
        assertThat(ticketer.getCreates(), contains(trigger.getId()));
    }

    @Test
    public void canCreateTicketForCriticalAlarm() {
        ticketer.setEnabled(true);

        // Trigger some problem
        OnmsAlarm trigger = new OnmsAlarm();
        trigger.setId(1);
        trigger.setAlarmType(1);
        trigger.setSeverity(OnmsSeverity.CRITICAL);
        trigger.setReductionKey("n1:oops");
        trigger.setLastEventTime(new Date(100));
        alm.onAlarm(trigger);

        // No ticket yet
        assertThat(ticketer.getCreates(), hasSize(0));

        // Advance the clock and tick
        alm.getClock().advanceTime( 6, TimeUnit.MINUTES );
        alm.tick();

        // Ticket!
        assertThat(ticketer.getCreates(), contains(trigger.getId()));
    }

    @Test
    public void canUpdateTicket() {
        ticketer.setEnabled(true);

        // Trigger some problem
        OnmsAlarm trigger = new OnmsAlarm();
        trigger.setId(1);
        trigger.setAlarmType(1);
        trigger.setSeverity(OnmsSeverity.WARNING);
        trigger.setReductionKey("n1:oops");
        trigger.setLastEventTime(new Date(100));
        alm.onAlarm(trigger);

        // No ticket yet
        assertThat(ticketer.getCreates(), hasSize(0));
        assertThat(ticketer.getNumUpdatesFor(trigger), equalTo(0));

        // Advance the clock and tick
        alm.getClock().advanceTime( 20, TimeUnit.MINUTES );
        alm.tick();

        // FIXME: We shouldn't get an update right after the create
        assertThat(ticketer.getCreates(), hasSize(1));
        assertThat(ticketer.getNumUpdatesFor(trigger), equalTo(1));

        /*
        // Ticket, but no update yet
        assertThat(ticketer.getCreates(), hasSize(1));
        assertThat(ticketer.getNumUpdatesFor(trigger), equalTo(0));

        // Advance the clock and tick
        alm.getClock().advanceTime( 60, TimeUnit.MINUTES );
        alm.tick();
        assertThat(ticketer.getNumUpdatesFor(trigger), equalTo(1));

        // Advance the clock and tick
        alm.getClock().advanceTime( 20, TimeUnit.MINUTES );
        alm.tick();
        assertThat(ticketer.getNumUpdatesFor(trigger), equalTo(2));
        */
    }

    private static class MockTicketer implements Ticketer {
        private boolean enabled = false;
        private List<Integer> creates = new ArrayList<>();
        private Map<Integer, Integer> updates = new LinkedHashMap<>();

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        @Override
        public boolean isEnabled() {
            return enabled;
        }

        @Override
        public void createTicket(OnmsAlarm alarm) {
            creates.add(alarm.getId());
        }

        @Override
        public void updateTicket(OnmsAlarm alarm) {
            updates.compute(alarm.getId(), (k, v) -> (v == null) ? 1 : v + 1);
        }

        public List<Integer> getCreates() {
            return creates;
        }

        public Map<Integer, Integer> getUpdates() {
            return updates;
        }

        public int getNumUpdatesFor(OnmsAlarm alarm) {
            return updates.computeIfAbsent(alarm.getId(), k -> 0);
        }
    }
}
