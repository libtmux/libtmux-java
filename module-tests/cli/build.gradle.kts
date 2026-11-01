// Runs tmux-workspace from the staged jar and what its POM names, nothing else: a runtime dependency
// the POM leaves out fails here as a missing class, not in a user's launcher.
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.createTempDirectory

plugins { java }

// A build of its own, so the quarantine is applied by path rather than as a plugin.
apply(from = rootProject.file("../../build-logic/conventions/src/main/kotlin/libtmux.tmux-quarantine.gradle.kts"))
@Suppress("UNCHECKED_CAST")
val withoutServerSelectors = extra["withoutServerSelectors"] as (ProcessForkOptions) -> Unit
@Suppress("UNCHECKED_CAST")
val stopLeakedServers = extra["stopLeakedServers"] as (Path, String) -> Pair<List<String>, List<String>>

// The version the root build staged. Required, so a launcher that resolved some other release
// cannot pass for this one.
val libtmuxVersion = providers.gradleProperty("libtmuxVersion").get()

dependencies { runtimeOnly("io.github.libtmux:libtmux-workspace-cli:$libtmuxVersion") }

tasks.register("run") {
    group = "verification"
    description = "Loads a workspace with the staged tmux-workspace, then freezes it back."
    val classpath = configurations.runtimeClasspath
    val workspace = layout.projectDirectory.file("workspace.yaml").asFile
    doLast {
        val directory = createTempDirectory(File("/tmp/libtmux-java-test").toPath().createDirectories(), "cli-")
        val socket = directory.resolve("s").toString()
        // A client given no -S would otherwise follow the caller's TMUX, or its default socket.
        val tmuxTmpDir = directory.resolve("t").createDirectories().toString()
        fun cli(vararg arguments: String): String {
            val ran = providers.javaexec {
                classpath(classpath)
                mainClass = "io.github.libtmux.workspace.cli.Main"
                args(*arguments)
                isIgnoreExitValue = true
                withoutServerSelectors(this)
                environment("TMUX_TMPDIR", tmuxTmpDir)
            }
            val status = ran.result.get().exitValue
            check(status == 0) {
                "tmux-workspace ${arguments.joinToString(" ")} exited $status: ${ran.standardError.asText.get()}"
            }
            return ran.standardOutput.asText.get()
        }
        var failure: Throwable? = null
        try {
            val info = cli("debug-info", "--json")
            check("\"version\":\"$libtmuxVersion\"" in info) { "not the staged $libtmuxVersion: $info" }
            cli("load", workspace.path, "-S", socket, "-d", "--json")
            val frozen = cli("freeze", "staged-cli", "-S", socket, "--json")
            check("\"session_name\":\"staged-cli\"" in frozen) { "freeze lost the session: $frozen" }
            println("staged tmux-workspace $libtmuxVersion loaded and froze a workspace")
        } catch (thrown: Throwable) {
            failure = thrown
            throw thrown
        } finally {
            providers.exec {
                commandLine("tmux", "-S", socket, "kill-server")
                isIgnoreExitValue = true
            }.result.get()
            val (stopped, stuck) = stopLeakedServers(Path.of(tmuxTmpDir), "tmux")
            // Kept while a server still answers, so it can be found and ended.
            if (stuck.isEmpty()) directory.toFile().deleteRecursively()
            val left = (stopped + stuck).sorted()
            if (left.isNotEmpty()) {
                val leak = IllegalStateException("tmux-workspace left a tmux server running: ${left.joinToString(", ")}")
                // The failure that ended the run stays the one reported.
                failure?.addSuppressed(leak) ?: throw leak
            }
        }
    }
}
