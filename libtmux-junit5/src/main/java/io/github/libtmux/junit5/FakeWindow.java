package io.github.libtmux.junit5;

import java.util.ArrayList;
import java.util.List;

/** One window in a {@link FakeSession}, holding its panes in creation order. */
final class FakeWindow {
    final String id;
    final int index;
    String name;
    boolean active;
    final List<FakePane> panes = new ArrayList<>();

    FakeWindow(String id, int index, String name) {
        this.id = id;
        this.index = index;
        this.name = name;
    }

    FakePane active() {
        return panes.getFirst();
    }
}
