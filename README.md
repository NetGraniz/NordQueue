# NordQueue

> Release build and installation requirements: see [BUILDING.md](BUILDING.md).
> Older local paths below describe historical test fixtures, not the release build.

Velocity queue core for Nord Fjell. Players enter the NanoLimbo server named `queue`, are
kept in FIFO order, and move to `main` when capacity is available. A persistent title is
resent while waiting. Kicks caused by a backend restart redirect players back to the queue.
Authentication timeouts disconnect the player instead of creating an endless
queue-to-main reconnect loop.

Priority players are listed one username or UUID per line in
`plugins/nordqueue/priority-players.txt`. Priority uses a separate FIFO and is transferred
before the regular queue. The server-list player hover reports in-game, regular queue, and
priority queue counts.

NordBans synchronization adds a third persistent state: suspended players remain in the
limbo server, are excluded from both queues, and see the reason and remaining time. When
the ban expires or is removed, they join the end of the regular queue. The proxy also
recognizes a structured Paper kick marker from the trusted main backend so missed plugin messages repair themselves
when a suspended player next reaches the backend.

Commands: `/queue`, `/qposition`, and `/nordqueue reload` (`nordqueue.admin`).

## 1.1.2 ban receiver storage

Prepared release; production deployment is separate. See SECURITY-1.1.2.md.
Immutable snapshots publish after successful atomic persistence; loading is strict.
A bounded latest-per-account inbox batches up to 256 changes on one storage worker.
Duplicate committed records do not write again. Pending BAN restricts immediately;
UNBAN releases only after commit. Main admission pauses while synchronization/storage
is unfinished or unhealthy, instead of letting a failed write bypass a ban.
I/O errors retain work and retry after five seconds; corrupt initialization requires
repair/restart and overflow requires reconciliation. Limbo may continue serving clients.
V1 has no acknowledgments; this does not guarantee delivery of every transport message.

The build also runs 22 BanStorageTest scenarios. The queue integration harness runs
15 scenarios and supports --modern with fresh local-only forwarding secrets.
test-support/build-probe.ps1 prepares a helper for local fixtures only. The separate
bans-compatibility.cjs runs 11 NordBans interoperability scenarios. Never install probes
or local fixture configuration on production.

## 1.1.1 queue/session fixes

- The queue, enqueue timestamps, retry deadlines and capacity reservation now live under one
  state monitor. Each proxy connection has an identity-based session token; each transfer also
  identifies the particular queue entry. Late callbacks and disconnect events cannot mutate a
  replacement connection or a newly queued entry for the same player.
- Selection skips temporarily ineligible heads without changing their FIFO position. Eligible
  priority players still precede eligible regular players. A single pending transfer reserves the
  next slot; admission is single-use and valid only for the selected connection and target.
- A monotonic-clock minimum interval between attempts enforces `transfer-interval-seconds`
  even if scheduled tasks catch up or configuration is repeatedly reloaded. With the existing
  default, there is at most one new attempt per second; successful throughput can be lower.
- `connection-attempt-timeout-seconds` is a new optional setting (default 30, range 1..300).
  A hung attempt invalidates and disconnects its exact connection before allowing a fresh one.
  This prevents late network results from admitting a timed-out player into a reserved slot.
- Snapshot readers reuse an immutable, consistent `QueueSnapshot`; positions are computed once
  per queue change instead of by repeated linear searches. `position(UUID)` retains its existing
  per-group meaning; `snapshot().absolutePosition(UUID)` provides the combined position.
- Titles use one queue snapshot per refresh. Membership checks are constant-time. All existing
  public methods used by NordQueueTab/NordQueueNotice remain available.
- Default NordAuth `Login timed out.` and temporary database-unavailability kicks are terminal,
  as are the older AuthMe timeout strings. Other backend failures fall back to limbo when it is
  available. Custom/localized authentication kick messages require explicit recognition; do not
  assume arbitrary text is recognized.
- Reload keeps the last valid settings on failure, retains retry/enqueue metadata and invalidates
  obsolete scheduled-task generations. Changing main/queue server names with players connected
  requires a proxy restart. Shutdown clears queue/session state and cancels plugin timers.

`estimatedSeconds(UUID)` remains a compatibility estimate of transfer scheduling only. It cannot
predict when a full main server will free a slot; it is not a reliable waiting-time ETA.
NordQueueTab's full-list fan-out and disconnected-viewer cleanup were addressed in
its separately released 1.1.0. Ban-storage I/O/batching is addressed in this project's
1.1.2. Priority identity verification in offline mode and acknowledged reconciliation
remain separate work.

## Build and isolated tests

Use Java 25 and `build.ps1 -ProxyPath <directory containing velocity.jar>`. For verification, the
Velocity executable was copied to a local fixture; production was not started, stopped or edited.
The build runs the existing `BanProtocolTest` and 15 queue-state regression scenarios, including
1000 synthetic entries with concurrent snapshot readers. These are algorithm tests, not proof
of supporting 1000 connected clients.

All source, test helpers and release artifacts stay in this project on the network drive.
The local fixture is `C:\Users\artyo\Documents\Codex\nordqueue-test-20261003`:

- `proxy`: copied Velocity executable, NordQueue 1.1.1, unchanged NordQueueTab/NordQueueNotice
  JARs, and a **local-test-only** `NordQueueTestProbe`.
- `queue` / `main`: copied NanoLimbo executables with newly generated local configuration.
- `paper`: copied Paper executable/libraries and the existing accepted EULA, a new world and
  synthetic database, and the already verified NordAuth 1.2.2 release. No production world,
  credentials, proxy forwarding secrets or operational configuration is copied.

The helper in `test-support/probe` must be compiled against the same Velocity executable and
the built NordQueue JAR, packaged with its own `velocity-plugin.json`, and installed **only**
in the local proxy. It injects controlled denial/delay and ban/unban scenarios through a
console-only command. Never install this probe on production.

The `test-support/integration.cjs` harness prepares its own configuration, starts hidden child
processes on loopback ports 25615/25616/25617, runs ten integration scenarios, and stops them.
Its clients use the locally cached pinned NordLoadTest/Mineflayer dependencies prepared during
the NordAuth tests. Set `NODE_PATH` to that local `clients/node_modules` directory and run:

```powershell
node 'Z:\Minecraft Plagins\NordQueue\test-support\integration.cjs' `
  'C:\Users\artyo\Documents\Codex\nordqueue-test-20261003' `
  'C:\Program Files\Java\jdk-25\bin\java.exe'
```

The fixture disables forwarding/rate limiting **locally only** to exercise multiple synthetic
clients. It does not validate production modern-forwarding security or WAN load handling.
The 3-second injected watchdog is restored to the default 30 seconds for cold Paper logins.
The report and checksums for this verified release are in `QUEUE-1.1.1.md`.
