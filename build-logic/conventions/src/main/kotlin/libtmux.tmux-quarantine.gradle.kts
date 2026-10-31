// Keeps every Test task, and each JavaExec task that asks, off the developer's own tmux.
//
// Two things decide where a bare client lands: tmux resolves its default socket under TMUX_TMPDIR when
// it execs, and an inherited TMUX takes precedence over that for a client started inside a pane, which
// the Gradle daemon may well have been. A test is supposed to pass an explicit -S, but nothing in the
// language enforces it, and a command that omits it silently addresses a real server and can kill it.
//
// A JavaExec task is left alone unless it calls quarantineTmux: `run` and `runDevelopment` follow the
// developer's TMUX on purpose. No plugins block, so module-tests, which resolve nothing from this
// build, apply this file by path and opt their runs in.
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.security.MessageDigest
import java.util.HexFormat

// Where a test puts the sockets it names explicitly, which TMUX_TMPDIR does not govern. Sibling
// libtmux ports run on this machine at the same time and start their own tmux servers under /tmp; a
// server of theirs left behind by an exited run holds a pty and answers to a name this suite might
// have chosen, which turns their debris into this suite's intermittent failures. So this port takes a
// root of its own, and everything a task creates lands under it.
//
// Short on purpose: a unix socket path cannot exceed about 104 bytes, which rules out the build
// directory and is why this is not derived from one.
val socketRoot = providers.gradleProperty("libtmuxSocketRoot").getOrElse("/tmp/libtmux-java-test")
val checkoutIdentity = rootProject.rootDir.canonicalPath

/** The variables that choose a server for a client that names none. */
object ServerSelectors {
    fun select(name: String) = name == "TMUX" || name == "TMUX_PANE" || name.startsWith("LIBTMUX_SOCKET")

    // Replaced rather than edited: removing from the map a providers.javaexec spec returns does not
    // reach the child.
    fun clear(options: ProcessForkOptions) {
        options.setEnvironment(options.environment.filterKeys { !select(it) })
    }
}

/**
 * Gives a task its quarantine as it starts.
 *
 * Plain values and no reference to this script, so the configuration cache can store it. The task's
 * own path keeps one module's leftover socket from failing the next module's tests, and the owner
 * identity keeps concurrent invocations apart. 16 hex digits leave AF_UNIX room.
 */
class PrepareQuarantine(private val root: String, private val checkout: String) : Action<Task> {
    private val ownerOnly = PosixFilePermissions.fromString("rwx------")

    override fun execute(task: Task) {
        val directory = directoryFor(task.path)
        val options = task as ProcessForkOptions
        ServerSelectors.clear(options)
        options.environment("TMUX_TMPDIR", directory.toString())
    }

    private fun directoryFor(taskPath: String): Path {
        require(root.length <= 40) { "libtmuxSocketRoot is $root, too long to leave room for a socket under it" }
        val identity = listOf(checkout, taskPath, ProcessHandle.current().pid().toString()).joinToString("\u0000")
        val digest = MessageDigest.getInstance("SHA-256").digest(identity.toByteArray(StandardCharsets.UTF_8))
        val directory = Path.of(root, HexFormat.of().formatHex(digest, 0, 8))

        Files.createDirectories(Path.of(root))
        if (Files.exists(directory, LinkOption.NOFOLLOW_LINKS)) {
            require(Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) {
                "tmux quarantine is not a directory: $directory"
            }
            val entries = Files.walk(directory).use { paths -> paths.toList() }
            val stale = entries.firstOrNull { it != directory && !Files.isDirectory(it, LinkOption.NOFOLLOW_LINKS) }
            require(stale == null) { "tmux quarantine contains a stale entry: $stale" }
            entries.asReversed().filter { it != directory }.forEach(Files::delete)
            Files.setPosixFilePermissions(directory, ownerOnly)
        } else {
            Files.createDirectory(directory, PosixFilePermissions.asFileAttribute(ownerOnly))
        }
        return directory
    }
}

fun <T> T.quarantine() where T : Task, T : ProcessForkOptions {
    ServerSelectors.clear(this)
    doFirst(PrepareQuarantine(socketRoot, checkoutIdentity))
}

// For a process that is not a task, such as a providers.javaexec run from a task action.
extra["withoutServerSelectors"] = { options: ProcessForkOptions -> ServerSelectors.clear(options) }
extra["quarantineTmux"] = { run: TaskProvider<JavaExec> -> run.configure { quarantine() } }

tasks.withType<Test>().configureEach {
    systemProperty("java.io.tmpdir", socketRoot)
    quarantine()
}
