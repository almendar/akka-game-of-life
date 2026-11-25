package gameoflife.ui

import gameoflife.ClusterBootstrap
import java.io.{BufferedReader, InputStreamReader}
import java.net.{InetAddress, ServerSocket}
import java.nio.charset.StandardCharsets
import java.nio.file.Paths
import java.util.ArrayDeque
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.slf4j.LoggerFactory
import scala.collection.mutable
import scala.jdk.CollectionConverters.*

final class BackendProcessManager(
    seedPort: Int,
    onEvent: UiEvent => Unit,
    hostname: String = "127.0.0.1",
    maxLogLines: Int = 250,
    mainClass: String = "gameoflife.RunBackend"
):
  private val log     = LoggerFactory.getLogger(getClass)
  private val counter = new AtomicInteger(0)

  final private class ManagedProcess(
      var info: ManagedBackendInfo,
      val process: Process,
      val logs: ArrayDeque[String],
      var stopIntent: Option[String] = None
  )

  private val processes         = mutable.LinkedHashMap.empty[String, ManagedProcess]
  private var nextCandidatePort = seedPort + 1

  def startBackend(): Either[String, ManagedBackendInfo] =
    val (id, port) = this.synchronized {
      val allocatedPort = allocateFreePort()
      val backendId     = s"backend-${counter.incrementAndGet()}"
      (backendId, allocatedPort)
    }

    val javaBin   = Paths.get(System.getProperty("java.home"), "bin", "java").toString
    val classpath = System.getProperty("java.class.path")
    val command   = java.util.List.of(
      javaBin,
      "-Xms64m",
      "-Xmx256m",
      "-cp",
      classpath,
      s"-Dpekko.remote.artery.canonical.hostname=$hostname",
      "-Dpekko.cluster.split-brain-resolver.active-strategy=keep-oldest",
      "-Dpekko.cluster.split-brain-resolver.keep-oldest.down-if-alone=off",
      "-Dpekko.cluster.split-brain-resolver.stable-after=5s",
      "-Dpekko.cluster.sharding.rebalance-interval=3s",
      s"-Dgameoflife.parent.pid=${ProcessHandle.current().pid()}",
      mainClass,
      port.toString,
      seedPort.toString
    )

    try
      val pb = new ProcessBuilder(command)
      val _  = pb.redirectErrorStream(true)
      val p  = pb.start()

      val address = s"pekko://${ClusterBootstrap.SystemName}@$hostname:$port"
      val info    = ManagedBackendInfo(
        id = id,
        port = port,
        pid = p.pid(),
        status = "Starting",
        address = address,
        startedAtMs = System.currentTimeMillis(),
        exitCode = None
      )
      val managed = new ManagedProcess(info, p, new ArrayDeque[String](maxLogLines))
      this.synchronized {
        val _ = processes.put(id, managed)
      }

      startLogReader(id, managed)
      attachExitHandler(id, managed)

      log.info("Spawned {} JVM (pid={}, port={})", id, p.pid(), port)
      onEvent(UiEvent.ManagedBackendUpdated(info))
      onEvent(
        UiEvent.LogMessage(
          "cluster",
          s"Spawned child JVM $id (pid=${p.pid()}, port=$port)"
        )
      )
      Right(info)
    catch
      case ex: Exception =>
        log.error("Failed to spawn backend {}", id, ex)
        Left(s"Failed to start backend: ${ex.getMessage}")

  def stopBackend(id: String): Either[String, Unit] =
    this.synchronized {
      processes.get(id) match
        case None                                      => Left(s"Unknown backend '$id'")
        case Some(managed) if !managed.process.isAlive =>
          Left(s"Backend '$id' is already stopped")
        case Some(managed) =>
          managed.stopIntent = Some("Stopped")
          managed.info = managed.info.copy(status = "Stopping")
          val snapshot = managed.info
          managed.process.destroy()
          log.info("Sent SIGTERM to {} (pid={})", id, snapshot.pid)
          onEvent(UiEvent.ManagedBackendUpdated(snapshot))
          onEvent(
            UiEvent.LogMessage(
              "cluster",
              s"Sent SIGTERM (graceful leave) to $id (pid=${snapshot.pid}, port=${snapshot.port})"
            )
          )
          Right(())
    }

  def killBackend(id: String): Either[String, Unit] =
    this.synchronized {
      processes.get(id) match
        case None                                      => Left(s"Unknown backend '$id'")
        case Some(managed) if !managed.process.isAlive =>
          Left(s"Backend '$id' is already stopped")
        case Some(managed) =>
          managed.stopIntent = Some("Killed")
          managed.info = managed.info.copy(status = "Killing")
          val snapshot = managed.info
          val _        = managed.process.destroyForcibly()
          log.warn("Sent SIGKILL to {} (pid={})", id, snapshot.pid)
          onEvent(UiEvent.ManagedBackendUpdated(snapshot))
          onEvent(
            UiEvent.LogMessage(
              "cluster",
              s"Sent SIGKILL (hard crash) to $id (pid=${snapshot.pid}, port=${snapshot.port}) — SBR will down & rebalance"
            )
          )
          Right(())
    }

  def logsFor(id: String): Option[Vector[String]] =
    this.synchronized {
      processes.get(id).map { managed =>
        managed.logs.synchronized {
          managed.logs.iterator().asScala.toVector
        }
      }
    }

  def listBackends(): Vector[ManagedBackendInfo] =
    this.synchronized {
      processes.values.map(_.info).toVector
    }

  def stopAll(): Unit =
    val active = this.synchronized {
      processes.values.filter(_.process.isAlive).toVector
    }
    active.foreach { m =>
      m.stopIntent = Some("Stopped")
      m.process.destroy()
    }
    active.foreach { m =>
      try
        if !m.process.waitFor(2L, TimeUnit.SECONDS) then
          val _ = m.process.destroyForcibly()
      catch case _: InterruptedException => Thread.currentThread().interrupt()
    }

  private[gameoflife] def allocateFreePort(): Int =
    val usedPorts = processes.values.filter(_.process.isAlive).map(_.info.port).toSet
    var candidate = nextCandidatePort
    while candidate < 65000 do
      if !usedPorts.contains(candidate) && isPortAvailable(candidate) then
        nextCandidatePort = candidate + 1
        return candidate
      candidate += 1
    throw new IllegalStateException("No available TCP port found for backend node")

  private def isPortAvailable(port: Int): Boolean =
    var socket: ServerSocket = null
    try
      socket = new ServerSocket(port, 1, InetAddress.getByName(hostname))
      socket.setReuseAddress(true)
      true
    catch case _: Exception => false
    finally
      if socket != null then
        try socket.close()
        catch case _: Exception => ()

  private def startLogReader(id: String, managed: ManagedProcess): Unit =
    val readerThread = new Thread(
      () => {
        val reader = new BufferedReader(
          new InputStreamReader(managed.process.getInputStream, StandardCharsets.UTF_8)
        )
        try
          var line = reader.readLine()
          while line != null do
            managed.logs.synchronized {
              if managed.logs.size() >= maxLogLines then
                val _ = managed.logs.pollFirst()
              val _ = managed.logs.addLast(line)
            }
            if managed.info.status == "Starting" &&
              (line.contains("Welcome from") || line.contains("Started up successfully"))
            then
              val updated = this.synchronized {
                if managed.info.status == "Starting" then
                  managed.info = managed.info.copy(status = "Running")
                  Some(managed.info)
                else None
              }
              updated.foreach(u => onEvent(UiEvent.ManagedBackendUpdated(u)))
            line = reader.readLine()
        catch case _: Exception => ()
        finally
          try reader.close()
          catch case _: Exception => ()
      },
      s"backend-log-reader-$id"
    )
    readerThread.setDaemon(true)
    readerThread.start()

  private def attachExitHandler(id: String, managed: ManagedProcess): Unit =
    val _ = managed.process.onExit().thenAccept { exited =>
      val code    = exited.exitValue()
      val updated = this.synchronized {
        val finalStatus = managed.stopIntent.getOrElse(if code == 0 then "Stopped" else "Exited")
        managed.info = managed.info.copy(status = finalStatus, exitCode = Some(code))
        managed.info
      }
      log.info(
        "Backend {} (pid={}) exited with code {} ({})",
        id,
        updated.pid,
        code,
        updated.status
      )
      onEvent(UiEvent.ManagedBackendUpdated(updated))
      onEvent(
        UiEvent.LogMessage(
          "cluster",
          s"Child JVM $id (port=${updated.port}) ${updated.status} (exit=$code)"
        )
      )
    }
