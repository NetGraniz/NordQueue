# NordQueue

Queue core for Velocity. Players wait on the NanoLimbo backend named `queue` and move to `main` in FIFO order when capacity is available.

A title repeats while they wait. Backend-restart kicks return players to the queue. Authentication timeouts disconnect them instead of creating a queue-to-main reconnect loop.

## Priority and suspended players

List priority players in `plugins/nordqueue/priority-players.txt`, one username or UUID per line. Priority players have their own FIFO queue and transfer before eligible regular players.

The server-list player hover shows counts for the main server, regular queue and priority queue.

NordBans synchronization adds a persistent suspended state. Suspended players stay in limbo outside both queues and see the ban reason and remaining time. Expiry or unban places them at the end of the regular queue.

A structured Paper kick marker from the trusted main backend can repair a missed plugin message when a suspended player next reaches that backend.

## Commands

- `/queue` or `/qposition` shows a player's position or suspension state.
- `/nordqueue reload` reloads settings and the priority roster.

## Permissions

| Permission | Allows |
| --- | --- |
| `nordqueue.admin` | `/nordqueue` management, including `reload` |

Player access comes from Velocity's permission provider; NordQueue registers no default player grant for its admin node. Backend-only permissions do not grant proxy rights.

`/queue` and `/qposition` have no dedicated permission node and are player-only. Priority comes from `priority-players.txt`, not a permission node. Review offline-mode identity trust before assigning priority by name or UUID.

## 1.1.2 ban receiver storage

The historical report is `SECURITY-1.1.2.md`, excluded from the public repository. Deployment is separate from building or testing the release.

Immutable ban snapshots publish after successful atomic persistence. Loading is strict. A bounded latest-per-account inbox batches up to 256 changes on one storage worker; duplicate committed records do not write again.

Pending BAN restricts immediately. UNBAN releases only after commit. Main-server admission pauses while synchronization or storage is unfinished or unhealthy, so a failed write cannot bypass a ban.

I/O errors retain pending work and retry after 5 seconds. Corrupt initialization requires repair and restart; inbox overflow requires reconciliation. Limbo can continue serving clients.

The V1 protocol has no acknowledgments and does not guarantee delivery of every transport message.

The build runs 22 `BanStorageTest` scenarios. The queue integration harness runs 15 scenarios; `--modern` creates fresh local-only forwarding secrets. `test-support/build-probe.ps1` builds a helper for fixtures only. The separate `bans-compatibility.cjs` checks 11 NordBans interoperability scenarios.

Never install probes or fixture configuration on production.

## 1.1.1 queue and session fixes

The queue, enqueue timestamps, retry deadlines and capacity reservation share one state monitor. Each connection has an identity-based session token; each transfer also identifies its queue entry. Late callbacks and disconnect events cannot mutate a replacement connection or a newly queued entry for that player.

Selection skips temporarily ineligible heads without changing their FIFO position. Eligible priority players still precede eligible regular players. One pending transfer reserves the next slot; its single-use admission belongs to the selected connection and target.

A monotonic-clock minimum interval enforces `transfer-interval-seconds`, including during scheduler catch-up or repeated reloads. The default allows at most one new attempt per second. Successful transfers can be slower.

`connection-attempt-timeout-seconds` defaults to 30 and accepts 1..300. A hung attempt invalidates and disconnects its exact connection before a new attempt starts. A late network result cannot reuse the timed-out admission.

Readers share an immutable `QueueSnapshot`. Positions are computed once per queue change, not through repeated linear searches. `position(UUID)` remains per-group; `snapshot().absolutePosition(UUID)` returns the combined position.

Titles use one snapshot per refresh. Membership checks are constant-time, and public methods used by NordQueueTab and NordQueueNotice remain available.

NordAuth's default `Login timed out.` and temporary database-unavailability kicks are terminal, as are legacy AuthMe timeout strings. Other backend failures fall back to limbo when it is available. Custom or localized authentication messages need explicit recognition.

A failed reload keeps valid settings and enqueue/retry metadata. Reload invalidates obsolete task generations. Changing main or queue server names while players are connected requires a proxy restart. Shutdown clears queue/session state and cancels plugin timers.

`estimatedSeconds(UUID)` estimates transfer scheduling only. It cannot predict when a full main server will free a slot and is not a reliable waiting-time ETA.

NordQueueTab 1.1.0 addresses full-list fan-out and disconnected-viewer cleanup. NordQueue 1.1.2 addresses ban-storage I/O and batching. Offline-mode priority identity verification and acknowledged reconciliation remain separate work.

## Build and isolated tests

Use Maven 3.9+ and JDK 25. Run `mvn clean verify` or `./build.ps1`; see [BUILDING.md](BUILDING.md). The release build does not use a live proxy directory.

The build includes `BanProtocolTest` and 15 queue-state regression scenarios, including 1000 synthetic entries with concurrent snapshot readers. Algorithm tests do not establish capacity for 1000 connected clients.

### Historical 1.1.1 fixture

The earlier verification used `C:\Users\artyo\Documents\Codex\nordqueue-test-20261003` with these isolated components:

- `proxy`: copied Velocity executable, NordQueue 1.1.1, unchanged NordQueueTab/NordQueueNotice JARs and the test-only `NordQueueTestProbe`.
- `queue` and `main`: copied NanoLimbo executables with fresh local configuration.
- `paper`: copied executable/libraries and accepted EULA, a new world, a synthetic database and NordAuth 1.2.2.

No production world, credentials, forwarding secrets or operational configuration were copied. Production was not started, stopped or edited for that verification. The old network-share source path and `-ProxyPath` build workflow are historical, not the current release build.

Compile `test-support/probe` against the fixture's Velocity API and built NordQueue JAR, with its own `velocity-plugin.json`. Its console-only commands inject controlled denial, delay, ban and unban scenarios. Install it only on the local proxy.

`test-support/integration.cjs` prepares local configuration, starts hidden processes on loopback ports 25615/25616/25617, runs checks and stops the processes. The original 1.1.1 fixture ran ten integration scenarios; later receiver tests expanded the suite.

Clients use locally cached, pinned NordLoadTest/Mineflayer dependencies prepared for the NordAuth checks. Set `NODE_PATH` to the local `clients/node_modules` directory:

```powershell
node ./test-support/integration.cjs $FreshFixture $JavaExecutable
```

The historical fixture disabled forwarding and rate limiting locally to exercise synthetic clients. It did not test production modern-forwarding security or WAN load. Modern-forwarding checks use the separate `--modern` mode described above.

The injected 3-second watchdog is restored to the default 30 seconds for cold Paper logins. The historical report and checksums are in `QUEUE-1.1.1.md`, excluded from the public repository.
