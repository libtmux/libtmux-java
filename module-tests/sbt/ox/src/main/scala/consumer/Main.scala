package consumer

@main def run(): Unit =
  println("resolved " + io.github.libtmux.scaladsl.ox.Flows.getClass.getName)
