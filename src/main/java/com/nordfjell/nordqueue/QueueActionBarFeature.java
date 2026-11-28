package com.nordfjell.nordqueue;

import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.scheduler.ScheduledTask;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.slf4j.Logger;

import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

final class QueueActionBarFeature {
    private final NordQueuePlugin owner;
    private final ProxyServer proxy;
    private final Logger logger;
    private final Path config;
    private final Object lock = new Object();
    private final MiniMessage mini = MiniMessage.miniMessage();
    private ActionBarSettings settings;
    private ScheduledTask task;
    private long startedAt;
    private boolean stopped;

    QueueActionBarFeature(NordQueuePlugin owner, ProxyServer proxy, Logger logger, Path config) {
        this.owner = owner;
        this.proxy = proxy;
        this.logger = logger;
        this.config = config;
    }

    void start() {
        synchronized (lock) {
            reloadSettings(true);
            task = proxy.getScheduler().buildTask(owner, this::tick)
                .delay(1, TimeUnit.SECONDS).repeat(1, TimeUnit.SECONDS).schedule();
        }
    }

    boolean reload() {
        synchronized (lock) {
            return !stopped && reloadSettings(false);
        }
    }

    private boolean reloadSettings(boolean createMissing) {
        try {
            ActionBarSettings next = ActionBarSettings.load(config, createMissing);
            settings = next;
            startedAt = System.nanoTime();
            return true;
        } catch (Exception error) {
            logger.error("Could not load rotating Action Bar configuration; keeping previous settings", error);
            return false;
        }
    }

    private void tick() {
        ActionBarSettings active;
        long epoch;
        synchronized (lock) {
            if (stopped || settings == null) return;
            active = settings;
            epoch = startedAt;
        }
        QueueSnapshot snapshot = owner.snapshot();
        long elapsed = Math.max(0L, System.nanoTime() - epoch);
        long period = TimeUnit.SECONDS.toNanos(active.intervalSeconds());
        int index = (int) ((elapsed / period) % active.messages().size());
        String template = active.messages().get(index);
        String skippedLastHour = Integer.toString(owner.skippedLastHour());
        String priorityEstimate = QueueEstimate.format(snapshot.prioritySize());
        String regularEstimate = QueueEstimate.format(snapshot.size());
        for (Player player : proxy.getAllPlayers()) {
            int position = snapshot.absolutePosition(player.getUniqueId());
            if (position <= 0 || !player.isActive() || player.getCurrentServer()
                    .map(connection -> !connection.getServerInfo().getName().equalsIgnoreCase(owner.queueServerName()))
                    .orElse(true)) continue;
            player.sendActionBar(mini.deserialize(template,
                Placeholder.unparsed("position", Integer.toString(position)),
                Placeholder.unparsed("size", Integer.toString(snapshot.size())),
                Placeholder.unparsed("regular_size", Integer.toString(snapshot.regularSize())),
                Placeholder.unparsed("priority_size", Integer.toString(snapshot.prioritySize())),
                Placeholder.unparsed("estimate", QueueEstimate.format(position)),
                Placeholder.unparsed("priority_estimate", priorityEstimate),
                Placeholder.unparsed("regular_estimate", regularEstimate),
                Placeholder.unparsed("skipped_last_hour", skippedLastHour)));
        }
    }

    String health() {
        synchronized (lock) {
            return settings != null && !stopped
                ? "actionbar=ready, messages=" + settings.messages().size()
                : "actionbar=blocked";
        }
    }

    void stop() {
        synchronized (lock) {
            stopped = true;
            if (task != null) task.cancel();
        }
    }
}
