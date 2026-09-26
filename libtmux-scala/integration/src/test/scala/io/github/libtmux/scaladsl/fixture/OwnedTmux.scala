package io.github.libtmux.scaladsl.fixture

import io.github.libtmux.{Server, ServerConfig, ServerEndpoint, SessionSpec}
import io.github.libtmux.junit5.NamedServerFixture
import java.nio.file.{Files, LinkOption, Path}
import java.time.Duration
import java.util.concurrent.TimeUnit
import scala.collection.mutable.ArrayBuffer
import scala.jdk.CollectionConverters._
import scala.util.Using

/** Owns one isolated daemon and every client explicitly registered with it. */
private[scaladsl] final class OwnedTmux private (
    val directory: Path,
    val config: ServerConfig,
    private val baselineWorkers: Set[Long],
    private val baselineChildren: Set[Long]
) extends AutoCloseable {
  val socket: Path = directory.resolve("s")
  val server: Server = Server.open(config)
  private val resources = ArrayBuffer.empty[AutoCloseable]
  private var owner: Option[NamedServerFixture] = None
  private var process: Option[ProcessHandle] = None
  private var closed = false

  def serverProcess: ProcessHandle =
    process.getOrElse(
      throw new IllegalStateException("fixture has not started")
    )

  def own[A <: AutoCloseable](resource: A): A = synchronized {
    require(!closed, "fixture is closed")
    resources += resource
    resource
  }

  private def start(): Unit = {
    server.newSession(
      SessionSpec.builder().named("fixture").running("cat").build()
    )
    owner = Some(NamedServerFixture.own(server, socket, directory))
    val pid =
      server.cmd("display-message", "-p", "#{pid}").stdout().get(0).toLong
    process = Some(ProcessHandle.of(pid).orElseThrow())
  }

  override def close(): Unit = synchronized {
    if (closed) return
    closed = true
    val descendants = process.toVector.flatMap(OwnedTmux.descendants)
    var endpointReleased = false
    var failure: Throwable = null
    def attempt(body: => Unit): Unit =
      try body
      catch {
        case problem: Throwable =>
          if (failure == null) failure = problem
          else failure.addSuppressed(problem)
      }

    val interrupted = Thread.interrupted()
    try {
      resources.reverseIterator.foreach(resource => attempt(resource.close()))
      attempt {
        if (owner.isEmpty && Files.exists(socket))
          owner = Some(NamedServerFixture.own(server, socket, directory))
        owner.foreach(_.close())
        endpointReleased = true
      }
      if (
        !sys.props
          .get("libtmux.scala.fixture.mutant")
          .contains("omit-client-close")
      )
        attempt {
          server.close()
          OwnedTmux.awaitWorkers(baselineWorkers)
        }
      attempt {
        descendants.filter(_.isAlive).foreach(_.destroy())
        (process.toVector ++ descendants).foreach(OwnedTmux.awaitExit)
      }
      attempt(OwnedTmux.assertReleased(baselineWorkers, baselineChildren))
      if (
        endpointReleased && process.forall(!_.isAlive) && !Files.exists(socket)
      )
        attempt(OwnedTmux.deleteDirectory(directory))
    } finally {
      // The mutation must prove the census fails without leaving live workers.
      attempt {
        server.close()
        OwnedTmux.awaitWorkers(baselineWorkers)
      }
      if (interrupted) Thread.currentThread().interrupt()
    }
    if (failure != null) throw failure
  }
}

private[scaladsl] object OwnedTmux {
  private val Root = Path.of("/tmp/libtmux-java-test")

  def binary: String = {
    val selected = sys.env.getOrElse(
      "TMUX_TEST_BINARY",
      throw new IllegalStateException(
        "TMUX_TEST_BINARY must name an explicit executable"
      )
    )
    val path = Path.of(selected)
    require(
      path.isAbsolute && Files.isExecutable(path),
      "TMUX_TEST_BINARY must be absolute and executable"
    )
    path.toString
  }

  def open(): OwnedTmux = {
    assertForkedJdk()
    acquire(binary)(_ => ())
  }

  def acquire(binary: String)(afterAcquired: OwnedTmux => Unit): OwnedTmux = {
    require(
      !sys.env.contains("TMUX") && !sys.env.contains("TMUX_PANE"),
      "clear TMUX and TMUX_PANE before starting the test JVM"
    )
    val workers = workerThreads.map(_.threadId()).toSet
    val children = childProcesses.map(_.pid()).toSet
    Files.createDirectories(Root)
    val directory = Files.createTempDirectory(
      Root,
      "scala-" + ProcessHandle.current().pid() + "-"
    )
    var allocated: Option[OwnedTmux] = None
    try {
      val configFile = directory.resolve("tmux.conf")
      Files.writeString(configFile, "set -g default-shell /bin/sh\n")
      val config = ServerConfig
        .builder()
        .binary(binary)
        .endpoint(ServerEndpoint.socketPath(directory.resolve("s")))
        .configFile(configFile)
        .build()
      val fixture = new OwnedTmux(directory, config, workers, children)
      allocated = Some(fixture)
      fixture.start()
      afterAcquired(fixture)
      fixture
    } catch {
      case failure: Throwable =>
        try
          allocated match {
            case Some(fixture) => fixture.close()
            case None          => deleteDirectory(directory)
          }
        catch { case cleanup: Throwable => failure.addSuppressed(cleanup) }
        throw failure
    }
  }

  def use[A](body: OwnedTmux => A): A = Using.resource(open())(body)

  private def descendants(process: ProcessHandle): Vector[ProcessHandle] =
    Using.resource(process.descendants())(_.iterator().asScala.toVector)

  private def childProcesses: Vector[ProcessHandle] =
    Using.resource(ProcessHandle.current().children())(
      _.iterator().asScala.toVector
    )

  private def workerThreads: Vector[Thread] =
    Thread.getAllStackTraces
      .keySet()
      .asScala
      .iterator
      .filter(thread => thread.isAlive && thread.getName.startsWith("libtmux-"))
      .toVector

  private def assertForkedJdk(): Unit = {
    val selected = sys.env.getOrElse(
      "LIBTMUX_SCALA_EXPECTED_JAVA_HOME",
      sys.env.getOrElse(
        "JAVA_HOME",
        throw new IllegalStateException("JAVA_HOME must select the test JDK")
      )
    )
    val expected = Path.of(selected).toRealPath()
    val actual = Path.of(System.getProperty("java.home")).toRealPath()
    assert(
      actual.equals(expected),
      "forked JVM differs from selected JDK: " + actual + " != " + expected
    )
  }

  private def awaitExit(process: ProcessHandle): Unit = {
    if (process.isAlive) process.onExit().get(10, TimeUnit.SECONDS)
    assert(!process.isAlive, "owned process survived: " + process.pid())
  }

  private def awaitWorkers(workersBefore: Set[Long]): Unit =
    workerThreads
      .filterNot(thread => workersBefore(thread.threadId()))
      .foreach { thread =>
        assert(
          thread.join(Duration.ofSeconds(10)),
          "libtmux worker did not exit: " + thread.getName
        )
      }

  private def deleteDirectory(directory: Path): Unit = {
    require(
      Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS),
      "fixture directory was replaced"
    )
    Using.resource(Files.walk(directory)) { paths =>
      paths
        .iterator()
        .asScala
        .toVector
        .sortBy(_.getNameCount)
        .reverse
        .foreach(Files.delete)
    }
  }

  private def assertReleased(
      workersBefore: Set[Long],
      childrenBefore: Set[Long]
  ): Unit = {
    val workers =
      workerThreads.filterNot(thread => workersBefore(thread.threadId()))
    assert(
      workers.isEmpty,
      "libtmux workers survived: " + workers.map(_.getName).mkString(", ")
    )
    val children = childProcesses.filter(process =>
      process.isAlive && !childrenBefore(process.pid())
    )
    assert(
      children.isEmpty,
      "client processes survived: " + children.map(_.pid()).mkString(", ")
    )
  }
}
