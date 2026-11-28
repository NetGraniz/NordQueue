# NordQueue

Independent Velocity queue, priority access, failover, rotating Action Bar messages, notices, TAB presentation, and server-list label in one plugin. NordQueue 2.0.5 keeps all settings separated inside one directory:

- `plugins/nordqueue/config.properties` — queue, priority, failover, and NordBans database path
- `plugins/nordqueue/notice.properties` — recurring queue notices
- `plugins/nordqueue/tab.properties` — queue TAB presentation
- `plugins/nordqueue/actionbar.properties` — rotating bottom-screen messages and interval
- `plugins/nordqueue/ping.properties` — server-list version label
- `plugins/nordqueue/priority.db` — priority players

## Rotating Action Bar

`actionbar.properties` accepts `interval-seconds` and a contiguous list of `message.1` through `message.20`. Changes apply through `/nordqueue reload`.

Available placeholders are `<position>`, `<size>`, `<regular_size>`, `<priority_size>`, `<estimate>`, `<priority_estimate>`, `<regular_estimate>`, and `<skipped_last_hour>`. The last value counts successful priority admissions during the previous hour of the current proxy process.

## NordBans integration

Set `nordbans-database-path` in `config.properties` to the NordBans `bans.db` file. Relative paths resolve from `plugins/nordqueue`.

NordQueue opens the database read-only and refreshes an immutable in-memory snapshot asynchronously. There is no plugin-message ban channel and no duplicate proxy ban store. If a configured database becomes unreadable, ban-sensitive admission fails closed and `/nordqueue health` reports the problem.

## Commands

- `/nordqueue health`
- `/nordqueue reload`
- `/nordqueue priority add|remove|status <player>`

The single reload command validates and refreshes all current component settings.

## Platform

- Java 25
- Velocity 4.2.1 snapshot used by the project
- SQLite JDBC is shaded into the release JAR

See [BUILDING.md](BUILDING.md) for the verified build command.

## License

Current development on `main` is licensed under [GNU GPL v3.0 only](LICENSE). The latest published release remains available under the MIT License stated on its release page.
