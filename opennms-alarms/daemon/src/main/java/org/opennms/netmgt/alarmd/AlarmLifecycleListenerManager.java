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

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

import org.opennms.netmgt.alarmd.api.AlarmLifecycleListener;
import org.opennms.netmgt.alarmd.api.AlarmLifecycleSubscriptionService;
import org.opennms.netmgt.dao.api.AlarmDao;
import org.opennms.netmgt.model.OnmsAlarm;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallbackWithoutResult;
import org.springframework.transaction.support.TransactionTemplate;

public class AlarmLifecycleListenerManager implements AlarmLifecycleSubscriptionService {

    private static final Logger LOG = LoggerFactory.getLogger(AlarmLifecycleListenerManager.class);

    private final Set<AlarmLifecycleListener> listeners = new LinkedHashSet<>();
    private final ReadWriteLock rwLock = new ReentrantReadWriteLock();
    private Timer timer;

    @Autowired
    private AlarmDao alarmDao;

    @Autowired
    private TransactionTemplate template;

    public void start() {
        timer = new Timer("AlarmLifecycleListenerManager");
        timer.scheduleAtFixedRate(new TimerTask() {
            @Override
            public void run() {
                try {
                    doSnapshot();
                } catch (Exception e) {
                    LOG.error("Error while performing snapshot update.", e);
                }
            }
        }, 0, TimeUnit.SECONDS.toMillis(5));
    }

    public void stop() {
        if (timer != null) {
            timer.cancel();
            timer = null;
        }
    }
    private void doSnapshot() {
        rwLock.readLock().lock();
        try {
            if (listeners.size() < 1) {
                return;
            }
            template.execute(new TransactionCallbackWithoutResult() {
                @Override
                protected void doInTransactionWithoutResult(TransactionStatus status) {
                    final List<OnmsAlarm> allAlarms = alarmDao.findAll();
                    listeners.forEach(l -> l.handleAlarmSnapshot(allAlarms));
                }
            });
        } finally {
            rwLock.readLock().unlock();
        }
    }

    public void onNewOrUpdatedAlarm(OnmsAlarm alarm) {
        rwLock.readLock().lock();
        try {
            listeners.forEach(l -> l.handleNewOrUpdatedAlarm(alarm));
        } finally {
            rwLock.readLock().unlock();
        }
    }

    public void onAlarmDeleted(OnmsAlarm alarm) {
        final Integer alarmId = alarm.getId();
        final String reductionKey = alarm.getReductionKey();
        rwLock.readLock().lock();
        try {
            listeners.forEach(l -> l.handleDeletedAlarm(alarmId, reductionKey));
        } finally {
            rwLock.readLock().unlock();
        }
    }

    @Override
    public void addAlarmLifecyleListener(AlarmLifecycleListener listener) {
        rwLock.writeLock().lock();
        try {
            listeners.add(listener);
        } finally {
            rwLock.writeLock().unlock();
        }
    }

    @Override
    public void removeAlarmLifecycleListener(AlarmLifecycleListener listener) {
        rwLock.writeLock().lock();
        try {
            listeners.remove(listener);
        } finally {
            rwLock.writeLock().unlock();
        }
    }

}
