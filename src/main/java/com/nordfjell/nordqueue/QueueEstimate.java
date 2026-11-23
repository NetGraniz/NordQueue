package com.nordfjell.nordqueue;

/** Fixed queue-position estimate; no history collection, clocks or extra tasks. */
final class QueueEstimate {
    private QueueEstimate() {}

    static String format(int position) {
        long minutes = Math.max(0L, (long) position - 1L) * 20L;
        if (minutes < 60L) return "~" + minutes + " min";
        return "~" + (minutes / 60L) + " h " + (minutes % 60L) + " min";
    }
}

