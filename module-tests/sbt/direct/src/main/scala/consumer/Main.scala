package consumer

// The Java artifact from Scala, with a single `%`.
@main def run(): Unit =
  println("resolved " + io.github.libtmux.Pane_.id().getClass.getName)
