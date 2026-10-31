// Quarantines every Test task from the developer's own tmux.
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.security.MessageDigest
import java.util.HexFormat

tasks.withType<Test>().configureEach {
    // Quarantine every test from the developer's own tmux. A test is supposed to pass an explicit
    // -S, but nothing in the language enforces that, and a command that omits it silently addresses
    // a real server and can kill it. Two environment values decide where a bare client lands: tmux
    // resolves its default socket under TMUX_TMPDIR when it execs, and $TMUX takes precedence over
    // that for a client started inside a pane — which the Gradle daemon may well have been.
    // TMUX_TMPDIR is set per invocation in doFirst below, under the same root as the named sockets.
    environment.remove("TMUX")
    environment.remove("TMUX_PANE")

    // Where a test puts the sockets it names explicitly, which TMUX_TMPDIR above does not govern.
    // Sibling libtmux ports run on this machine at the same time and start their own tmux servers
    // under /tmp; a server of theirs left behind by an exited run holds a pty and answers to a name
    // this suite might have chosen, which turns their debris into this suite's intermittent
    // failures. So this port takes a root of its own, and everything a test creates lands under it.
    //
    // Short on purpose: a unix socket path cannot exceed about 104 bytes, which rules out the build
    // directory and is why this is not derived from one.
    val socketRoot = providers.gradleProperty("libtmuxSocketRoot").getOrElse("/tmp/libtmux-java-test")
    val checkoutIdentity = rootProject.rootDir.canonicalPath
    // This task's own path, :module:test, so each test task in one invocation has a quarantine of its
    // own and one module's leftover socket cannot fail the next module's tests.
    val taskIdentity: String = path
    systemProperty("java.io.tmpdir", socketRoot)
    doFirst {
        require(socketRoot.length <= 40) {
            "libtmuxSocketRoot is $socketRoot, too long to leave room for a socket under it"
        }
        // The quarantine shares the configured root, so overriding libtmuxSocketRoot moves the
        // bare-client sockets along with the named ones instead of splitting them across two roots.
        // Owner identity separates concurrent invocations; 16 hex digits leave AF_UNIX room.
        val quarantineIdentity = listOf(
            checkoutIdentity,
            taskIdentity,
            ProcessHandle.current().pid().toString(),
        ).joinToString("\u0000")
        val quarantineDigest = MessageDigest.getInstance("SHA-256")
            .digest(quarantineIdentity.toByteArray(StandardCharsets.UTF_8))
        val quarantineName = HexFormat.of().formatHex(quarantineDigest, 0, 8)
        val tmuxTmpDir = Path.of(socketRoot, quarantineName)

        if (Files.exists(tmuxTmpDir, LinkOption.NOFOLLOW_LINKS)) {
            require(Files.isDirectory(tmuxTmpDir, LinkOption.NOFOLLOW_LINKS)) {
                "tmux quarantine is not a directory: $tmuxTmpDir"
            }
            val entries = Files.walk(tmuxTmpDir).use { paths -> paths.toList() }
            val stale = entries.firstOrNull {
                it != tmuxTmpDir && !Files.isDirectory(it, LinkOption.NOFOLLOW_LINKS)
            }
            require(stale == null) {
                "tmux quarantine contains a stale entry: $stale"
            }
            entries.asReversed().filter { it != tmuxTmpDir }.forEach(Files::delete)
        }
        Files.createDirectories(tmuxTmpDir)
        environment("TMUX_TMPDIR", tmuxTmpDir.toString())
        File(socketRoot).mkdirs()
    }
}
