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

import org.opennms.netmgt.dao.api.AlarmEntityNotifier;
import org.opennms.netmgt.model.OnmsAlarm;
import org.opennms.netmgt.model.OnmsMemo;
import org.opennms.netmgt.model.OnmsReductionKeyMemo;
import org.opennms.netmgt.model.OnmsSeverity;
import org.springframework.beans.factory.annotation.Autowired;

public class DefaultAlarmEntityNotifier implements AlarmEntityNotifier {

    @Autowired
    private NorthbounderManager m_northbounderManager;

    @Autowired
    private AlarmLifecycleListenerManager m_alarmLifecycleListenerManager;

    @Override
    public void didCreateAlarm(OnmsAlarm alarm) {
        onNewOrUpdatedAlarm(alarm);
        m_northbounderManager.onAlarmCreatedOrUpdatedWithReducedEvent(alarm);
    }

    @Override
    public void didUpdateAlarmWithReducedEvent(OnmsAlarm alarm) {
        onNewOrUpdatedAlarm(alarm);
        m_northbounderManager.onAlarmCreatedOrUpdatedWithReducedEvent(alarm);
    }

    @Override
    public void didAcknowledgeAlarm(OnmsAlarm alarm, String previousAckUser, Date previousAckTime) {
        onNewOrUpdatedAlarm(alarm);
    }

    @Override
    public void didUnacknowledgeAlarm(OnmsAlarm alarm, String previousAckUser, Date previousAckTime) {
        onNewOrUpdatedAlarm(alarm);
    }

    @Override
    public void didUpdateAlarmSeverity(OnmsAlarm alarm, OnmsSeverity previousSeverity) {
        onNewOrUpdatedAlarm(alarm);
    }

    @Override
    public void didDeleteAlarm(OnmsAlarm alarm) {
        m_alarmLifecycleListenerManager.onAlarmDeleted(alarm);
    }

    @Override
    public void didUpdateSticky(OnmsAlarm alarm, String previousBody, String previousAuthor, Date previousUpdated) {
        onNewOrUpdatedAlarm(alarm);
    }

    @Override
    public void didUpdateReductionKeyMemo(OnmsAlarm alarm, String previousBody, String previousAuthor, Date previousUpdated) {
        onNewOrUpdatedAlarm(alarm);
    }

    @Override
    public void didRemoveStickyMemo(OnmsAlarm alarm, OnmsMemo memo) {
        onNewOrUpdatedAlarm(alarm);
    }

    @Override
    public void didRemoveReductionKeyMemo(OnmsAlarm alarm, OnmsReductionKeyMemo memo) {
        onNewOrUpdatedAlarm(alarm);
    }

    private void onNewOrUpdatedAlarm(OnmsAlarm alarm) {
        m_alarmLifecycleListenerManager.onNewOrUpdatedAlarm(alarm);
    }

    public void setNorthbounderManager(NorthbounderManager northbounderManager) {
        m_northbounderManager = northbounderManager;
    }

    public void setAlarmLifecycleListenerManager(AlarmLifecycleListenerManager alarmLifecycleListenerManager) {
        m_alarmLifecycleListenerManager = alarmLifecycleListenerManager;
    }
}
