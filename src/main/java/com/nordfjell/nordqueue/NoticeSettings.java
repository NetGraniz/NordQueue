package com.nordfjell.nordqueue;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.serializer.gson.GsonComponentSerializer;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;

/** Bounded reader for the current notice configuration. */
record NoticeSettings(long intervalSeconds, long initialDelayMillis, int clearLines,
                      Component frame, int serializedBytes) {
    static final int MAX_FILE = 65536, MAX_MESSAGE = 8192;
    static final String DEFAULT_MESSAGE = "<gold>Waiting for an available server slot.</gold>";
    private static final Set<String> KEYS = Set.of("interval-seconds", "initial-delay-millis", "clear-lines", "message");
    private static final MiniMessage MINI = MiniMessage.builder().strict(true).build();
    long intervalNanos() { return TimeUnit.SECONDS.toNanos(intervalSeconds); }
    long initialDelayNanos() { return TimeUnit.MILLISECONDS.toNanos(initialDelayMillis); }

    static NoticeSettings load(Path config, boolean createMissing) throws IOException {
        if (createMissing && !Files.exists(config, LinkOption.NOFOLLOW_LINKS)) {
            Files.createDirectories(config.getParent());
            Properties defaults = new Properties();
            defaults.setProperty("interval-seconds", "8");
            defaults.setProperty("initial-delay-millis", "750");
            defaults.setProperty("clear-lines", "40");
            defaults.setProperty("message", DEFAULT_MESSAGE);
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            defaults.store(bytes, "NordQueue notice configuration. MiniMessage formatting is supported.");
            try { Files.write(config, bytes.toByteArray(), StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE); }
            catch (FileAlreadyExistsException concurrentCreation) {
                if (!Files.isRegularFile(config, LinkOption.NOFOLLOW_LINKS)) throw concurrentCreation;
            }
        }
        if (!Files.isRegularFile(config, LinkOption.NOFOLLOW_LINKS)) throw new IOException("Config is missing or is not a regular file");
        byte[] bytes;
        try (InputStream input = Files.newInputStream(config, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)) {
            bytes = input.readNBytes(MAX_FILE + 1);
        }
        if (bytes.length > MAX_FILE) throw new IOException("Config exceeds 64 KiB");
        Properties properties = new Properties() {
            @Override public synchronized Object put(Object key, Object value) {
                if (containsKey(key)) throw new IllegalArgumentException("Duplicate configuration key");
                return super.put(key, value);
            }
        };
        // Retain Properties.load(InputStream) ISO-8859-1 + Unicode escape semantics.
        properties.load(new ByteArrayInputStream(bytes));
        return from(properties);
    }
    static NoticeSettings from(Properties properties) {
        for (Map.Entry<Object,Object> entry : properties.entrySet()) {
            if (!KEYS.contains(entry.getKey()) || !(entry.getValue() instanceof String))
                throw new IllegalArgumentException("Unknown or non-string configuration field");
        }
        long interval = number(properties, "interval-seconds", 8, 3, 3600);
        long delay = number(properties, "initial-delay-millis", 750, 0, 60000);
        int lines = (int) number(properties, "clear-lines", 40, 0, 200);
        String message = properties.getProperty("message", DEFAULT_MESSAGE);
        if (message.isBlank() || message.length() > MAX_MESSAGE) throw new IllegalArgumentException("Message must contain 1..8192 characters");
        int tags = 0;
        for (int i = 0; i < message.length(); i++) {
            char c = message.charAt(i);
            if (Character.isISOControl(c) && c != '\n' && c != '\r' && c != '\t') throw new IllegalArgumentException("Message contains controls");
            if (c == '<' && ++tags > 128) throw new IllegalArgumentException("Message has too many tags");
            if (Character.isHighSurrogate(c)) {
                if (i+1>=message.length() || !Character.isLowSurrogate(message.charAt(++i)))
                    throw new IllegalArgumentException("Message contains malformed Unicode");
            } else if (Character.isLowSurrogate(c)) throw new IllegalArgumentException("Message contains malformed Unicode");
        }
        Component frame = Component.text("\n".repeat(lines)).append(MINI.deserialize(message));
        validateTree(frame);
        int serialized = GsonComponentSerializer.gson().serialize(frame).getBytes(StandardCharsets.UTF_8).length;
        if (serialized > 65536) throw new IllegalArgumentException("Rendered frame exceeds 64 KiB JSON representation");
        return new NoticeSettings(interval, delay, lines, frame, serialized);
    }
    private static long number(Properties properties, String key, long fallback, long minimum, long maximum) {
        String raw = properties.getProperty(key, Long.toString(fallback)).trim();
        long value;
        try { value = Long.parseLong(raw); }
        catch (NumberFormatException malformed) { throw new IllegalArgumentException("Invalid numeric configuration field: " + key); }
        if (value < minimum || value > maximum) throw new IllegalArgumentException("Configuration field out of range: " + key);
        return value;
    }
    private record Node(Component component, int depth) {}
    private static void validateTree(Component frame) {
        Deque<Node> nodes = new ArrayDeque<>(); nodes.add(new Node(frame, 0)); int count = 0;
        while (!nodes.isEmpty()) {
            Node node = nodes.removeFirst();
            if (++count > 4096 || node.depth > 32) throw new IllegalArgumentException("Rendered frame exceeds component bounds");
            for (Component child : node.component.children()) nodes.addLast(new Node(child, node.depth + 1));
            if (node.component instanceof net.kyori.adventure.text.TranslatableComponent translated)
                for (var argument : translated.arguments()) if (argument.value() instanceof Component child)
                    nodes.addLast(new Node(child, node.depth + 1));
            if (node.component instanceof net.kyori.adventure.text.SelectorComponent selector && selector.separator()!=null)
                nodes.addLast(new Node(selector.separator(), node.depth + 1));
            if (node.component instanceof net.kyori.adventure.text.NBTComponent<?> nbt && nbt.separator()!=null)
                nodes.addLast(new Node(nbt.separator(), node.depth + 1));
            if (node.component.hoverEvent() != null && node.component.hoverEvent().value() instanceof Component hover)
                nodes.addLast(new Node(hover, node.depth + 1));
            if (node.component.hoverEvent() != null
                    && node.component.hoverEvent().value() instanceof net.kyori.adventure.text.event.HoverEvent.ShowEntity entity
                    && entity.name()!=null) nodes.addLast(new Node(entity.name(),node.depth+1));
            if (nodes.size() > 4096) throw new IllegalArgumentException("Rendered frame exceeds component bounds");
        }
    }
}

