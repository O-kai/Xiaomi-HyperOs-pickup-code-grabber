package io.github.okaidev.pickupcode;

import android.app.Notification;
import android.os.Bundle;
import android.os.Parcelable;

import java.util.LinkedHashSet;

/** Bounded, documented visible-text fields only. Does not open a notification's deep link. */
final class NotificationText {
    private static final int MAX_CHARS = 8192;
    private static final int MAX_ITEMS = 32;

    static String build(Notification n) {
        LinkedHashSet<String> pieces = new LinkedHashSet<>();
        if (n == null) return "";
        try {
            Bundle e = n.extras;
            if (e != null) {
                String[] keys = {Notification.EXTRA_TITLE, Notification.EXTRA_TEXT,
                        Notification.EXTRA_BIG_TEXT, Notification.EXTRA_SUB_TEXT,
                        Notification.EXTRA_SUMMARY_TEXT, Notification.EXTRA_INFO_TEXT};
                for (String key : keys) add(pieces, e.getCharSequence(key));
                CharSequence[] lines = e.getCharSequenceArray(Notification.EXTRA_TEXT_LINES);
                if (lines != null) {
                    for (int i = 0; i < lines.length && i < MAX_ITEMS; i++) add(pieces, lines[i]);
                }
                Parcelable[] messages = e.getParcelableArray("android.messages");
                if (messages != null) {
                    for (int i = Math.max(0, messages.length - MAX_ITEMS); i < messages.length; i++) {
                        if (messages[i] instanceof Bundle) {
                            add(pieces, ((Bundle) messages[i]).getCharSequence("text"));
                        }
                    }
                }
            }
        } catch (RuntimeException ignored) {
            // Malformed OEM extras must never crash system_server.
        }
        add(pieces, n.tickerText);
        StringBuilder out = new StringBuilder();
        for (String piece : pieces) {
            if (out.length() > 0) out.append('\n');
            int remaining = MAX_CHARS - out.length();
            if (remaining <= 0) break;
            out.append(piece, 0, Math.min(piece.length(), remaining));
            if (out.length() >= MAX_CHARS) break;
        }
        return out.toString();
    }

    private static void add(LinkedHashSet<String> pieces, CharSequence value) {
        if (value == null || pieces.size() >= 128) return;
        String text = value.subSequence(0, Math.min(value.length(), MAX_CHARS)).toString().trim();
        if (!text.isEmpty()) pieces.add(text);
    }

    private NotificationText() { }
}
