package com.nordfjell.nordqueue;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Properties;
import org.junit.jupiter.api.Test;

final class IntegratedFeaturesTest {
    @Test
    void noticeDefaultsRemainValid() throws Exception {
        NoticeSettings settings = NoticeSettings.from(new Properties());
        assertEquals(8, settings.intervalSeconds());
        assertEquals(40, settings.clearLines());
        assertNotNull(settings.frame());
    }

    @Test
    void tabDefaultsRoundTrip() {
        QueueTabSettings defaults = QueueTabSettings.defaults();
        Properties properties = new Properties();
        defaults.writeDefaults(properties);
        assertEquals(defaults, QueueTabSettings.from(properties));
    }

    @Test
    void featureConfigsRejectUnknownFields() {
        Properties notice = new Properties();
        notice.setProperty("unknown", "value");
        assertThrows(IllegalArgumentException.class, () -> NoticeSettings.from(notice));

        Properties tab = new Properties();
        tab.setProperty("max-visible-players", "0");
        assertThrows(IllegalArgumentException.class, () -> QueueTabSettings.from(tab));
    }
}
