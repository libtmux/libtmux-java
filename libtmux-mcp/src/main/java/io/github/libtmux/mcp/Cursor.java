package io.github.libtmux.mcp;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/** An authenticated trailing-line context for one pane and server process. */
record Cursor(long serverPid, String paneId, List<String> anchors) {

    private static final String VERSION = "2";
    private static final int CONTEXT_LINES = 8;
    private static final int DIGEST_BYTES = 16;
    private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();
    private static final byte[] SECRET = secret();

    Cursor {
        if (serverPid <= 0 || paneId.isEmpty() || anchors.size() > CONTEXT_LINES) {
            throw new IllegalArgumentException("invalid cursor state");
        }
        anchors = List.copyOf(anchors);
        if (anchors.stream().anyMatch(anchor -> !anchor.matches("[A-Za-z0-9_-]{22}"))) {
            throw new IllegalArgumentException("invalid cursor anchor");
        }
    }

    /** Records up to the last eight finished lines, without retaining their contents. */
    static Cursor of(long serverPid, String paneId, List<String> written) {
        int first = Math.max(0, written.size() - CONTEXT_LINES);
        return new Cursor(
                serverPid,
                paneId,
                written.subList(first, written.size()).stream().map(Cursor::digest).toList());
    }

    String encode() {
        byte[] payload = (VERSION + "|" + serverPid + "|" + paneId + "|" + String.join(",", anchors))
                .getBytes(StandardCharsets.UTF_8);
        return ENCODER.encodeToString(payload) + "." + ENCODER.encodeToString(mac(payload));
    }

    /** Reads a cursor issued by this process, or tells the caller to start again. */
    static Cursor decode(String encoded) {
        try {
            String[] token = encoded.split("\\.", -1);
            if (token.length != 2) {
                throw unreadable();
            }
            byte[] payload = Base64.getUrlDecoder().decode(token[0]);
            byte[] signature = Base64.getUrlDecoder().decode(token[1]);
            if (!MessageDigest.isEqual(mac(payload), signature)) {
                throw unreadable();
            }
            String[] parts = new String(payload, StandardCharsets.UTF_8).split("\\|", -1);
            if (parts.length != 4 || !VERSION.equals(parts[0])) {
                throw unreadable();
            }
            List<String> anchors = parts[3].isEmpty() ? List.of() : List.of(parts[3].split(",", -1));
            return new Cursor(Long.parseLong(parts[1]), parts[2], anchors);
        } catch (IllegalArgumentException e) {
            if (e.getMessage() != null && e.getMessage().startsWith("that cursor")) {
                throw e;
            }
            throw unreadable();
        }
    }

    static String digest(String line) {
        try {
            byte[] whole = MessageDigest.getInstance("SHA-256")
                    .digest(line.getBytes(StandardCharsets.UTF_8));
            return ENCODER.encodeToString(Arrays.copyOf(whole, DIGEST_BYTES));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }

    private static byte[] mac(byte[] payload) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(SECRET, "HmacSHA256"));
            return mac.doFinal(payload);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HMAC-SHA256 is unavailable", e);
        }
    }

    private static byte[] secret() {
        byte[] secret = new byte[32];
        new SecureRandom().nextBytes(secret);
        return secret;
    }

    private static IllegalArgumentException unreadable() {
        return new IllegalArgumentException(
                "that cursor is not one this server issued; omit 'cursor' to start from what the pane shows now");
    }
}
