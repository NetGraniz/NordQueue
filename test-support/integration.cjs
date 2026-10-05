'use strict'
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const net = require('node:net')
const { spawn } = require('node:child_process')
const mineflayer = require('mineflayer')
const root = path.resolve(process.argv[2] || '')
const java = process.argv[3]
const modern = process.argv.includes('--modern')
const localSecret = require('node:crypto').randomBytes(32).toString('hex')
assert(root.startsWith('C:\\Users\\artyo\\Documents\\Codex\\nordqueue-test-'))
assert(java)
const children = []
const clients = new Set()
const results = []
let proxy
let mainServer
let sequence = 0
const sleep = ms => new Promise(resolve => setTimeout(resolve, ms))
async function until(fn, description, timeout = 15000) {
  const start = Date.now()
  while (!await fn()) {
    if (Date.now() - start > timeout) throw Error('Timeout: ' + description)
    await sleep(100)
  }
}
function pass(name) { results.push(name); console.log('PASS: ' + name) }
function start(name, jar, heap) {
  const child = spawn(java, ['-Xms64M', '-Xmx' + heap, '-jar', jar, ...(name === 'paper' ? ['nogui'] : [])],
    { cwd: path.join(root, name), windowsHide: true, stdio: ['pipe', 'pipe', 'pipe'] })
  const handle = { child, name, output: '', exited: false, index: children.filter(h => h.name === name).length }
  children.push(handle)
  child.stdout.on('data', b => { handle.output += b.toString().replace(/\x1b\[[0-9;]*m/g, '') })
  child.stderr.on('data', b => { handle.output += b.toString().replace(/\x1b\[[0-9;]*m/g, '') })
  child.on('exit', () => { handle.exited = true })
  child.on('error', e => { handle.output += String(e); handle.exited = true })
  return handle
}
function portReady(port) {
  return new Promise(resolve => {
    const socket = net.connect({ host: '127.0.0.1', port })
    socket.on('connect', () => { socket.destroy(); resolve(true) })
    socket.on('error', () => resolve(false))
  })
}
function command(text) { proxy.child.stdin.write(text + '\n') }
async function state() {
  const id = 's' + (++sequence)
  command('qtest state ' + id)
  const regex = new RegExp('QSTATE ' + id + ' (\\{[^\\r\\n]+\\})')
  await until(() => regex.test(proxy.output), 'proxy state ' + id, 5000)
  const result = JSON.parse(proxy.output.match(regex)[1])
  assert(result.main.length <= 1, 'Main capacity exceeded: ' + JSON.stringify(result))
  assert.equal(result.regular + result.priority, result.waiting.length)
  return result
}
async function storageState() {
  const id = 'store' + (++sequence)
  command('qtest storagestate ' + id)
  const regex = new RegExp('STORESTATE ' + id + ' (\\{[^\\r\\n]+\\})')
  await until(() => regex.test(proxy.output), 'storage state ' + id, 5000)
  return JSON.parse(proxy.output.match(regex)[1])
}
async function waitStorage(predicate, label, timeout = 15000) {
  let current
  await until(async () => { current = await storageState(); return predicate(current) }, label, timeout)
  return current
}
async function waitState(predicate, description, timeout = 15000) {
  let latest
  await until(async () => { latest = await state(); return predicate(latest) }, description, timeout)
  return latest
}
function connect(name) {
  const bot = mineflayer.createBot({ host: '127.0.0.1', port: 25615, username: name,
    version: '26.2', auth: 'offline', hideErrors: true, checkTimeoutInterval: 30000 })
  const client = { name, bot, ended: false, messages: [], errors: [] }
  clients.add(client)
  bot.on('end', () => { client.ended = true })
  bot.on('messagestr', m => client.messages.push(m))
  bot.on('error', e => client.errors.push(String(e)))
  return client
}
async function disconnect(client) {
  if (!client.ended) client.bot.quit()
  await until(() => client.ended, 'disconnect ' + client.name)
  await sleep(250)
}
function fixture() {
  fs.writeFileSync(path.join(root, 'proxy', 'local-test-forwarding.secret'), localSecret)
  fs.writeFileSync(path.join(root, 'proxy', 'velocity.toml'), `config-version = "2.9"
bind = "127.0.0.1:25615"
motd = "LOCAL_QUEUE_TEST"
show-max-players = 1000
online-mode = false
force-key-authentication = false
player-info-forwarding-mode = "${modern ? 'modern' : 'none'}"
forwarding-secret-file = "local-test-forwarding.secret"
[servers]
queue = "127.0.0.1:25617"
main = "127.0.0.1:25616"
try = ["queue"]
[forced-hosts]
"local.invalid" = ["queue"]
[advanced]
login-ratelimit = 0
connection-timeout = 2000
read-timeout = 10000
failover-on-unexpected-server-disconnect = true
`)
  for (const [name, port] of [['main', 25616], ['queue', 25617]]) {
    fs.writeFileSync(path.join(root, name, 'settings.yml'), `bind:
  ip: "127.0.0.1"
  port: ${port}
maxPlayers: -1
ping:
  description: "LOCAL_TEST"
  version: "LOCAL_TEST"
  protocol: -1
dimension: THE_END
gameMode: 3
secureProfile: false
playerList:
  enable: false
  username: "LocalTest"
headerAndFooter:
  enable: false
  header: ""
  footer: ""
brandName:
  enable: true
  content: "LOCAL_${name}"
infoForwarding:
  type: ${modern ? 'MODERN' : 'NONE'}
  secret: "${localSecret}"
joinMessage:
  enable: false
  text: ""
bossBar:
  enable: false
  text: ""
  health: 1.0
  color: BLUE
  division: SOLID
title:
  enable: false
  title: ""
  subtitle: ""
  fadeIn: 0
  stay: 20
  fadeOut: 0
readTimeout: 30000
logPlayersIp: false
debugLevel: 2
netty:
  transportType: NIO
  threads:
    bossGroup: 1
    workerGroup: 2
traffic:
  enable: false
  maxPacketSize: 8192
  interval: 7.0
  maxPacketRate: 500.0
  maxPacketBytesRate: 2048.0
`)
  }
  const configDir = path.join(root, 'proxy', 'plugins', 'nordqueue')
  fs.mkdirSync(configDir, { recursive: true })
  fs.writeFileSync(path.join(configDir, 'suspended-bans.properties'), '# LOCAL SYNTHETIC STORE RESET\n')
  fs.writeFileSync(path.join(configDir, 'config.properties'), `main-capacity=1
minimum-wait-seconds=0
transfer-interval-seconds=1
failed-retry-seconds=5
connection-attempt-timeout-seconds=3
display-interval-millis=1000
`)
  fs.writeFileSync(path.join(configDir, 'priority-players.txt'), 'NQPriority\n')
  fs.writeFileSync(path.join(root, 'paper', 'server.properties'), `server-ip=127.0.0.1
server-port=25616
online-mode=false
enforce-secure-profile=false
max-players=20
view-distance=2
simulation-distance=2
enable-rcon=false
enable-query=false
level-name=nordqueue-auth-test-world
pause-when-empty-seconds=-1
`)
  const authConfig = fs.readFileSync(path.join(__dirname, '..', '..', 'NordAuth', 'src', 'main', 'resources', 'config.yml'), 'utf8')
  const authDir = path.join(root, 'paper', 'plugins', 'NordAuth')
  fs.mkdirSync(authDir, { recursive: true })
  fs.writeFileSync(path.join(authDir, 'config.yml'), authConfig.replace('login-timeout-seconds: 60', 'login-timeout-seconds: 2'))
  const globalPath = path.join(root, 'paper', 'config', 'paper-global.yml')
  const global = fs.readFileSync(globalPath, 'utf8')
  const velocitySection = /  velocity:\r?\n    enabled: (?:true|false)\r?\n    online-mode: (?:true|false)\r?\n    secret: [^\r\n]*/
  assert(velocitySection.test(global), 'known local Paper velocity settings required')
  fs.writeFileSync(globalPath, global.replace(velocitySection,
    '  velocity:\n    enabled: ' + modern + '\n    online-mode: false\n    secret: ' + JSON.stringify(localSecret)))
}
async function main() {
  fixture()
  const queueServer = start('queue', 'NanoLimbo.jar', '256M')
  mainServer = start('main', 'NanoLimbo.jar', '256M')
  await until(() => portReady(25616), 'local main backend')
  await until(() => portReady(25617), 'local queue backend')
  proxy = start('proxy', 'velocity.jar', '512M')
  await until(() => /LOCAL_QUEUE_PROBE_READY/.test(proxy.output) || proxy.exited, 'local Velocity startup', 30000)
  assert(!proxy.exited, proxy.output)
  await until(() => portReady(25615), 'local proxy listener')
  const a = connect('NQSlotA')
  await waitState(s => s.main.includes(a.name), 'first player enters main')
  const b = connect('NQSlotB')
  await waitState(s => s.waiting.includes(b.name) && s.limbo.includes(b.name), 'B enters queue before starting C')
  const c = connect('NQSlotC')
  await waitState(s => s.waiting.length === 2 && s.limbo.length === 2, 'two players wait while main is full')
  await sleep(1500)
  assert.equal((await state()).main[0], a.name)
  await disconnect(a)
  await waitState(s => s.main.includes(b.name) && s.waiting.includes(c.name), 'FIFO after slot release')
  await disconnect(b)
  await waitState(s => s.main.includes(c.name), 'next FIFO player')
  await disconnect(c)
  await waitState(s => s.main.length === 0 && s.waiting.length === 0, 'queue empty')
  pass('Real proxy FIFO, main capacity, slot reservation and disconnect cleanup')

  command('qtest block NQBlockedHead')
  const head = connect('NQBlockedHead')
  await waitState(s => s.waiting.includes(head.name), 'blocked head joins queue')
  await sleep(1200)
  const next = connect('NQAfterHead')
  await waitState(s => s.main.includes(next.name) && s.waiting.includes(head.name), 'retry head does not block next player')
  pass('Rejected head retains place but does not block an eligible follower')
  await disconnect(head); await disconnect(next)
  command('qtest block')

  command('qtest hold NQHanging')
  const hanging = connect('NQHanging')
  await waitState(s => s.held === 1, 'controlled hung connection')
  const afterHang = connect('NQAfterHang')
  await until(() => hanging.ended, 'connection watchdog disconnects timed-out session', 8000)
  await waitState(s => s.main.includes(afterHang.name), 'watchdog releases slot for next player')
  command('qtest resume')
  await sleep(500)
  assert.equal((await state()).main[0], afterHang.name)
  pass('Hung connection times out; late event cannot admit it or release another reservation')
  await disconnect(afterHang)

  command('qtest hold NQReconnect')
  const old = connect('NQReconnect')
  await waitState(s => s.held === 1, 'old connection held')
  await disconnect(old)
  const replacement = connect('NQReconnect')
  await waitState(s => s.held === 2, 'new connection has its own pending attempt')
  command('qtest resume')
  await waitState(s => s.main.includes(replacement.name), 'replacement reaches main')
  assert(!replacement.ended)
  pass('Disconnect/reconnect during a pending transfer ignores the old connection result')
  await disconnect(replacement)

  const survivor = connect('NQRestart')
  await waitState(s => s.main.includes(survivor.name), 'player enters main before backend failure')
  mainServer.child.kill()
  await waitState(s => s.limbo.includes(survivor.name) && s.waiting.includes(survivor.name), 'backend failure returns player to limbo')
  assert(!survivor.ended)
  mainServer = start('main', 'NanoLimbo.jar', '256M')
  await until(() => portReady(25616), 'restarted backend')
  await waitState(s => s.main.includes(survivor.name), 'backend recovery drains queue', 15000)
  pass('Backend loss returns player to queue and backend recovery restores admission')
  const queued = connect('NQReload')
  await waitState(s => s.waiting.includes(queued.name), 'waiting player before reload')
  for (let n = 0; n < 5; n++) command('nordqueue reload')
  await sleep(1000)
  const reloaded = await state()
  assert(reloaded.main.includes(survivor.name) && reloaded.waiting.includes(queued.name))
  await disconnect(survivor)
  await waitState(s => s.main.includes(queued.name), 'queue still works after repeated reloads')
  pass('Repeated configuration reloads preserve queue order and capacity')
  await disconnect(queued)
  await waitState(s => !s.main.length && !s.limbo.length && !s.waiting.length, 'final cleanup')
  assert(!/NoSuchMethodError|ClassCastException|ConcurrentModificationException/.test(proxy.output), 'plugin API/concurrency error')
  pass('Existing NordQueueTab and NordQueueNotice JARs work with the preserved public API')
  const keeper = connect('NQPriorityKeeper')
  await waitState(s => s.main.includes(keeper.name), 'keeper occupies main')
  const regular = connect('NQRegular')
  await waitState(s => s.waiting.includes(regular.name), 'regular waiting first')
  const priority = connect('NQPriority')
  await waitState(s => s.priority === 1 && s.waiting[0] === priority.name, 'priority waiting ahead of regular')
  await disconnect(keeper)
  await waitState(s => s.main.includes(priority.name) && s.waiting.includes(regular.name), 'priority admitted first')
  await disconnect(priority)
  await waitState(s => s.main.includes(regular.name), 'regular admitted afterwards')
  pass('Priority FIFO precedes regular FIFO with unchanged per-group API semantics')

  const beforeBan = connect('NQBeforeBan')
  await waitState(s => s.waiting.includes(beforeBan.name), 'first regular waiting')
  const banned = connect('NQBanned')
  await waitState(s => s.waiting.includes(banned.name), 'second regular waiting')
  command('qtest ban ' + banned.name)
  await waitState(s => s.limbo.includes(banned.name) && !s.waiting.includes(banned.name), 'ban excludes player from queues')
  command('qtest unban ' + banned.name)
  await waitState(s => s.waiting.join(',') === beforeBan.name + ',' + banned.name, 'unban appends to regular queue')
  assert(!banned.ended)
  pass('Controlled ban/unban keeps suspended player in limbo and returns it to the regular tail')
  await disconnect(regular); await disconnect(beforeBan); await disconnect(banned)
  await waitState(s => !s.main.length && !s.waiting.length, 'empty before auth interoperability')
  const storageKeeper = connect('NQStoreKeeper')
  await waitState(s => s.main.includes(storageKeeper.name), 'main occupied for storage fault tests')
  const heldByBan = connect('NQStoreBanned')
  await waitState(s => s.waiting.includes(heldByBan.name), 'local banned test target queues')
  command('qtest ban ' + heldByBan.name)
  await waitStorage(s => s.ready && s.active.includes(heldByBan.name), 'durable synthetic ban')
  const beforeFailedUnban = fs.readFileSync(path.join(root, 'proxy', 'plugins', 'nordqueue', 'suspended-bans.properties'))
  command('qtest storagefault true'); command('qtest unban ' + heldByBan.name)
  await waitStorage(s => !s.ready && s.problem === 'IOException', 'injected proxy UNBAN failure')
  assert.deepEqual(fs.readFileSync(path.join(root, 'proxy', 'plugins', 'nordqueue', 'suspended-bans.properties')), beforeFailedUnban)
  const heldState = await storageState()
  assert(heldState.active.includes(heldByBan.name))
  assert(!(await state()).waiting.includes(heldByBan.name))
  pass('Receiver failed UNBAN leaves ban and file unchanged; admission fails closed')
  await disconnect(storageKeeper)
  const protectedFollower = connect('NQStoreFollower')
  await waitState(s => s.limbo.includes(protectedFollower.name), 'unbanned player remains in limbo during storage failure')
  await sleep(1200)
  assert.equal((await state()).main.length, 0)
  assert(!protectedFollower.ended)
  pass('Storage error holds new main admission without disconnecting the waiting client')
  command('qtest storagefault false')
  await waitStorage(s => s.ready && !s.active.includes(heldByBan.name), 'automatic storage retry recovers', 12000)
  await waitState(s => s.main.includes(protectedFollower.name), 'admission resumes after durable recovery')
  await disconnect(protectedFollower)
  await waitState(s => s.main.includes(heldByBan.name), 'committed UNBAN restores regular queue tail')
  pass('Automatic retry durably clears ban and resumes queue without a proxy restart')
  await disconnect(heldByBan)
  command('qtest storageslow 3000'); command('qtest ban NQSlowSynthetic')
  await waitStorage(s => !s.ready && s.pendingCount > 0, 'slow storage operation admitted')
  const slowObserver = connect('NQSlowObserver')
  await waitState(s => s.limbo.includes(slowObserver.name), 'network keeps serving limbo during slow disk work')
  assert(!(await storageState()).ready, 'the slow write must still be in progress')
  assert(!slowObserver.ended)
  command('qtest storageslow 0')
  await waitStorage(s => s.ready, 'slow operation commits')
  await waitState(s => s.main.includes(slowObserver.name), 'observer admitted after slow persistence')
  command('qtest unban NQSlowSynthetic')
  await waitStorage(s => s.ready && !s.active.includes('NQSlowSynthetic'), 'synthetic cleanup')
  assert.equal((await storageState()).writers, 1)
  for(let n=0;n<5;n++)command('nordqueue reload')
  await sleep(500)
  assert.equal((await storageState()).writers, 1)
  pass('Slow ban write does not block network; repeated reloads keep exactly one storage writer')
  await disconnect(slowObserver)
  mainServer.child.kill()
  await until(() => mainServer.exited, 'NanoLimbo main stopped')
  mainServer = start('paper', 'server.jar', '2G')
  await until(() => /Done \(/.test(mainServer.output) || mainServer.exited, 'isolated Paper with NordAuth', 120000)
  assert(!mainServer.exited, mainServer.output.slice(-4000))
  assert.match(mainServer.output, /NordAuth enabled/)
  // The 3-second watchdog above is fault injection, not a sensible cold Paper login limit.
  // Restore the production default for the actual Paper interoperability check.
  const queueConfigPath = path.join(root, 'proxy', 'plugins', 'nordqueue', 'config.properties')
  const currentConfig = fs.readFileSync(queueConfigPath, 'utf8')
  fs.writeFileSync(queueConfigPath, currentConfig.replace('connection-attempt-timeout-seconds=3', 'connection-attempt-timeout-seconds=30'))
  command('nordqueue reload')
  await sleep(500)
  const authTimeout = connect('NQAuthTimeout')
  await waitState(s => s.main.includes(authTimeout.name), 'unauthenticated client enters isolated Paper')
  await until(() => authTimeout.ended, 'NordAuth timeout disconnects proxy client', 15000)
  await sleep(2500)
  const afterTimeout = await state()
  assert(!afterTimeout.main.includes(authTimeout.name) && !afterTimeout.waiting.includes(authTimeout.name))
  pass('Real Paper + NordAuth login timeout disconnects rather than looping back into queue')
  command('shutdown')
  await until(() => proxy.exited, 'proxy stopped before corrupt synthetic store test')
  fs.writeFileSync(path.join(root, 'proxy', 'plugins', 'nordqueue', 'suspended-bans.properties'), 'corrupt=not-a-valid-ban\n')
  proxy = start('proxy', 'velocity.jar', '512M')
  await until(() => /LOCAL_QUEUE_PROBE_READY/.test(proxy.output) || proxy.exited, 'proxy with corrupt synthetic store starts in maintenance mode', 30000)
  assert(!proxy.exited)
  assert.match(proxy.output, /Ban storage failed strict initialization/)
  await until(() => portReady(25615), 'restarted proxy listener')
  const rejectedByStore = connect('NQCorruptHold')
  await waitState(s => s.limbo.includes(rejectedByStore.name), 'corrupt store allows queue but not main')
  await sleep(2500)
  assert.equal((await state()).main.length, 0)
  assert(!rejectedByStore.ended)
  pass('Corrupt proxy ban store fails closed for main admission; limbo remains available')
  await disconnect(rejectedByStore)
  fs.writeFileSync(path.join(root, 'integration-results.json'), JSON.stringify({ passed: results,
    total: results.length, forwarding: modern ? 'MODERN; fresh isolated random secret' : 'NONE',
    fixture: '127.0.0.1:25615/25616/25617; synthetic users; local Velocity/NanoLimbo/Paper/NordAuth' }, null, 2))
  console.log('ALL ' + results.length + ' LOCAL QUEUE INTEGRATION SCENARIOS PASSED')
}
main().catch(e => { console.error(e.stack); process.exitCode = 1 }).finally(async () => {
  for (const c of clients) if (!c.ended) c.bot.quit()
  if (proxy && !proxy.exited) {
    command('qtest resume'); command('shutdown')
    await until(() => proxy.exited, 'proxy shutdown', 15000).catch(() => proxy.child.kill())
  }
  for (const h of children) {
    if (!h.exited && h.name === 'paper') {
      h.child.stdin.write('stop\n')
      await until(() => h.exited, 'Paper graceful shutdown', 45000).catch(() => h.child.kill())
    }
    if (!h.exited) h.child.kill()
    fs.writeFileSync(path.join(root, h.name + '-' + h.index + '-test-output.log'), h.output)
  }
})
