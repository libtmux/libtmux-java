package io.github.libtmux.testsandbox;

import org.junit.platform.launcher.LauncherSession;
import org.junit.platform.launcher.LauncherSessionListener;

/**
 * Stops a launcher session whose process could reach a tmux server it was not given.
 *
 * <p>Registered through {@code ServiceLoader}, so it applies to every engine on the classpath and to
 * a run no Gradle task started.
 */
public final class SandboxSessionListener implements LauncherSessionListener {

    /** Called by the JUnit Platform. */
    public SandboxSessionListener() {}

    @Override
    public void launcherSessionOpened(LauncherSession session) {
        SandboxCheck.require(System.getenv(), System.getProperty(SandboxCheck.SANDBOX_PROPERTY));
    }
}
