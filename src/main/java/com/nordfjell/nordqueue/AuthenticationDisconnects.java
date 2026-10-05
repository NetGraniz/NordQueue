package com.nordfjell.nordqueue;

import java.util.Locale;

final class AuthenticationDisconnects {
    private AuthenticationDisconnects() { }

    static boolean terminal(String reason) {
        String text = reason.toLowerCase(Locale.ROOT);
        return text.contains("login timeout exceeded") || text.contains("register timeout exceeded")
            || text.contains("login timed out") || text.contains("register timed out")
            || text.contains("authentication is temporarily unavailable");
    }
}
