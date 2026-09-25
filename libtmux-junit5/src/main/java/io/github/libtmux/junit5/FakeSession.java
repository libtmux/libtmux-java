package io.github.libtmux.junit5;

import java.util.ArrayList;
import java.util.List;

/** One session in a {@link FakeTmux}, holding its windows in creation order. */
final class FakeSession {
    final String id;
    String name;
    final List<FakeWindow> windows = new ArrayList<>();

    FakeSession(String id, String name) {
        this.id = id;
        this.name = name;
    }
}
