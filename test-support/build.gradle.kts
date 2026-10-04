// What the test suites of several modules share. Not published.
plugins { id("libtmux.java-library") }

tasks.withType<Javadoc>().configureEach { enabled = false }
