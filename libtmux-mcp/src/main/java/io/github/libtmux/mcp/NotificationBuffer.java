package io.github.libtmux.mcp;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

/** A bounded set that coalesces repeated resource updates while a client is slow. */
final class NotificationBuffer {

    private final int capacity;
    private final Set<String> pending = new LinkedHashSet<>();

    NotificationBuffer(int capacity) {
        if (capacity < 1) {
            throw new IllegalArgumentException("notification capacity is not positive");
        }
        this.capacity = capacity;
    }

    synchronized boolean offer(String uri) {
        if (pending.contains(uri)) {
            return true;
        }
        return pending.size() < capacity && pending.add(uri);
    }

    synchronized Set<String> drain() {
        if (pending.isEmpty()) {
            return Set.of();
        }
        Set<String> drained = Collections.unmodifiableSet(new LinkedHashSet<>(pending));
        pending.clear();
        return drained;
    }

    synchronized int size() {
        return pending.size();
    }
}
