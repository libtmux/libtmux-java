// Keeps every Test task, and each JavaExec task that asks, off the developer's own tmux.
//
// Two things decide where a bare client lands: tmux resolves its default socket under TMUX_TMPDIR when
// it execs, and an inherited TMUX takes precedence over that for a client started inside a pane, which
// the Gradle daemon may well have been. A test is supposed to pass an explicit -S, but nothing in the
// language enforces it, and a command that omits it silently addresses a real server and can kill it.
//
// A JavaExec task is left alone unless it calls quarantineTmux: `run` and `runDevelopment` follow the
// developer's TMUX on purpose. When a quarantined task ends, even a failed one, a finalizer stops any
// server it left on a socket anywhere in its quarantine and fails the build naming the sockets.
//
// No plugins block, so module-tests, which resolve nothing from this build, apply this file by path.
import java.io.IOException
import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.channels.SocketChannel
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.PosixFilePermissions
import java.security.MessageDigest
import java.util.HexFormat
import java.util.concurrent.TimeUnit

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

/** Where a task's quarantine lives, which the root, the checkout, the task and this daemon decide. */
object QuarantineLocation {
    fun of(root: String, checkout: String, taskPath: String): Path {
        val identity = listOf(checkout, taskPath, ProcessHandle.current().pid().toString()).joinToString("\u0000")
        val digest = MessageDigest.getInstance("SHA-256").digest(identity.toByteArray(StandardCharsets.UTF_8))
        return Path.of(root, HexFormat.of().formatHex(digest, 0, 8))
    }
}

/** What a quarantined task left behind: servers it started and never stopped. */
object LeakedServers {
    /**
     * @param stopped the sockets whose server answered and then stopped answering
     * @param stuck the sockets whose server still answers after kill-server
     */
    class Swept(val stopped: List<String>, val stuck: List<String>)

    /**
     * Stops every server that answers on a socket anywhere under [directory], and removes [directory]
     * unless one still answers. Links are not followed, and a socket nothing answers on is not a leak.
     * Sockets are named by their path below [directory].
     */
    fun sweep(directory: Path, tmux: String): Swept {
        if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) return Swept(emptyList(), emptyList())
        val sockets = Files.walk(directory).use { paths -> paths.filter(::isSocket).toList() }
        val stopped = mutableListOf<String>()
        val stuck = mutableListOf<String>()
        for (socket in sockets.filter(::answers)) {
            kill(tmux, directory, socket)
            (if (stillAnswers(socket)) stuck else stopped).add(directory.relativize(socket).toString())
        }
        if (stuck.isEmpty()) {
            Files.walk(directory).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::delete) }
        }
        return Swept(stopped.sorted(), stuck.sorted())
    }

    // A socket file itself: a link to one is not, and is left for whoever owns what it points at.
    private fun isSocket(path: Path) =
        Files.readAttributes(path, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS).isOther

    private fun answers(socket: Path) = try {
        SocketChannel.open(StandardProtocolFamily.UNIX).use { it.connect(UnixDomainSocketAddress.of(socket)) }
        true
    } catch (nothingListens: IOException) {
        false
    } catch (tooLong: IllegalArgumentException) {
        false
    }

    // A server can take a moment to go once it has been told to.
    private fun stillAnswers(socket: Path): Boolean {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
        while (answers(socket)) {
            if (System.nanoTime() > deadline) return true
            Thread.sleep(50)
        }
        return false
    }

    private fun kill(tmux: String, tmpdir: Path, socket: Path) {
        val client = ProcessBuilder(tmux, "-S", socket.toString(), "kill-server")
            .redirectErrorStream(true)
            .redirectOutput(ProcessBuilder.Redirect.DISCARD)
        client.environment().keys.removeIf(ServerSelectors::select)
        client.environment()["TMUX_TMPDIR"] = tmpdir.toString()
        val process = try {
            client.start()
        } catch (cannotStart: IOException) {
            return
        }
        if (!process.waitFor(15, TimeUnit.SECONDS)) process.destroyForcibly()
    }
}

/**
 * Fails the build when a quarantined task left a server running, after stopping it.
 *
 * Plain values and a provider, like [PrepareQuarantine], so the configuration cache can store it.
 */
class CheckQuarantine(
    private val root: String,
    private val checkout: String,
    private val taskPath: String,
    private val tmux: Provider<String>,
) : Action<Task> {
    override fun execute(finalizer: Task) {
        val swept = LeakedServers.sweep(QuarantineLocation.of(root, checkout, taskPath), tmux.get())
        if (swept.stuck.isNotEmpty()) {
            throw GradleException(
                "$taskPath left a tmux server running: ${(swept.stopped + swept.stuck).sorted().joinToString(", ")}; " +
                    "${swept.stuck.joinToString(", ")} still answers after kill-server, so its quarantine was kept",
            )
        }
        if (swept.stopped.isNotEmpty()) {
            throw GradleException("$taskPath left a tmux server running: ${swept.stopped.joinToString(", ")}")
        }
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
        // What the test JVM's refusal compares TMUX_TMPDIR with.
        if (task is Test) task.systemProperty("libtmux.test.sandbox", directory.toString())
    }

    private fun directoryFor(taskPath: String): Path {
        require(root.length <= 40) { "libtmuxSocketRoot is $root, too long to leave room for a socket under it" }
        val directory = QuarantineLocation.of(root, checkout, taskPath)

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

// Registered from the build script and not while a task is being created, which Gradle refuses. The
// binary is read when the finalizer starts, because a matrix lane names it after this file has run.
fun checkAfter(taskName: String) {
    val taskPath = if (path == ":") ":$taskName" else "$path:$taskName"
    val configured = providers.gradleProperty("libtmuxTmux")
    val tmux = tasks.named(taskName).map {
        (it as? Test)?.systemProperties?.get("libtmux.tmux")?.toString() ?: configured.orNull ?: "tmux"
    }
    val check = tasks.register("${taskName}QuarantineCheck") {
        doLast(CheckQuarantine(socketRoot, checkoutIdentity, taskPath, tmux))
    }
    tasks.named(taskName) { finalizedBy(check) }
}

// For a process that is not a task, such as a providers.javaexec run from a task action.
extra["withoutServerSelectors"] = { options: ProcessForkOptions -> ServerSelectors.clear(options) }
extra["quarantineTmux"] = { run: TaskProvider<JavaExec> ->
    run.configure { quarantine() }
    checkAfter(run.name)
}
extra["stopLeakedServers"] = { directory: Path, tmux: String ->
    LeakedServers.sweep(directory, tmux).let { it.stopped to it.stuck }
}

tasks.withType<Test>().configureEach {
    systemProperty("java.io.tmpdir", socketRoot)
    quarantine()
}
afterEvaluate { tasks.withType<Test>().names.forEach(::checkAfter) }
