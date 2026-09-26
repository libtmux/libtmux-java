// A javadoc Doclet that reads :libtmux's own sources and emits operation-catalog.json (see
// libtmux/build.gradle.kts, generateOperationCatalog). Never published, never depends on
// :libtmux: it classifies operations by annotation name, not by loading the annotation's class.
plugins { id("libtmux.java-library") }

tasks.withType<Javadoc>().configureEach { enabled = false }
