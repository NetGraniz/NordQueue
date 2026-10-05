package com.nordfjell.nordqueue;

import com.google.inject.Inject;
import com.velocitypowered.api.command.CommandMeta;
import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.PostOrder;
import com.velocitypowered.api.event.connection.DisconnectEvent;
import com.velocitypowered.api.event.connection.PostLoginEvent;
import com.velocitypowered.api.event.connection.PluginMessageEvent;
import com.velocitypowered.api.event.player.KickedFromServerEvent;
import com.velocitypowered.api.event.player.ServerPostConnectEvent;
import com.velocitypowered.api.event.player.ServerPreConnectEvent;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.event.proxy.ProxyPingEvent;
import com.velocitypowered.api.event.proxy.ProxyShutdownEvent;
import com.velocitypowered.api.plugin.Plugin;
import com.velocitypowered.api.plugin.annotation.DataDirectory;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ConnectionRequestBuilder;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.messages.MinecraftChannelIdentifier;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import com.velocitypowered.api.proxy.ServerConnection;
import com.velocitypowered.api.proxy.server.ServerPing;
import com.velocitypowered.api.scheduler.ScheduledTask;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import net.kyori.adventure.title.Title;
import org.slf4j.Logger;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.HashSet;
import java.util.Locale;
import java.util.Optional;
import java.util.Properties;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

@Plugin(
        id = "nordqueue",
        name = "NordQueue",
        version = "1.1.3",
        description = "Lightweight queue and backend failover for Nord Fjell",
        authors = {"Nord Fjell"}
)
public final class NordQueuePlugin {
    private static final MinecraftChannelIdentifier BAN_CHANNEL =
            MinecraftChannelIdentifier.from(BanProtocol.CHANNEL);
    private final ProxyServer proxy;
    private final Logger logger;
    private final Path dataDirectory;
    private final MiniMessage miniMessage = MiniMessage.miniMessage();
    private final PlainTextComponentSerializer plainText = PlainTextComponentSerializer.plainText();
    private final QueueState<Player> queueState = new QueueState<>();
    private final Object taskLock = new Object();
    private final ConcurrentHashMap<QueueState.Attempt<Player>, ScheduledTask> attemptTimeouts = new ConcurrentHashMap<>();
    private volatile PriorityPlayers priorityPlayers = new PriorityPlayers(Set.of(), Set.of());
    private volatile long taskGeneration;
    private volatile boolean stopping;

    private record PriorityPlayers(Set<UUID> uuids, Set<String> names) { }

    private volatile Settings settings = Settings.defaults();
    private ScheduledTask displayTask;
    private ScheduledTask transferTask;
    private SuspendedBanStore suspendedBanStore;
    private BanInbox banInbox;
    private ScheduledExecutorService banWriter;
    private String reportedBanProblem = "";

    @Inject
    public NordQueuePlugin(ProxyServer proxy, Logger logger, @DataDirectory Path dataDirectory) {
        this.proxy = proxy;
        this.logger = logger;
        this.dataDirectory = dataDirectory;
    }

    @Subscribe
    public void onProxyInitialize(ProxyInitializeEvent event) {
        suspendedBanStore = new SuspendedBanStore(dataDirectory.resolve("suspended-bans.properties"));
        try {
            suspendedBanStore.load();
            banInbox = new BanInbox(suspendedBanStore,4096,256);
            banWriter = Executors.newSingleThreadScheduledExecutor(task -> {
                Thread thread=new Thread(task,"NordQueue-ban-storage");thread.setDaemon(true);return thread;
            });
            banWriter.scheduleWithFixedDelay(() -> banInbox.flush(System.nanoTime()),100,100,TimeUnit.MILLISECONDS);
        } catch (IOException exception) {
            logger.error("Ban storage failed strict initialization; main admission stays closed. Repair storage and restart the proxy. Error: {}",
                    exception.getClass().getSimpleName());
        }
        proxy.getChannelRegistrar().register(BAN_CHANNEL);
        reloadSettings();
        registerCommands();
        startTasks();
        logger.info("NordQueue enabled: {} -> {}", settings.queueServer, settings.mainServer);
    }

    @Subscribe
    public void onPostLogin(PostLoginEvent event) {
        queueState.begin(event.getPlayer().getUniqueId(), event.getPlayer());
        if (activeBan(event.getPlayer()).isEmpty()) enqueue(event.getPlayer());
    }

    @Subscribe
    public void onDisconnect(DisconnectEvent event) {
        synchronized (queueState) {
            QueueState.Attempt<Player> attempt = queueState.pending();
            if (queueState.end(event.getPlayer().getUniqueId(), event.getPlayer())
                    && attempt != null && attempt.session.owner == event.getPlayer()) cancelTimeout(attempt);
        }
    }

    @Subscribe
    public void onProxyShutdown(ProxyShutdownEvent event) {
        synchronized (taskLock) {
            stopping = true;
            taskGeneration++;
            if (displayTask != null) displayTask.cancel();
            if (transferTask != null) transferTask.cancel();
            queueState.close();
            attemptTimeouts.values().forEach(ScheduledTask::cancel);
            attemptTimeouts.clear();
            if(banInbox!=null)banInbox.close();
            if(banWriter!=null)banWriter.shutdownNow();
        }
    }

    @Subscribe
    public void onPluginMessage(PluginMessageEvent event) {
        if (!event.getIdentifier().equals(BAN_CHANNEL)) return;
        event.setResult(PluginMessageEvent.ForwardResult.handled());
        if (!(event.getSource() instanceof ServerConnection connection)
                || !connection.getServerInfo().getName().equalsIgnoreCase(settings.mainServer)) {
            logger.warn("Rejected NordBans sync from an untrusted source");
            return;
        }
        BanProtocol.decodeSync(event.getData()).ifPresentOrElse(message -> {
            if (message.action().equals("BAN")) applyBan(message.ban());
            else removeBan(message.ban().playerName());
        }, () -> logger.warn("Rejected malformed NordBans sync payload"));
    }

    @Subscribe
    public void onKickedFromServer(KickedFromServerEvent event) {
        if (!event.getServer().getServerInfo().getName().equalsIgnoreCase(settings.mainServer)) return;
        if (!isCurrent(event.getPlayer())) return;
        String reason = event.getServerKickReason().map(plainText::serialize).orElse("");
        Optional<SuspendedBan> protocolBan = BanProtocol.decodeKick(event.getPlayer().getUsername(), reason);
        if (protocolBan.isPresent()) {
            SuspendedBan ban = protocolBan.get();
            applyBan(ban);
            Optional<RegisteredServer> queueServer = proxy.getServer(settings.queueServer);
            Component message = banMessage(ban);
            if (queueServer.isEmpty()) {
                event.setResult(KickedFromServerEvent.DisconnectPlayer.create(message));
            } else if (isOnServer(event.getPlayer(), settings.queueServer)) {
                event.setResult(KickedFromServerEvent.Notify.create(message));
            } else {
                event.setResult(KickedFromServerEvent.RedirectPlayer.create(queueServer.get(), message));
            }
            return;
        }
        if (AuthenticationDisconnects.terminal(reason)) {
            remove(event.getPlayer());
            Component message = event.getServerKickReason().orElseGet(() ->
                    component("<red>Authentication timed out. Reconnect and use /login before chatting.</red>"));
            event.setResult(KickedFromServerEvent.DisconnectPlayer.create(message));
            return;
        }
        // Ordinary backend failures return existing players to limbo. A failed transfer
        // from limbo stays there and the connection future applies the retry backoff.
        proxy.getServer(settings.queueServer).ifPresent(queue -> {
            Component message = event.getServerKickReason().orElse(Component.text("Main server is unavailable."));
            if (isOnServer(event.getPlayer(), settings.queueServer)) {
                event.setResult(KickedFromServerEvent.Notify.create(message));
            } else {
                event.setResult(KickedFromServerEvent.RedirectPlayer.create(queue, message));
            }
        });
    }

    @Subscribe
    public void onServerPostConnect(ServerPostConnectEvent event) {
        Player player = event.getPlayer();
        if (!isCurrent(player)) return;
        String current = currentServerName(player).orElse("");
        if (current.equalsIgnoreCase(settings.queueServer)) {
            Optional<SuspendedBan> ban = activeBan(player);
            if (ban.isPresent()) {
                remove(player);
                showBanNotice(player, ban.get());
            } else enqueue(player);
        } else if (current.equalsIgnoreCase(settings.mainServer)) {
            Optional<SuspendedBan> ban = activeBan(player);
            if (ban.isPresent() || !banAdmissionReady()) {
                remove(player);
                proxy.getServer(settings.queueServer).ifPresentOrElse(
                    queue -> player.createConnectionRequest(queue).fireAndForget(),
                    () -> player.disconnect(ban.map(this::banMessage).orElse(Component.text("Ban checks are temporarily unavailable."))));
                return;
            }
            QueueState.Attempt<Player> completed = queueState.arrived(session(player));
            if (completed != null) cancelTimeout(completed);
            player.clearTitle();
        }
    }

    @Subscribe(order = PostOrder.LAST)
    public void onServerPreConnect(ServerPreConnectEvent event) {
        Optional<RegisteredServer> destination = event.getResult().getServer();
        if (destination.isEmpty() || !destination.get().getServerInfo().getName().equalsIgnoreCase(settings.mainServer)) return;
        if (!isCurrent(event.getPlayer())) {
            event.setResult(ServerPreConnectEvent.ServerResult.denied());
            return;
        }
        if (!banAdmissionReady()) {
            event.setResult(ServerPreConnectEvent.ServerResult.denied());
            event.getPlayer().sendActionBar(Component.text("Ban checks are temporarily synchronizing. Please wait."));
            return;
        }
        Optional<SuspendedBan> ban = activeBan(event.getPlayer());
        if (ban.isPresent()) {
            event.setResult(ServerPreConnectEvent.ServerResult.denied());
            showBanNotice(event.getPlayer(), ban.get());
            return;
        }
        if (!isOnServer(event.getPlayer(), settings.mainServer)
                && !queueState.consumeAdmission(session(event.getPlayer()), settings.mainServer)) {
            event.setResult(ServerPreConnectEvent.ServerResult.denied());
            event.getPlayer().sendActionBar(component(settings.waitMessage));
        }
    }

    @Subscribe
    public void onProxyPing(ProxyPingEvent event) {
        int inGame = proxy.getServer(settings.mainServer)
                .map(server -> server.getPlayersConnected().size()).orElse(0);
        QueueSnapshot snapshot = snapshot();
        int regular = snapshot.regularSize();
        int priority = snapshot.prioritySize();
        int total = inGame + regular + priority;
        List<ServerPing.SamplePlayer> sample = List.of(
                sample("§6In-game: §f" + inGame, "nordfjell-ingame"),
                sample("§6Queue: §f" + regular, "nordfjell-queue"),
                sample("§6Priority queue: §f" + priority, "nordfjell-priority"),
                sample("§7... total " + total + " ...", "nordfjell-total")
        );
        event.setPing(event.getPing().asBuilder()
                .onlinePlayers(total)
                .maximumPlayers(1)
                .samplePlayers(sample)
                .build());
    }

    private static ServerPing.SamplePlayer sample(String text, String id) {
        return new ServerPing.SamplePlayer(text,
                UUID.nameUUIDFromBytes(id.getBytes(StandardCharsets.UTF_8)));
    }

    public boolean isQueued(UUID uuid) {
        return queueState.contains(uuid);
    }

    public boolean isPriorityQueued(UUID uuid) {
        return queueState.priority(uuid);
    }

    public int position(UUID uuid) {
        return snapshot().position(uuid);
    }

    public int queueSize() {
        return queueState.size();
    }

    public int priorityQueueSize() {
        return queueState.prioritySize();
    }

    public int regularQueueSize() {
        return queueState.regularSize();
    }

    public List<UUID> queuedPlayers() {
        return snapshot().orderedIds();
    }

    public QueueSnapshot snapshot() { return queueState.snapshot(); }

    public long estimatedSeconds(UUID uuid) {
        QueueSnapshot snapshot = snapshot();
        int position = snapshot.absolutePosition(uuid);
        if (position <= 0) return 0L;
        long playersAhead = Math.max(0L, position - 1L);
        return playersAhead * settings.transferIntervalSeconds;
    }

    public String queueServerName() {
        return settings.queueServer;
    }

    private void enqueue(Player player) {
        if (activeBan(player).isPresent()) return;
        queueState.enqueue(session(player), isPriorityPlayer(player), nowMillis());
    }

    private void enqueueRegular(Player player) {
        if (activeBan(player).isPresent()) return;
        queueState.enqueue(session(player), false, nowMillis());
    }

    private boolean isPriorityPlayer(Player player) {
        PriorityPlayers priorities = priorityPlayers;
        return priorities.uuids.contains(player.getUniqueId())
                || priorities.names.contains(player.getUsername().toLowerCase(Locale.ROOT));
    }

    private void remove(Player player) { queueState.removeQueued(session(player)); }
    private QueueState.Session<Player> session(Player player) {
        return queueState.find(player.getUniqueId(), player);
    }
    private boolean isCurrent(Player player) { return player.isActive() && session(player) != null; }
    private static long nowMillis() { return TimeUnit.NANOSECONDS.toMillis(System.nanoTime()); }

    private void startTasks() {
        synchronized (taskLock) {
            if (stopping) return;
            long generation = ++taskGeneration;
            if (displayTask != null) displayTask.cancel();
            if (transferTask != null) transferTask.cancel();
            displayTask = proxy.getScheduler().buildTask(this, () -> {
                        if (!stopping && generation == taskGeneration) updateTitles();
                    })
                    .delay(500, TimeUnit.MILLISECONDS)
                    .repeat(settings.displayIntervalMillis, TimeUnit.MILLISECONDS)
                    .schedule();
            transferTask = proxy.getScheduler().buildTask(this, () -> tryTransfer(generation))
                    .delay(1, TimeUnit.SECONDS)
                    .repeat(settings.transferIntervalSeconds, TimeUnit.SECONDS)
                    .schedule();
        }
    }

    private void updateTitles() {
        Settings config = settings;
        releaseExpiredBans();
        for (Player player : proxy.getAllPlayers()) {
            if (!isCurrent(player) || !isOnServer(player, config.queueServer)) continue;
            activeBan(player).ifPresent(ban -> showBanTitle(player, ban));
        }
        QueueSnapshot snapshot = snapshot();
        for (UUID uuid : snapshot.orderedIds()) {
            proxy.getPlayer(uuid).ifPresent(player -> {
                int position = snapshot.position(uuid);
                if (position <= 0 || !isCurrent(player) || !isQueued(uuid) || !isOnServer(player, config.queueServer)) return;
                boolean first = position == 1;
                boolean priority = snapshot.isPriority(uuid);
                String titleTemplate = first
                        ? (priority ? config.priorityFirstTitle : config.firstTitle)
                        : (priority ? config.priorityPositionTitle : config.positionTitle);
                String subtitleTemplate = priority ? config.prioritySubtitle
                        : (first ? config.firstSubtitle : config.positionSubtitle);
                Component title = miniMessage.deserialize(titleTemplate,
                        Placeholder.unparsed("position", Integer.toString(position)),
                        Placeholder.unparsed("size", Integer.toString(snapshot.size())),
                        Placeholder.unparsed("regular_size", Integer.toString(snapshot.regularSize())),
                        Placeholder.unparsed("priority_size", Integer.toString(snapshot.prioritySize())));
                Component subtitle = miniMessage.deserialize(subtitleTemplate,
                        Placeholder.unparsed("position", Integer.toString(position)),
                        Placeholder.unparsed("size", Integer.toString(snapshot.size())),
                        Placeholder.unparsed("regular_size", Integer.toString(snapshot.regularSize())),
                        Placeholder.unparsed("priority_size", Integer.toString(snapshot.prioritySize())));
                player.showTitle(Title.title(title, subtitle, Title.Times.times(
                        Duration.ZERO, Duration.ofMillis(config.titleStayMillis), Duration.ZERO)));
            });
        }
    }

    private void tryTransfer(long generation) {
        QueueState.Attempt<Player> attempt;
        RegisteredServer main;
        Settings config;
        synchronized (taskLock) {
            if (stopping || generation != taskGeneration) return;
            if (!banAdmissionReady()) return;
            config = settings;
            Optional<RegisteredServer> target = proxy.getServer(config.mainServer);
            if (target.isEmpty()) return;
            main = target.get();
            queueState.prune(Player::isActive);
            attempt = queueState.select(() -> main.getPlayersConnected().size(), config.mainCapacity,
                    nowMillis(), TimeUnit.SECONDS.toMillis(config.minimumWaitSeconds),
                    TimeUnit.SECONDS.toMillis(config.transferIntervalSeconds), config.mainServer,
                    player -> isOnServer(player, config.queueServer) && activeBan(player).isEmpty());
        }
        if (attempt == null) return;
        Player player = attempt.session.owner;
        synchronized (taskLock) {
        if (stopping || !queueState.relevant(attempt)) {
            queueState.finish(attempt, false, nowMillis(), 0);
            return;
        }
        try {
            ScheduledTask timeout = proxy.getScheduler().buildTask(this, () -> {
                synchronized (taskLock) {
                    if (queueState.expire(attempt)) {
                        logger.warn("Backend connection attempt timed out for {}; disconnecting the old session", player.getUsername());
                        player.disconnect(Component.text("Connection to the main server timed out. Please reconnect."));
                    }
                    cancelTimeout(attempt);
                }
            }).delay(config.connectionAttemptTimeoutSeconds, TimeUnit.SECONDS).schedule();
            attemptTimeouts.put(attempt, timeout);
            player.sendActionBar(component(config.connectingMessage));
            player.createConnectionRequest(main).connect().whenComplete((result, throwable) -> {
                if (throwable == null && result != null
                        && result.getStatus() == ConnectionRequestBuilder.Status.CONNECTION_CANCELLED
                        && queueState.admissionConsumed(attempt) && queueState.relevant(attempt)) {
                    // Another request for this same player may have consumed the admission.
                    // Keep its reservation until arrival/disconnect/watchdog, not until this cancellation.
                    return;
                }
                if (queueState.finish(attempt, throwable == null && result != null && result.isSuccessful(),
                        nowMillis(), TimeUnit.SECONDS.toMillis(config.failedRetrySeconds))) cancelTimeout(attempt);
            });
        } catch (RuntimeException exception) {
            queueState.finish(attempt, false, nowMillis(), TimeUnit.SECONDS.toMillis(config.failedRetrySeconds));
            cancelTimeout(attempt);
            logger.warn("Could not start a queued backend connection", exception);
        }
        }
    }

    private void cancelTimeout(QueueState.Attempt<Player> attempt) {
        ScheduledTask task = attemptTimeouts.remove(attempt);
        if (task != null) task.cancel();
    }

    private boolean isOnServer(Player player, String name) {
        return currentServerName(player).map(server -> server.equalsIgnoreCase(name)).orElse(false);
    }

    private Optional<String> currentServerName(Player player) {
        return player.getCurrentServer().map(connection -> connection.getServerInfo().getName());
    }

    private Component component(String template) {
        return miniMessage.deserialize(template);
    }

    private Optional<SuspendedBan> activeBan(Player player) {
        return banInbox == null ? Optional.empty() : banInbox.active(player.getUsername());
    }

    private boolean banAdmissionReady(){return banInbox!=null && banInbox.ready();}

    private void applyBan(SuspendedBan ban) {
        if (!ban.active(System.currentTimeMillis())) {
            removeBan(ban.playerName());
            return;
        }
        if(banInbox==null || !banInbox.offer(new BanInbox.Change(ban.playerName(),ban))) {
            logger.error("Ban inbox unavailable/overloaded; main admission remains closed. Restart and resynchronize before admitting players.");
            return;
        }
        proxy.getPlayer(ban.playerName()).ifPresent(player -> {
            remove(player);
            queueState.clearBanNotice(session(player));
            if (isOnServer(player, settings.queueServer)) showBanNotice(player, ban);
        });
    }

    private void removeBan(String playerName) {
        if(banInbox==null || !banInbox.offer(new BanInbox.Change(playerName,null)))
            logger.error("Unban inbox unavailable/overloaded; main admission remains closed. Restart and resynchronize before admitting players.");
    }

    private void releaseExpiredBans() {
        String problem=banInbox==null ? "initialization-failed" : banInbox.problem();
        if(!problem.equals(reportedBanProblem)) {
            if(problem.isEmpty())logger.info("Ban storage recovered; pending synchronization must finish before main admission.");
            else logger.error("Ban storage unavailable ({}); main admission is closed. I/O failures retry every five seconds; overflow requires restart/resynchronization.",problem);
            reportedBanProblem=problem;
        }
        if(banInbox==null)return;
        for(Player player:proxy.getAllPlayers()) {
            if(!isCurrent(player) || activeBan(player).isPresent() || !queueState.hasBanNotice(session(player)))continue;
            // Use the exact active connection, not a name lookup that can target a replacement session.
            synchronized(queueState) {
                if(!isCurrent(player) || activeBan(player).isPresent())continue;
                queueState.clearBanNotice(session(player));
                player.clearTitle();
                if (isOnServer(player, settings.queueServer)) {
                    player.sendMessage(component(settings.banExpiredMessage));
                    enqueueRegular(player);
                }
            }
        }
    }

    private void showBanNotice(Player player, SuspendedBan ban) {
        if (!isCurrent(player)) return;
        remove(player);
        showBanTitle(player, ban);
        if (queueState.markBanNotice(session(player))) player.sendMessage(banMessage(ban));
    }

    private void showBanTitle(Player player, SuspendedBan ban) {
        String remaining = formatRemaining(ban.remainingMillis(System.currentTimeMillis()));
        Component title = miniMessage.deserialize(settings.banTitle,
                Placeholder.unparsed("reason", ban.reason()),
                Placeholder.unparsed("remaining", remaining));
        Component subtitle = miniMessage.deserialize(settings.banSubtitle,
                Placeholder.unparsed("reason", ban.reason()),
                Placeholder.unparsed("remaining", remaining));
        player.showTitle(Title.title(title, subtitle, Title.Times.times(
                Duration.ZERO, Duration.ofMillis(settings.titleStayMillis), Duration.ZERO)));
    }

    private Component banMessage(SuspendedBan ban) {
        return miniMessage.deserialize(settings.banMessage,
                Placeholder.unparsed("reason", ban.reason()),
                Placeholder.unparsed("remaining", formatRemaining(ban.remainingMillis(System.currentTimeMillis()))));
    }

    private static String formatRemaining(long remainingMillis) {
        long minutes = Math.max(1L, (remainingMillis + 59_999L) / 60_000L);
        long days = minutes / (24L * 60L);
        minutes %= 24L * 60L;
        long hours = minutes / 60L;
        minutes %= 60L;
        List<String> parts = new ArrayList<>(3);
        if (days > 0) parts.add(days + "d");
        if (hours > 0) parts.add(hours + "h");
        if (minutes > 0 || parts.isEmpty()) parts.add(minutes + "m");
        return String.join(" ", parts);
    }

    private void registerCommands() {
        CommandMeta meta = proxy.getCommandManager().metaBuilder("queue")
                .aliases("qposition")
                .plugin(this)
                .build();
        proxy.getCommandManager().register(meta, new QueueCommand());

        CommandMeta adminMeta = proxy.getCommandManager().metaBuilder("nordqueue")
                .plugin(this)
                .build();
        proxy.getCommandManager().register(adminMeta, new AdminCommand());
    }

    private void reloadSettings() {
        try {
            Files.createDirectories(dataDirectory);
            Path config = dataDirectory.resolve("config.properties");
            Properties properties = new Properties();
            if (Files.exists(config)) {
                try (InputStream input = Files.newInputStream(config)) {
                    properties.load(input);
                }
            }
            Settings defaults = Settings.defaults();
            putDefault(properties, "queue-server", defaults.queueServer);
            putDefault(properties, "main-server", defaults.mainServer);
            putDefault(properties, "main-capacity", Integer.toString(defaults.mainCapacity));
            putDefault(properties, "minimum-wait-seconds", Long.toString(defaults.minimumWaitSeconds));
            putDefault(properties, "transfer-interval-seconds", Long.toString(defaults.transferIntervalSeconds));
            putDefault(properties, "failed-retry-seconds", Long.toString(defaults.failedRetrySeconds));
            putDefault(properties, "connection-attempt-timeout-seconds", Long.toString(defaults.connectionAttemptTimeoutSeconds));
            putDefault(properties, "display-interval-millis", Long.toString(defaults.displayIntervalMillis));
            putDefault(properties, "title-stay-millis", Long.toString(defaults.titleStayMillis));
            putDefault(properties, "first-title", defaults.firstTitle);
            putDefault(properties, "first-subtitle", defaults.firstSubtitle);
            putDefault(properties, "priority-first-title", defaults.priorityFirstTitle);
            putDefault(properties, "priority-position-title", defaults.priorityPositionTitle);
            putDefault(properties, "priority-subtitle", defaults.prioritySubtitle);
            putDefault(properties, "position-title", defaults.positionTitle);
            putDefault(properties, "position-subtitle", defaults.positionSubtitle);
            putDefault(properties, "connecting-message", defaults.connectingMessage);
            putDefault(properties, "wait-message", defaults.waitMessage);
            putDefault(properties, "ban-title", defaults.banTitle);
            putDefault(properties, "ban-subtitle", defaults.banSubtitle);
            putDefault(properties, "ban-message", defaults.banMessage);
            putDefault(properties, "ban-expired-message", defaults.banExpiredMessage);
            try (OutputStream output = Files.newOutputStream(config)) {
                properties.store(output, "NordQueue configuration. MiniMessage formatting is supported.");
            }
            Settings loaded = Settings.from(properties);
            if (loaded.mainServer.isEmpty() || loaded.queueServer.isEmpty()
                    || loaded.mainServer.equalsIgnoreCase(loaded.queueServer)) {
                throw new IllegalArgumentException("Main and queue must name distinct servers");
            }
            if ((!loaded.mainServer.equalsIgnoreCase(settings.mainServer)
                    || !loaded.queueServer.equalsIgnoreCase(settings.queueServer)) && !proxy.getAllPlayers().isEmpty()) {
                throw new IllegalArgumentException("Changing server names with connected players requires a proxy restart");
            }
            PriorityPlayers priorities = loadPriorityPlayers();
            synchronized (taskLock) {
                settings = loaded;
                priorityPlayers = priorities;
                queueState.rebalance(this::isPriorityPlayer);
            }
        } catch (IOException | RuntimeException exception) {
            logger.error("Could not load NordQueue configuration; keeping previous settings", exception);
        }
    }

    private PriorityPlayers loadPriorityPlayers() throws IOException {
        Path file = dataDirectory.resolve("priority-players.txt");
        if (!Files.exists(file)) {
            Files.writeString(file,
                    "# NordQueue priority players\n"
                            + "# One Minecraft username or UUID per line. Lines starting with # are ignored.\n",
                    StandardCharsets.UTF_8);
        }
        Set<UUID> uuids = new HashSet<>();
        Set<String> names = new HashSet<>();
        for (String sourceLine : Files.readAllLines(file, StandardCharsets.UTF_8)) {
            String line = sourceLine.trim();
            if (line.isEmpty() || line.startsWith("#")) continue;
            try { uuids.add(UUID.fromString(line)); }
            catch (IllegalArgumentException ignored) { names.add(line.toLowerCase(Locale.ROOT)); }
        }
        logger.info("Loaded {} priority player entries", uuids.size() + names.size());
        return new PriorityPlayers(Set.copyOf(uuids), Set.copyOf(names));
    }

    private static void putDefault(Properties properties, String key, String value) {
        properties.putIfAbsent(key, value);
    }

    private final class QueueCommand implements SimpleCommand {
        @Override
        public void execute(Invocation invocation) {
            CommandSource source = invocation.source();
            if (!(source instanceof Player player)) {
                source.sendPlainMessage("This command is only available to players.");
                return;
            }
            int position = position(player.getUniqueId());
            Optional<SuspendedBan> ban = activeBan(player);
            if (ban.isPresent()) {
                source.sendMessage(banMessage(ban.get()));
                return;
            }
            if (position <= 0) {
                source.sendRichMessage("<green>You are connected to the main server.</green>");
                return;
            }
            String queueName = isPriorityQueued(player.getUniqueId()) ? "priority queue" : "queue";
            source.sendRichMessage("<gold>Position in " + queueName + ": <yellow>" + position
                    + "</yellow></gold> <gray>(regular: " + regularQueueSize()
                    + ", priority: " + priorityQueueSize() + ")</gray>");
        }
    }

    private final class AdminCommand implements SimpleCommand {
        @Override
        public boolean hasPermission(Invocation invocation) {
            return invocation.source().hasPermission("nordqueue.admin");
        }

        @Override
        public void execute(Invocation invocation) {
            String[] arguments = invocation.arguments();
            if (arguments.length == 1 && arguments[0].equalsIgnoreCase("reload")) {
                reloadSettings();
                startTasks();
                invocation.source().sendRichMessage("<green>NordQueue reloaded.</green>");
                return;
            }
            invocation.source().sendRichMessage("<gold>NordQueue:</gold> <yellow>" + regularQueueSize()
                    + "</yellow> regular, <yellow>" + priorityQueueSize()
                    + "</yellow> priority. <gray>Use /nordqueue reload</gray>");
        }
    }

    private record Settings(
            String queueServer,
            String mainServer,
            int mainCapacity,
            long minimumWaitSeconds,
            long transferIntervalSeconds,
            long failedRetrySeconds,
            long connectionAttemptTimeoutSeconds,
            long displayIntervalMillis,
            long titleStayMillis,
            String firstTitle,
            String firstSubtitle,
            String priorityFirstTitle,
            String priorityPositionTitle,
            String prioritySubtitle,
            String positionTitle,
            String positionSubtitle,
            String connectingMessage,
            String waitMessage,
            String banTitle,
            String banSubtitle,
            String banMessage,
            String banExpiredMessage
    ) {
        private static Settings defaults() {
            return new Settings(
                    "queue", "main", 100, 5L, 1L, 5L, 30L, 1000L, 2200L,
                    "<gold>You are first in queue</gold>",
                    "<yellow>Connecting...</yellow>",
                    "<gold>You are first in priority queue</gold>",
                    "<gold>Position in priority queue: <position></gold>",
                    "<yellow>Priority players waiting: <priority_size></yellow>",
                    "<gold>Position in queue: <position></gold>",
                    "<gray>Players waiting: <size></gray>",
                    "<yellow>Connecting to the server...</yellow>",
                    "<gold>Please wait for your turn.</gold>",
                    "<red>Temporarily banned</red>",
                    "<gray><reason> • <remaining> remaining</gray>",
                    "<red>You are temporarily banned.</red><newline><gray>Reason: <white><reason></white></gray><newline><gray>Remaining: <white><remaining></white></gray>",
                    "<green>Your temporary ban has expired. You joined the end of the queue.</green>"
            );
        }

        private static Settings from(Properties p) {
            Settings d = defaults();
            return new Settings(
                    p.getProperty("queue-server", d.queueServer).trim(),
                    p.getProperty("main-server", d.mainServer).trim(),
                    positiveInt(p, "main-capacity", d.mainCapacity),
                    nonNegativeLong(p, "minimum-wait-seconds", d.minimumWaitSeconds),
                    positiveLong(p, "transfer-interval-seconds", d.transferIntervalSeconds),
                    positiveLong(p, "failed-retry-seconds", d.failedRetrySeconds),
                    Math.min(300L, positiveLong(p, "connection-attempt-timeout-seconds", d.connectionAttemptTimeoutSeconds)),
                    Math.max(250L, positiveLong(p, "display-interval-millis", d.displayIntervalMillis)),
                    Math.max(1000L, positiveLong(p, "title-stay-millis", d.titleStayMillis)),
                    p.getProperty("first-title", d.firstTitle),
                    p.getProperty("first-subtitle", d.firstSubtitle),
                    p.getProperty("priority-first-title", d.priorityFirstTitle),
                    p.getProperty("priority-position-title", d.priorityPositionTitle),
                    p.getProperty("priority-subtitle", d.prioritySubtitle),
                    p.getProperty("position-title", d.positionTitle),
                    p.getProperty("position-subtitle", d.positionSubtitle),
                    p.getProperty("connecting-message", d.connectingMessage),
                    p.getProperty("wait-message", d.waitMessage),
                    p.getProperty("ban-title", d.banTitle),
                    p.getProperty("ban-subtitle", d.banSubtitle),
                    p.getProperty("ban-message", d.banMessage),
                    p.getProperty("ban-expired-message", d.banExpiredMessage)
            );
        }

        private static int positiveInt(Properties p, String key, int fallback) {
            try { return Math.max(1, Integer.parseInt(p.getProperty(key))); }
            catch (RuntimeException ignored) { return fallback; }
        }

        private static long positiveLong(Properties p, String key, long fallback) {
            try { return Math.max(1L, Long.parseLong(p.getProperty(key))); }
            catch (RuntimeException ignored) { return fallback; }
        }

        private static long nonNegativeLong(Properties p, String key, long fallback) {
            try { return Math.max(0L, Long.parseLong(p.getProperty(key))); }
            catch (RuntimeException ignored) { return fallback; }
        }
    }
}
