package com.nordfjell.nordqueue;

import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import java.util.Properties;

record QueueTabSettings(long intervalMillis, String header, String footer) {
    static QueueTabSettings defaults() {
        return new QueueTabSettings(1000,
            "<dark_green><bold>Server queue</bold></dark_green><newline><gold>Position in queue: <position></gold><newline><yellow>Estimated time: <estimate></yellow>",
            "");
    }
    static QueueTabSettings from(Properties p) {
        var d = defaults();
        long interval = Long.parseLong(p.getProperty("update-interval-millis", "1000"));
        if (interval < 500 || interval > 60000) throw new IllegalArgumentException("Interval must be 500..60000ms");
        return new QueueTabSettings(interval, p.getProperty("header", d.header),
            p.getProperty("footer", d.footer));
    }
    void validate(MiniMessage mini) {
        for (String text : new String[]{header, footer}) {
            if (text.length() > 8192) throw new IllegalArgumentException("Format is too long");
            mini.deserialize(text, Placeholder.unparsed("position", "1"), Placeholder.unparsed("size", "1"),
                Placeholder.unparsed("estimate", QueueEstimate.format(1)));
        }
    }
    void writeDefaults(Properties p) {
        p.setProperty("update-interval-millis", Long.toString(intervalMillis));
        p.setProperty("header", header); p.setProperty("footer", footer);
    }
}

