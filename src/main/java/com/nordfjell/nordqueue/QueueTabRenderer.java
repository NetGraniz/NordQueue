package com.nordfjell.nordqueue;

import com.nordfjell.nordqueue.QueueSnapshot;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.player.TabList;
import com.velocitypowered.api.proxy.player.TabListEntry;
import com.velocitypowered.api.util.GameProfile;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import java.util.*;

/** Called only under the lifecycle lock. Never adopt entries belonging to other producers. */
final class QueueTabRenderer {
    record Row(UUID id, GameProfile profile, Component name, int order) {}
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
    private long version = Long.MIN_VALUE;
    private QueueTabSettings frameSettings;
    private Map<UUID, Row> rows = Map.of();
    private List<Row> leading = List.of();
    private Set<UUID> leadingIds = Set.of();

    void refresh(ProxyServer proxy, QueueSnapshot snapshot, String queueServer, QueueTabSettings settings) {
        if (version != snapshot.version() || !settings.equals(frameSettings)) {
            Map<UUID, Row> next = new HashMap<>();
            List<Row> first = new ArrayList<>(settings.maxVisible());
            for (UUID id : snapshot.orderedIds()) {
                Player player = proxy.getPlayer(id).orElse(null);
                if (player == null || !player.isActive()) continue;
                int position = snapshot.absolutePosition(id);
                Component name = mini.deserialize(settings.playerFormat(),
                    Placeholder.unparsed("player", player.getUsername()),
                    Placeholder.unparsed("position", Integer.toString(position)),
                    Placeholder.unparsed("size", Integer.toString(snapshot.size())),
                    Placeholder.unparsed("estimate", QueueEstimate.format(position)));
                Row row = new Row(id, player.getGameProfile(), name, Integer.MAX_VALUE - position);
                next.put(id, row);
                if (first.size() < settings.maxVisible()) first.add(row);
            }
            rows = Map.copyOf(next); leading = List.copyOf(first);
            leadingIds = Set.copyOf(first.stream().map(Row::id).toList());
            version = snapshot.version(); frameSettings = settings;
        }
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
            sync(view, selected(id), snapshot.absolutePosition(id), snapshot.size(), settings);
        }
        views.keySet().removeIf(id -> !active.contains(id));
        outsideQueue.keySet().removeIf(id -> !active.contains(id));
    }

    private List<Row> selected(UUID viewer) {
        if (leadingIds.contains(viewer)) return leading;
        Row self = rows.get(viewer);
        if (self == null) return leading;
        List<Row> result = new ArrayList<>(leading);
        if (result.size() == frameSettings.maxVisible()) result.removeLast();
        result.add(self); return result;
    }

    private void sync(View view, List<Row> desired, int position, int size, QueueTabSettings settings) {
        Player player = view.player;
        TabList tab = player.getTabList();
        Set<UUID> ids = new HashSet<>();
        for (Row row : desired) ids.add(row.id);
        var iterator = view.owned.entrySet().iterator();
        while (iterator.hasNext()) {
            var old = iterator.next();
            if (!ids.contains(old.getKey())) {
                removeOwned(tab, old.getKey(), old.getValue()); iterator.remove();
            }
        }
        for (Row row : desired) {
            TabListEntry current = tab.getEntry(row.id).orElse(null);
            TabListEntry own = view.owned.get(row.id);
            if (current != null && current != own) {
                view.owned.remove(row.id); continue; // Relinquish entries replaced by another producer.
            }
            if (current == null) {
                current = TabListEntry.builder().tabList(tab).profile(row.profile).displayName(row.name)
                    .latency(0).gameMode(0).listed(true).listOrder(row.order).build();
                tab.addEntry(current); view.owned.put(row.id, current);
            } else {
                if (!current.getDisplayNameComponent().equals(Optional.of(row.name))) current.setDisplayName(row.name);
                if (current.getLatency() != 0) current.setLatency(0);
                if (current.getListOrder() != row.order) current.setListOrder(row.order);
                if (!current.isListed()) current.setListed(true);
            }
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
    void forgetAll() { views.clear(); outsideQueue.clear(); rows = Map.of(); leading = List.of(); leadingIds = Set.of(); version = Long.MIN_VALUE; }
    int viewerCount() { return views.size(); }
    int ownedCount() { return views.values().stream().mapToInt(view -> view.owned.size()).sum(); }
}

