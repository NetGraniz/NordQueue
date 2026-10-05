'use strict'
const assert = require('node:assert/strict'), fs = require('node:fs'), path = require('node:path'), net = require('node:net')
const { spawn } = require('node:child_process'), mineflayer = require('mineflayer')
const root = path.resolve(process.argv[2] || ''), java = process.argv[3]
assert(root.startsWith('C:\\Users\\artyo\\Documents\\Codex\\nordbans-test-')); assert(java)
const children = [], clients = [], results = []
let paper, proxy, sequence = 0
const sleep = ms => new Promise(resolve => setTimeout(resolve, ms))
async function until(fn, description, timeout = 20000) {
  const start = Date.now()
  while (!await fn()) { if (Date.now() - start > timeout) throw Error('Timeout: ' + description); await sleep(100) }
}
function pass(name) { results.push(name); console.log('PASS: ' + name) }
function start(name, jar, heap = '256M') {
  const child = spawn(java, ['-Xms64M', '-Xmx' + heap, '-jar', jar, ...(name === 'paper' ? ['nogui'] : [])],
    { cwd: path.join(root, name), windowsHide: true, stdio: ['pipe', 'pipe', 'pipe'] })
  const handle = { child, name, output: '', exited: false, index: children.filter(h => h.name === name).length }
  children.push(handle)
  for (const stream of [child.stdout, child.stderr]) stream.on('data', b => { handle.output += b.toString().replace(/\x1b\[[0-9;]*m/g, '') })
  child.on('exit', () => { handle.exited = true }); child.on('error', e => { handle.output += String(e); handle.exited = true })
  return handle
}
function portReady(port) {
  return new Promise(resolve => { const socket = net.connect({ host: '127.0.0.1', port }); socket.on('connect', () => { socket.destroy(); resolve(true) }); socket.on('error', () => resolve(false)) })
}
function command(handle, text) { handle.child.stdin.write(text + '\n') }
async function state(handle, label, prefix) {
  const id = 's' + (++sequence), regex = new RegExp(prefix + ' ' + id + ' (\\{[^\\r\\n]+\\})')
  command(handle, label + ' state ' + id); await until(() => regex.test(handle.output), prefix + ' state ' + id, 5000)
  return JSON.parse(handle.output.match(regex)[1])
}
const paperState = () => state(paper, 'nbtest', 'NBSTATE'), proxyState = () => state(proxy, 'banbridge', 'BRSTATE')
async function waitProxy(predicate, label) { let current; await until(async () => { current = await proxyState(); return predicate(current) }, label); return current }
async function waitPaper(predicate, label) { let current; await until(async () => { current = await paperState(); return predicate(current) }, label); return current }
function connect(name, port = 25635) {
  const bot = mineflayer.createBot({ host: '127.0.0.1', port, username: name, version: '26.2', auth: 'offline', hideErrors: true, checkTimeoutInterval: 30000 })
  const client = { name, bot, ended: false, messages: [], kicked: '', errors: [] }; clients.push(client)
  bot.on('end', () => { client.ended = true }); bot.on('messagestr', m => client.messages.push(m))
  bot.on('kicked', reason => { client.kicked = JSON.stringify(reason) }); bot.on('error', e => client.errors.push(String(e)))
  return client
}
async function disconnect(c) { if (!c.ended) c.bot.quit(); await until(() => c.ended, 'disconnect ' + c.name); await sleep(150) }
async function startPaper() {
  paper = start('paper', 'server.jar', '2G')
  await until(() => /Done \(/.test(paper.output) || paper.exited, 'local Paper ready', 90000)
  assert(!paper.exited, paper.output.slice(-5000)); assert.match(paper.output, /NordBans 1.1.0 enabled/)
  assert.match(paper.output, /LOCAL_NORDBANS_PROBE_READY/)
}
async function stopPaper() { if (!paper.exited) { command(paper, 'stop'); await until(() => paper.exited, 'Paper graceful stop', 30000) } }
const banFile = path.join(root, 'paper', 'plugins', 'NordBans', 'bans.properties')
function fixture() {
  const p = path.join(root, 'paper', 'plugins', 'NordBans'), q = path.join(root, 'proxy', 'plugins', 'nordqueue')
  fs.mkdirSync(p, { recursive: true }); fs.mkdirSync(q, { recursive: true })
  fs.writeFileSync(path.join(p, 'config.yml'), `maximum-duration-days: 365\nstorage:\n  maximum-pending-operations: 2\n  maximum-queue-wait-millis: 10000\nsync:\n  messages-per-tick: 3\n`)
  let records = '# LOCAL SYNTHETIC LEGACY FORMAT\n'
  const encode = value => Buffer.from(value).toString('base64url')
  for (let i = 0; i < 18; i++) records += `seed${i}=${Date.now() + 3600000}|${encode('Seed' + i)}|${encode('Local seed')}|${encode('Console')}\n`
  fs.writeFileSync(banFile, records)
  fs.writeFileSync(path.join(root, 'paper', 'server.properties'), `server-ip=127.0.0.1\nserver-port=25636\nonline-mode=false\nenforce-secure-profile=false\nmax-players=20\nview-distance=2\nsimulation-distance=2\nlevel-name=NordBansLocalTest\nspawn-protection=0\n`)
  fs.writeFileSync(path.join(root, 'proxy', 'velocity.toml'), `config-version = "2.9"
bind = "127.0.0.1:25635"
motd = "LOCAL_BANS_TEST"
show-max-players = 1000
online-mode = false
force-key-authentication = false
player-info-forwarding-mode = "none"
forwarding-secret-file = "unused-local-secret"
[servers]
main = "127.0.0.1:25636"
queue = "127.0.0.1:25637"
try = ["queue"]
[forced-hosts]
"local.invalid" = ["queue"]
[advanced]
login-ratelimit = 0
connection-timeout = 2000
read-timeout = 10000
failover-on-unexpected-server-disconnect = true
`)
  fs.writeFileSync(path.join(q, 'config.properties'), 'main-capacity=2\nminimum-wait-seconds=0\ntransfer-interval-seconds=1\nfailed-retry-seconds=5\nconnection-attempt-timeout-seconds=30\n')
  // Reuse only the complete generated LOCAL fixture YAML, never production settings/secrets.
  const limbo = fs.readFileSync('C:\\Users\\artyo\\Documents\\Codex\\nordqueuetab-test-20261003\\queue\\settings.yml', 'utf8')
  assert(limbo.includes('127.0.0.1')); assert(limbo.includes('unused-local-test'))
  fs.writeFileSync(path.join(root, 'queue', 'settings.yml'), limbo.replace('25627', '25637'))
}
function payload(action, name, expires, reason) {
  const utf = value => { const bytes = Buffer.from(value); const length = Buffer.alloc(2); length.writeUInt16BE(bytes.length); return Buffer.concat([length, bytes]) }
  const date = Buffer.alloc(8); date.writeBigInt64BE(BigInt(expires)); return Buffer.concat([utf(action), utf(name), date, utf(reason)])
}
async function main() {
  for (const port of [25635, 25636, 25637]) assert(!await portReady(port), 'Test port occupied: ' + port)
  fixture(); start('queue', 'NanoLimbo.jar'); await startPaper(); proxy = start('proxy', 'velocity.jar')
  await until(() => /LOCAL_BAN_BRIDGE_READY/.test(proxy.output), 'local proxy ready')
  const keeper = connect('NBKeeper'); await waitProxy(s => s.main.includes(keeper.name), 'first carrier joins Paper')
  await waitProxy(s => s.banMessages === 18 && s.banned.length === 18, 'one complete legacy-ban baseline')
  const target = connect('NBTarget'); await waitProxy(s => s.main.includes(target.name), 'second carrier joins Paper')
  await sleep(1500); assert.equal((await proxyState()).banMessages, 18)
  pass('Legacy bans load and sync once; a second Paper join does not resend the full list')
  target.bot.chat('/tempban NBKeeper 5m unauthorized')
  // Paper can hide an unauthorized command instead of sending a permission-specific text.
  await until(() => /NBTarget issued server command: \/tempban NBKeeper 5m unauthorized/.test(paper.output), 'unauthorized command received')
  await sleep(500)
  assert(!(await paperState()).active.includes(keeper.name)); assert.equal((await proxyState()).banMessages, 18)
  pass('Unprivileged client cannot issue a ban')
  command(paper, 'tempban NBTarget 5m Local moderation')
  await waitPaper(s => s.active.includes(target.name), 'BAN durable in Paper')
  await waitProxy(s => s.banned.includes(target.name) && s.limbo.includes(target.name) && !s.waiting.includes(target.name), 'real BAN redirects target into suspended limbo')
  pass('Async durable BAN reaches NordQueue 1.1.2 and suspends the real client outside the queue')
  const direct = connect('NBTarget', 25636)
  await until(() => direct.ended, 'direct banned login denied'); assert.match(direct.kicked, /NORD_BAN_V1/)
  pass('Paper pre-login enforces committed ban without any disk read')
  target.bot._client.write('custom_payload', { channel: 'nordfjell:bans', data: payload('UNBAN', target.name, Date.now() + 600000, 'Forged client') })
  await until(() => /Rejected NordBans sync from an untrusted source/.test(proxy.output), 'forged client payload rejected')
  assert((await proxyState()).banned.includes(target.name))
  pass('Forged client UNBAN is rejected by the unchanged trusted-main protocol boundary')
  command(paper, 'nbtest fault true'); await until(() => /NBFAULT true/.test(paper.output), 'storage fault enabled')
  const before = fs.readFileSync(banFile)
  command(paper, 'unban NBTarget'); await until(() => /Ban change failed before publication/.test(paper.output), 'failed unban returns error')
  await waitPaper(s => s.pending === 0, 'failed mutation callback drained')
  assert((await paperState()).active.includes(target.name)); assert((await proxyState()).banned.includes(target.name))
  assert.deepEqual(fs.readFileSync(banFile), before)
  command(paper, 'tempban NBFailed 5m Disk error'); await sleep(800)
  assert(!(await paperState()).active.includes('NBFailed')); assert.deepEqual(fs.readFileSync(banFile), before)
  pass('Injected storage errors neither lift an existing ban nor publish a new one')
  command(paper, 'nbtest fault false'); await until(() => /NBFAULT false/.test(paper.output), 'fault disabled')
  const ticks = (await paperState()).ticks
  command(paper, 'nbtest hold 1500'); await waitPaper(s => s.pending === 1, 'slow storage worker running')
  command(paper, 'tempban NBOverflowA 5m Accepted'); command(paper, 'tempban NBOverflowB 5m Rejected')
  await until(() => /Ban storage is busy/.test(paper.output), 'bounded queue refusal')
  await waitPaper(s => s.active.includes('NBOverflowA') && s.pending === 0, 'queued operation completes')
  const afterHold = await paperState(); assert(!afterHold.active.includes('NBOverflowB')); assert(afterHold.ticks - ticks >= 15)
  pass('Storage overload is bounded; Paper ticks continue during a deliberately slow worker')
  await disconnect(keeper); await disconnect(target)
  await waitProxy(s => !s.main.length && !s.limbo.length, 'all carriers leave')
  command(paper, 'unban NBTarget'); await waitPaper(s => !s.active.includes(target.name) && s.releases.includes(target.name), 'durable unban without carrier')
  assert((await proxyState()).banned.includes(target.name))
  await stopPaper(); await startPaper()
  const restartKeeper = connect('NBRestart'); await waitProxy(s => s.main.includes(restartKeeper.name) && !s.banned.includes(target.name), 'restart carrier delivers durable unban')
  assert((await proxyState()).unbanMessages >= 1)
  const released = connect(target.name); await waitProxy(s => s.main.includes(released.name), 'released target admitted normally')
  pass('UNBAN without a carrier survives Paper restart and releases the stale proxy ban on recovery')
  await disconnect(released); await disconnect(restartKeeper)
  await waitProxy(s => !s.main.length && !s.limbo.length, 'cleanup before expiry')
  command(paper, 'nbtest expire NBExpired 1'); await waitPaper(s => s.active.includes('NBExpired'), 'short local expiry inserted')
  await sleep(1500); assert(!(await paperState()).active.includes('NBExpired'))
  const expired = connect('NBExpired', 25636)
  await until(() => expired.bot.entity, 'expired account can join Paper'); await disconnect(expired)
  pass('Expired ban is not enforced while waiting for background cleanup')
  command(paper, 'nbtest disable'); await until(() => paper.exited, 'disabled security plugin stops Paper', 30000)
  assert.match(paper.output, /NordBans disabled on a running server; requesting safe shutdown/)
  pass('Disabling NordBans on a running test server requests a graceful Paper shutdown')
  fs.writeFileSync(banFile, 'broken=not-a-record\n')
  paper = start('paper', 'server.jar', '2G')
  await until(() => paper.exited, 'corrupt ban file fails closed', 60000)
  assert.match(paper.output, /Ban initialization failed/)
  assert(!/NordBans 1.1.0 enabled/.test(paper.output))
  assert(!await portReady(25636))
  pass('Corrupt ban file fails closed and shuts Paper down rather than silently ignoring bans')
  assert(!/NoSuchMethodError|ClassCastException|ConcurrentModificationException|Asynchronous.*exception/i.test(proxy.output))
  fs.writeFileSync(path.join(root, 'integration-results.json'), JSON.stringify({ total: results.length, passed: results,
    fixture: 'localhost:25635/25636/25637; Paper 26.2-127 + Velocity 4.2.1 + NordQueue 1.1.2 + NanoLimbo; synthetic users only; no production tests' }, null, 2))
  console.log('ALL ' + results.length + ' LOCAL NORDBANS INTEGRATION SCENARIOS PASSED')
}
main().catch(e => { console.error(e.stack); process.exitCode = 1 }).finally(async () => {
  for (const c of clients) if (!c.ended) c.bot.quit()
  if (paper && !paper.exited) await stopPaper().catch(() => paper.child.kill())
  if (proxy && !proxy.exited) { command(proxy, 'shutdown'); await until(() => proxy.exited, 'proxy shutdown', 15000).catch(() => proxy.child.kill()) }
  for (const handle of children) {
    if (!handle.exited) handle.child.kill()
    fs.writeFileSync(path.join(root, handle.name + '-' + handle.index + '-test-output.log'), handle.output)
  }
})
