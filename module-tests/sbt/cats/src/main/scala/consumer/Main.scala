package consumer

@main def run(): Unit =
  println("resolved " + io.github.libtmux.scaladsl.cats.Server.getClass.getName)
