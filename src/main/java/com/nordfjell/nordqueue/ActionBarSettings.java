package com.nordfjell.nordqueue;

import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Properties;

record ActionBarSettings(long intervalSeconds, List<String> messages) {
    private static final String MESSAGE_PREFIX = "message.";
    private static final MiniMessage MINI = MiniMessage.builder().strict(true).build();

    static ActionBarSettings load(Path path, boolean createMissing) throws IOException {
        if (createMissing && !Files.exists(path)) {
            Files.createDirectories(path.getParent());
            Properties defaults = new Properties();
            defaults.setProperty("interval-seconds", "7");
            defaults.setProperty("message.1", "<gold>Players who have skipped the queue in the last hour: <skipped_last_hour></gold>");
            defaults.setProperty("message.2", "<gold>Priority Queue Estimated Wait: <priority_estimate></gold>");
            defaults.setProperty("message.3", "<gold>Non-Priority Queue Estimated Wait: <regular_estimate></gold>");
            try (OutputStream output = Files.newOutputStream(path)) {
                defaults.store(output, "NordQueue rotating Action Bar settings. MiniMessage formatting is supported.");
            }
        }
        Properties properties = new Properties();
        try (InputStream input = Files.newInputStream(path)) {
            properties.load(input);
        }
        return from(properties);
    }

    private static ActionBarSettings from(Properties properties) {
        long interval;
        try {
            interval = Long.parseLong(properties.getProperty("interval-seconds", "7").trim());
        } catch (NumberFormatException malformed) {
            throw new IllegalArgumentException("interval-seconds must be an integer", malformed);
        }
        if (interval < 1 || interval > 3600) {
            throw new IllegalArgumentException("interval-seconds must be between 1 and 3600");
        }

        List<Map.Entry<Integer, String>> numbered = new ArrayList<>();
        for (Map.Entry<Object, Object> entry : properties.entrySet()) {
            String key = entry.getKey().toString();
            if (key.equals("interval-seconds")) continue;
            if (!key.startsWith(MESSAGE_PREFIX)) throw new IllegalArgumentException("Unknown Action Bar setting: " + key);
            int number;
            try {
                number = Integer.parseInt(key.substring(MESSAGE_PREFIX.length()));
            } catch (NumberFormatException malformed) {
                throw new IllegalArgumentException("Action Bar message keys must use message.<number>", malformed);
            }
            String message = entry.getValue().toString();
            if (number < 1 || message.isBlank() || message.length() > 2048) {
                throw new IllegalArgumentException("Action Bar messages must be numbered from 1 and contain 1..2048 characters");
            }
            validate(message);
            numbered.add(Map.entry(number, message));
        }
        numbered.sort(Comparator.comparingInt(Map.Entry::getKey));
        if (numbered.isEmpty() || numbered.size() > 20) {
            throw new IllegalArgumentException("Configure between 1 and 20 Action Bar messages");
        }
        for (int index = 0; index < numbered.size(); index++) {
            if (numbered.get(index).getKey() != index + 1) {
                throw new IllegalArgumentException("Action Bar message numbers must be contiguous from message.1");
            }
        }
        return new ActionBarSettings(interval, numbered.stream().map(Map.Entry::getValue).toList());
    }

    private static void validate(String message) {
        MINI.deserialize(message,
            Placeholder.unparsed("position", "1"),
            Placeholder.unparsed("size", "1"),
            Placeholder.unparsed("regular_size", "1"),
            Placeholder.unparsed("priority_size", "1"),
            Placeholder.unparsed("estimate", "0 min"),
            Placeholder.unparsed("priority_estimate", "0 min"),
            Placeholder.unparsed("regular_estimate", "0 min"),
            Placeholder.unparsed("skipped_last_hour", "0"));
    }
}
