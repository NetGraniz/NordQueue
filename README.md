# NordQueue

Velocity queue, priority access, failover, queue notices, and queue TAB presentation in one plugin.

NordQueue 1.3.0 replaces the standalone NordQueueNotice and NordQueueTab plugins. Their settings remain separated inside one directory:

- `plugins/nordqueue/config.properties` — queue, priority, failover, and NordBans database path
- `plugins/nordqueue/notice.properties` — recurring queue notices
- `plugins/nordqueue/tab.properties` — queue TAB presentation
- `plugins/nordqueue/priority.db` — priority players

On first start, missing integrated configuration files are copied from the old `plugins/nordqueuenotice` and `plugins/nordqueuetab` directories when available.

## NordBans integration

Set `nordbans-database-path` in `config.properties` to the NordBans `bans.db` file. Relative paths resolve from `plugins/nordqueue`.

NordQueue opens the database read-only and refreshes an immutable in-memory snapshot asynchronously. There is no plugin-message ban channel and no duplicate proxy ban store. If a configured database becomes unreadable, ban-sensitive admission fails closed and `/nordqueue health` reports the problem.

## Commands

- `/nordqueue status`
- `/nordqueue health`
- `/nordqueue reload`
- `/nordqueue notice reload`
- `/nordqueue tab reload`
- `/nordqueue priority add|remove|list <player>`

All reload commands validate a new settings snapshot before replacing active settings.

## Platform

- Java 25
- Velocity 4.2.1 snapshot used by the project
- SQLite JDBC is shaded into the release JAR

See [BUILDING.md](BUILDING.md) for the verified build command.
