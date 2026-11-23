package com.nordfjell.nordqueue;

import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import java.util.Properties;

record QueueTabSettings(long intervalMillis, int maxVisible, String header, String footer, String playerFormat) {
    static QueueTabSettings defaults() {
        return new QueueTabSettings(1000, 80,
            "<dark_green><bold>Server queue</bold></dark_green><newline><gold>Position in queue: <position></gold><newline><yellow>Estimated time: <estimate></yellow>",
            "<gold>Players waiting: <size></gold>", "<white><player></white>");
    }
    static QueueTabSettings from(Properties p) {
        var d = defaults();
        long interval = Long.parseLong(p.getProperty("update-interval-millis", "1000"));
        int maximum = Integer.parseInt(p.getProperty("max-visible-players", "80"));
        if (interval < 500 || interval > 60000) throw new IllegalArgumentException("Interval must be 500..60000ms");
        if (maximum < 1 || maximum > 80) throw new IllegalArgumentException("Visible players must be 1..80");
        return new QueueTabSettings(interval, maximum, p.getProperty("header", d.header),
            p.getProperty("footer", d.footer), p.getProperty("player-format", d.playerFormat));
    }
    void validate(MiniMessage mini) {
        for (String text : new String[]{header, footer, playerFormat}) {
            if (text.length() > 8192) throw new IllegalArgumentException("Format is too long");
            mini.deserialize(text, Placeholder.unparsed("position", "1"), Placeholder.unparsed("size", "1"),
                Placeholder.unparsed("estimate", QueueEstimate.format(1)), Placeholder.unparsed("player", "Test"));
        }
    }
    void writeDefaults(Properties p) {
        p.setProperty("update-interval-millis", Long.toString(intervalMillis));
        p.setProperty("max-visible-players", Integer.toString(maxVisible));
        p.setProperty("header", header); p.setProperty("footer", footer); p.setProperty("player-format", playerFormat);
    }
}

