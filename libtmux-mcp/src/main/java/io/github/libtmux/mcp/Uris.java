package io.github.libtmux.mcp;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Objects;

/**
 * Reads the values out of a templated resource URI.
 *
 * <p>Only the one shape MCP resource templates use here: a prefix, one {@code {name}} path segment,
 * and an optional suffix. A general RFC 6570 implementation would accept forms this server never
 * produces.
 */
final class Uris {

    private static final char[] HEX = "0123456789ABCDEF".toCharArray();

    private Uris() {}

    /**
     * The values a URI supplies for a template's variables, in the order the template names them.
     *
     * @throws IllegalArgumentException if the URI does not fit the template
     */
    static List<String> values(String template, String uri) {
        int open = template.indexOf('{');
        int close = open < 0 ? -1 : template.indexOf('}', open + 1);
        if (open < 0
                || close < 0
                || close == open + 1
                || template.indexOf('{', close + 1) >= 0
                || template.indexOf('}', close + 1) >= 0) {
            throw new IllegalStateException("resource template must contain one named segment: " + template);
        }

        String before = template.substring(0, open);
        String after = template.substring(close + 1);
        if (!uri.startsWith(before) || !uri.endsWith(after)) {
            throw mismatch(template, uri);
        }
        int end = uri.length() - after.length();
        if (end <= before.length()) {
            throw mismatch(template, uri);
        }
        String encoded = uri.substring(before.length(), end);
        if (encoded.indexOf('/') >= 0) {
            throw mismatch(template, uri);
        }
        String value = decodeSegment(encoded, template, uri);
        if (!encoded.equals(segment(value))) {
            throw mismatch(template, uri);
        }
        return List.of(value);
    }

    /** Encodes one value for the simple path-segment expansion used by the resource templates. */
    static String segment(String value) {
        Objects.requireNonNull(value, "value");
        StringBuilder encoded = new StringBuilder(value.length());
        for (byte octet : value.getBytes(StandardCharsets.UTF_8)) {
            int unsigned = Byte.toUnsignedInt(octet);
            if (unreserved(unsigned)) {
                encoded.append((char) unsigned);
            } else {
                encoded.append('%').append(HEX[unsigned >>> 4]).append(HEX[unsigned & 0x0f]);
            }
        }
        return encoded.toString();
    }

    private static String decodeSegment(String encoded, String template, String uri) {
        ByteArrayOutputStream decoded = new ByteArrayOutputStream(encoded.length());
        for (int index = 0; index < encoded.length(); ) {
            int codePoint = encoded.codePointAt(index);
            if (codePoint == '%') {
                if (index + 2 >= encoded.length()) {
                    throw mismatch(template, uri);
                }
                int high = Character.digit(encoded.charAt(index + 1), 16);
                int low = Character.digit(encoded.charAt(index + 2), 16);
                if (high < 0 || low < 0) {
                    throw mismatch(template, uri);
                }
                decoded.write((high << 4) | low);
                index += 3;
            } else {
                decoded.writeBytes(new String(Character.toChars(codePoint)).getBytes(StandardCharsets.UTF_8));
                index += Character.charCount(codePoint);
            }
        }
        try {
            return StandardCharsets.UTF_8
                    .newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(decoded.toByteArray()))
                    .toString();
        } catch (CharacterCodingException e) {
            throw mismatch(template, uri);
        }
    }

    private static boolean unreserved(int octet) {
        return (octet >= 'a' && octet <= 'z')
                || (octet >= 'A' && octet <= 'Z')
                || (octet >= '0' && octet <= '9')
                || octet == '-'
                || octet == '.'
                || octet == '_'
                || octet == '~';
    }

    private static IllegalArgumentException mismatch(String template, String uri) {
        return new IllegalArgumentException("'" + uri + "' is not a " + template);
    }
}
