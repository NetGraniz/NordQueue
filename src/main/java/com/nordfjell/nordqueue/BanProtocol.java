package com.nordfjell.nordqueue;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.ByteBuffer;
import java.util.Base64;
import java.util.Locale;
import java.util.Optional;

final class BanProtocol {
    static final String CHANNEL = "nordfjell:bans";
    private static final String KICK_PREFIX = "NORD_BAN_V1|";
    private static final int MAX_PLAYER_NAME_LENGTH = 32;
    private static final int MAX_REASON_LENGTH = 256;

    private BanProtocol() {
    }

    static Optional<SuspendedBan> decodeKick(String playerName, String message) {
        if (message == null || !message.startsWith(KICK_PREFIX)) return Optional.empty();
        String[] parts = message.split("\\|", 3);
        if (parts.length != 3) return Optional.empty();
        try {
            long expiresAt = Long.parseLong(parts[1]);
            String reason = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(Base64.getUrlDecoder().decode(parts[2]))).toString();
            return valid(playerName, expiresAt, reason)
                ? Optional.of(new SuspendedBan(playerName, expiresAt, reason))
                : Optional.empty();
        } catch (IllegalArgumentException | CharacterCodingException exception) {
            return Optional.empty();
        }
    }

    static Optional<SyncMessage> decodeSync(byte[] payload) {
        if (payload == null || payload.length == 0 || payload.length > 4096) return Optional.empty();
        try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(payload))) {
            String action = input.readUTF().toUpperCase(Locale.ROOT);
            String playerName = input.readUTF();
            long expiresAt = input.readLong();
            String reason = input.readUTF();
            if (input.available() != 0 || !(action.equals("BAN") || action.equals("UNBAN"))) {
                return Optional.empty();
            }
            if (!valid(playerName, action.equals("UNBAN") ? Math.max(1L, expiresAt) : expiresAt, reason)) return Optional.empty();
            return Optional.of(new SyncMessage(action, new SuspendedBan(playerName, expiresAt, reason)));
        } catch (IOException | RuntimeException exception) {
            return Optional.empty();
        }
    }

    private static boolean valid(String playerName, long expiresAt, String reason) {
        return playerName != null && !playerName.isBlank() && playerName.length() <= MAX_PLAYER_NAME_LENGTH
            && playerName.matches("[A-Za-z0-9_.-]+") && expiresAt > 0
            && reason != null && !reason.isBlank() && reason.length() <= MAX_REASON_LENGTH
            && reason.chars().noneMatch(Character::isISOControl);
    }

    record SyncMessage(String action, SuspendedBan ban) {
    }
}
