package io.github.libtmux.kotlin

import io.github.libtmux.ServerConfig
import io.github.libtmux.SessionId
import io.github.libtmux.control.ControlClient
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions

/** Control clients backed by a shell script that speaks just enough of tmux's control protocol. */
internal object FakeControlClients {

    /** A fake control client whose process blasts [count] `%output` lines once it is signalled to. */
    fun bursty(directory: Path, count: Int): ControlClient {
        val fake = directory.resolve("tmux")
        Files.writeString(
            fake,
            "#!/bin/sh\n$PRELUDE" +
                """
                read_request
                answer
                read_request
                i=0
                while [ "${'$'}i" -lt $count ]; do
                    printf '%%output %%1 e%s\n' "${'$'}i"
                    i=${'$'}((i+1))
                done
                answer
                sleep 2
                """.trimIndent() +
                "\n",
        )
        Files.setPosixFilePermissions(fake, PosixFilePermissions.fromString("rwx------"))
        val config = ServerConfig.builder().binary(fake.toString()).build()
        return ControlClient.attachUnfenced(config, SessionId("\$0"))
    }

    /**
     * What every fixture in this file starts with. tmux follows each request line with a marker
     * line, a `display-message -p` of a token, and a reply ends with the marker's block:
     * `read_request` reads a request and its marker, and `answer` writes a reply block, flagged as
     * this client's command, then the marker's block.
     */
    private val PRELUDE =
        "printf '%%begin 100 1 0\\n%%end 100 1 0\\n'\n" +
            "read_request() { IFS= read -r request && IFS= read -r marker; }\n" +
            "answer() {\n" +
            "  printf '%%begin 1 1 1\\n'\n" +
            "  for line in \"\$@\"; do printf '%s\\n' \"\$line\"; done\n" +
            "  printf '%%end 1 1 1\\n'\n" +
            "  token=\${marker##*\"' '\"}\n" +
            "  printf '%%begin 1 2 1\\n%s\\n%%end 1 2 1\\n' \"\${token%\"'\"}\"\n" +
            "}\n"
}
