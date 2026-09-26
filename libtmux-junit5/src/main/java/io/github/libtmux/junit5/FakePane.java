package io.github.libtmux.junit5;

/** One pane in a {@link FakeWindow}. */
final class FakePane {
    final String id;
    final int index;
    final String command;
    final String directory;
    final long pid;
    String title = "";

    FakePane(String id, int index, String command, String directory, long pid) {
        this.id = id;
        this.index = index;
        this.command = command;
        this.directory = directory;
        this.pid = pid;
    }
}
