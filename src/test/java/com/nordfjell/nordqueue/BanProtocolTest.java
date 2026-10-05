package com.nordfjell.nordqueue;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;

public final class BanProtocolTest {
    private BanProtocolTest() {
    }

    public static void main(String[] args) throws Exception {
        String reason = "Chat spam";
        String encoded = Base64.getUrlEncoder().withoutPadding()
            .encodeToString(reason.getBytes(StandardCharsets.UTF_8));
        SuspendedBan kick = BanProtocol.decodeKick("Player", "NORD_BAN_V1|123456789|" + encoded)
            .orElseThrow();
        assert kick.playerName().equals("Player");
        assert kick.expiresAtMillis() == 123456789L;
        assert kick.reason().equals(reason);
        assert BanProtocol.decodeKick("Player", "ordinary kick").isEmpty();
        assert BanProtocol.decodeKick("Player", "NORD_BAN_V1|123|_w").isEmpty();
        assert BanProtocol.decodeKick("Player", "NORD_BAN_V1|123|"+Base64.getUrlEncoder().encodeToString("bad\nlog".getBytes(StandardCharsets.UTF_8))).isEmpty();

        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream output = new DataOutputStream(bytes)) {
            output.writeUTF("BAN");
            output.writeUTF("Player");
            output.writeLong(987654321L);
            output.writeUTF(reason);
        }
        BanProtocol.SyncMessage sync = BanProtocol.decodeSync(bytes.toByteArray()).orElseThrow();
        assert sync.action().equals("BAN");
        assert sync.ban().reason().equals(reason);

        Path directory = Files.createTempDirectory("nordqueue-ban-store-");
        Path file = directory.resolve("bans.properties");
        SuspendedBanStore store = new SuspendedBanStore(file);
        store.load();
        long future = System.currentTimeMillis() + 60_000L;
        store.put(new SuspendedBan("Player", future, reason));
        SuspendedBanStore reloaded = new SuspendedBanStore(file);
        reloaded.load();
        assert reloaded.active("player").orElseThrow().reason().equals(reason);
        assert reloaded.remove("PLAYER").isPresent();
        assert reloaded.active("Player").isEmpty();
        Files.deleteIfExists(file);
        Files.deleteIfExists(directory);
    }
}
