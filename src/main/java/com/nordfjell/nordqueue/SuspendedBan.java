package com.nordfjell.nordqueue;

record SuspendedBan(String playerName, long expiresAtMillis, String reason) {
    boolean active(long nowMillis) {
        return expiresAtMillis > nowMillis;
    }

    long remainingMillis(long nowMillis) {
        return Math.max(0L, expiresAtMillis - nowMillis);
    }
}
