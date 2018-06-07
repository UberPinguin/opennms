# Input

Events are converted to alarms in `alarmd` (see `org.opennms.netmgt.alarmd.AlarmPersisterImpl`)

Alarms can be updated using:
 * The `org.opennms.netmgt.dao.api.AlarmRepository` (implemented in `org.opennms.netmgt.dao.hibernate.AlarmRepositoryHibernate`)
 * The `org.opennms.netmgt.dao.api.AcknowledgmentDao` (implemented in `org.opennms.netmgt.dao.hibernate.AcknowledgmentDaoHibernate`)

Alarm service

# Handling

Changes are recorded by the `AlarmEntityNotifier` via callbacks.

# Output

* Northbounders (`org.opennms.netmgt.alarmd.api.Northbounder`): Send one off messages, best-effort
** didCreate
** didUpdateAlarmWithReducedEvent

* Simple Listeners (`org.opennms.netmgt.alarmd.api.AlarmLifecycleListener`): A simple, but complete view of the alarms. Used to keep external components/systems in sync.
** BSM
** Kafka
** Drools

* Detailed Listener
** Audit log

# History

## Alarm Change Notifier

Depends on database triggers.

## Alarm Auditor





