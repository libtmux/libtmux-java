package io.github.libtmux.mcp;

import com.google.re2j.Pattern;
import com.google.re2j.PatternSyntaxException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.jspecify.annotations.Nullable;

/** Linear-time matching plus fixed caller-input and search-work bounds. */
final class TextPatterns {

    static final int MAX_PATTERNS = 32;
    static final int MAX_PATTERN_BYTES = 4_096;
    static final int MAX_TOTAL_PATTERN_BYTES = 16_384;
    static final int MAX_SEARCH_BYTES = 1_000_000;
    static final int MAX_SEARCH_PANES = 200;
    static final int MAX_SEARCH_LINES = 20_000;
    static final Duration MAX_SEARCH_TIME = Duration.ofSeconds(5);

    private TextPatterns() {}

    static List<Matcher> compile(List<String> sources, boolean regex) {
        if (sources.size() > MAX_PATTERNS) {
            throw new IllegalArgumentException("at most " + MAX_PATTERNS + " patterns are allowed");
        }
        int total = 0;
        List<Matcher> matchers = new ArrayList<>(sources.size());
        for (String source : sources) {
            int bytes = utf8Bytes(source);
            if (bytes > MAX_PATTERN_BYTES) {
                throw new IllegalArgumentException("one pattern exceeds " + MAX_PATTERN_BYTES + " UTF-8 bytes");
            }
            total = Math.addExact(total, bytes);
            if (total > MAX_TOTAL_PATTERN_BYTES) {
                throw new IllegalArgumentException("patterns exceed " + MAX_TOTAL_PATTERN_BYTES + " total UTF-8 bytes");
            }
            matchers.add(Matcher.of(source, regex));
        }
        return List.copyOf(matchers);
    }

    static Matcher compileOne(String source, boolean regex) {
        return compile(List.of(source), regex).getFirst();
    }

    static WorkBudget searchBudget() {
        return new WorkBudget();
    }

    private static int utf8Bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8).length;
    }

    record Matcher(String source, @Nullable Pattern compiled) {

        static Matcher of(String source, boolean regex) {
            if (!regex) {
                return new Matcher(source, null);
            }
            try {
                return new Matcher(source, Pattern.compile(source));
            } catch (PatternSyntaxException e) {
                throw new IllegalArgumentException("'" + source + "' is not a supported regular expression: "
                        + e.getDescription() + ". Omit 'regex' to match it as plain text instead");
            }
        }

        boolean matches(String line) {
            return compiled == null
                    ? line.contains(source)
                    : compiled.matcher(line).find();
        }
    }

    static final class WorkBudget {

        private final long started = System.nanoTime();
        private final long deadline = started + MAX_SEARCH_TIME.toNanos();
        private int remainingBytes = MAX_SEARCH_BYTES;
        private int remainingPanes = MAX_SEARCH_PANES;
        private int remainingLines = MAX_SEARCH_LINES;
        private int panes;
        private int lines;
        private int bytes;

        private WorkBudget() {}

        boolean tryStartPane() {
            if (expired() || remainingPanes == 0) {
                return false;
            }
            remainingPanes--;
            panes++;
            return true;
        }

        boolean trySpend(String text) {
            int size = utf8Bytes(text);
            if (expired() || remainingLines == 0 || size > remainingBytes) {
                return false;
            }
            remainingLines--;
            remainingBytes -= size;
            lines++;
            bytes += size;
            return true;
        }

        boolean expired() {
            return System.nanoTime() >= deadline;
        }

        int panes() {
            return panes;
        }

        int lines() {
            return lines;
        }

        int bytes() {
            return bytes;
        }

        double seconds() {
            return Math.round((System.nanoTime() - started) / 10_000_000.0) / 100.0;
        }
    }
}
