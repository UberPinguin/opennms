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
import static org.hamcrest.Matchers.equalTo;

import java.util.Date;
import java.util.concurrent.TimeUnit;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.opennms.netmgt.model.OnmsAlarm;
import org.opennms.netmgt.model.OnmsSeverity;

public class AlarmLifecycleDroolsIT {

    AlarmLifecyleManager alm;

    @Before
    public void setUp() {
        alm = new AlarmLifecyleManager();
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
}
