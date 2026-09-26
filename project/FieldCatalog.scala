/** One row of `META-INF/io.github.libtmux/field-catalog.tsv`: a queryable tmux
  * field or relation on `Pane_`/`Session_`/`Window_`/`Client_`. Parsed here
  * only for `name`/`owner`/`kind`/`relation`/`javadoc` — the Java field
  * descriptor a generated Scala `def` forwards to already encodes `tmuxFormat`,
  * `since` and `accessor`, so this generator never re-derives them.
  */
final case class FieldRow(
    name: String,
    owner: String,
    kind: String,
    relation: String,
    javadoc: String
)

object FieldCatalog {

  /** Parses the tab-separated field catalog: `#`-prefixed and blank lines are
    * comments — the column header is one of them (`# name\towner\t...`) — and
    * every other line is
    * `name\towner\tkind\ttmuxFormat\tsince\trelation\taccessor\tjavadoc`.
    */
  def parse(text: String): Vector[FieldRow] = {
    val rows = text
      .split("\n", -1)
      .toVector
      .map(_.stripLineEnd)
      .filter(line => line.nonEmpty && !line.startsWith("#"))
    rows.map { line =>
      val columns = line.split("\t", -1)
      require(
        columns.length == 8,
        "field-catalog.tsv row must have 8 tab-separated columns: " + line
      )
      FieldRow(
        name = columns(0),
        owner = columns(1),
        kind = columns(2),
        relation = columns(5),
        javadoc = columns(7)
      )
    }
  }
}
