package io.github.libtmux.scaladsl.ox

import ox.*

/** One worked structured-concurrency scenario: two direct-style calls run
  * concurrently, and either failing cancels its sibling rather than leaking it.
  * Ox adds no new handle type — the same opaque `io.github.libtmux.scaladsl`
  * handles this facade already has work unchanged inside a `fork`.
  */
object Scenario {
  def awaitBothReady(
      awaitEditor: () => String,
      awaitLogs: () => String
  ): (String, String) =
    supervised {
      val editorReady = fork(awaitEditor())
      val logsReady = fork(awaitLogs())
      (editorReady.join(), logsReady.join())
    }
}
