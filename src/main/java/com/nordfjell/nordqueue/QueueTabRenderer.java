package com.nordfjell.nordqueue;

import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.player.TabList;
import com.velocitypowered.api.proxy.player.TabListEntry;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import java.util.*;

/** Called only under the lifecycle lock. Never adopt entries belonging to other producers. */
final class QueueTabRenderer {
    private static final class View {
        final Player player;
        final Map<UUID, TabListEntry> owned = new HashMap<>();
        Component header, footer;
        int position = -1, size = -1;
        QueueTabSettings settings;
        View(Player player) { this.player = player; }
    }
    private final Map<UUID, View> views = new HashMap<>();
    private final Map<UUID, Player> outsideQueue = new HashMap<>();
    private final MiniMessage mini = MiniMessage.miniMessage();
    void refresh(ProxyServer proxy, QueueSnapshot snapshot, String queueServer, QueueTabSettings settings) {
        Set<UUID> active = new HashSet<>();
        for (Player player : proxy.getAllPlayers()) {
            UUID id = player.getUniqueId(); active.add(id);
            if (!player.isActive()) { forget(player); continue; }
            boolean inLimbo = player.getCurrentServer().map(s -> s.getServerInfo().getName()
                .equalsIgnoreCase(queueServer)).orElse(false);
            if (!snapshot.isQueued(id) || !inLimbo || outsideQueue.get(id) == player) { clear(player); continue; }
            View view = views.get(id);
            if (view == null || view.player != player) {
                view = new View(player); views.put(id, view);
            }
            sync(view, snapshot.absolutePosition(id), snapshot.size(), settings);
        }
        views.keySet().removeIf(id -> !active.contains(id));
        outsideQueue.keySet().removeIf(id -> !active.contains(id));
    }

    private void sync(View view, int position, int size, QueueTabSettings settings) {
        Player player = view.player;
        TabList tab = player.getTabList();
        UUID id = player.getUniqueId();
        var iterator = view.owned.entrySet().iterator();
        while (iterator.hasNext()) {
            var old = iterator.next();
            if (!old.getKey().equals(id)) {
                removeOwned(tab, old.getKey(), old.getValue()); iterator.remove();
            }
        }
        TabListEntry current = tab.getEntry(id).orElse(null);
        TabListEntry own = view.owned.get(id);
        if (current != null && current != own) {
            view.owned.remove(id); // A backend or another plugin already supplies the standard entry.
        } else if (current == null) {
            int latency = latency(player);
            current = TabListEntry.builder().tabList(tab).profile(player.getGameProfile())
                .latency(latency).gameMode(0).listed(true).listOrder(0).build();
            tab.addEntry(current); view.owned.put(id, current);
        } else {
            int latency = latency(player);
            if (current.getLatency() != latency) current.setLatency(latency);
            if (current.getListOrder() != 0) current.setListOrder(0);
            if (!current.isListed()) current.setListed(true);
        }
        if (view.position != position || view.size != size || !settings.equals(view.settings)) {
            Component header = text(settings.header(), position, size);
            Component footer = text(settings.footer(), position, size);
            if (!header.equals(view.header) || !footer.equals(view.footer)) {
                player.sendPlayerListHeaderAndFooter(header, footer); view.header = header; view.footer = footer;
            }
            view.position = position; view.size = size; view.settings = settings;
        }
    }

    private Component text(String template, int position, int size) {
        return mini.deserialize(template, Placeholder.unparsed("position", Integer.toString(position)),
            Placeholder.unparsed("size", Integer.toString(size)),
            Placeholder.unparsed("estimate", QueueEstimate.format(position)));
    }
    private static int latency(Player player) {
        return (int) Math.min(Integer.MAX_VALUE, Math.max(0L, player.getPing()));
    }
    private void removeOwned(TabList tab, UUID id, TabListEntry owned) {
        if (tab.getEntry(id).orElse(null) == owned) tab.removeEntry(id);
    }
    void clear(Player player) {
        View view = views.get(player.getUniqueId());
        if (view == null || view.player != player) return;
        views.remove(player.getUniqueId());
        if (!player.isActive()) return;
        for (var entry : view.owned.entrySet()) removeOwned(player.getTabList(), entry.getKey(), entry.getValue());
        player.clearPlayerListHeaderAndFooter();
    }
    void forget(Player player) {
        View view = views.get(player.getUniqueId());
        if (view != null && view.player == player) views.remove(player.getUniqueId());
        if (outsideQueue.get(player.getUniqueId()) == player) outsideQueue.remove(player.getUniqueId());
    }
    void connected(Player player, boolean inQueue) {
        if (inQueue) {
            if (outsideQueue.get(player.getUniqueId()) == player) outsideQueue.remove(player.getUniqueId());
        } else {
            clear(player); outsideQueue.put(player.getUniqueId(), player);
        }
    }
    void forgetAll() {
        for (View view : List.copyOf(views.values())) clear(view.player);
        outsideQueue.clear();
    }
    int viewerCount() { return views.size(); }
    int ownedCount() { return views.values().stream().mapToInt(view -> view.owned.size()).sum(); }
}

