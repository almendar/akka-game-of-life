package gameoflife.ui

import java.io.{IOException, OutputStream}
import java.nio.charset.StandardCharsets
import java.util.concurrent.{CopyOnWriteArraySet, Executors, ScheduledExecutorService, TimeUnit}

final class UiEventHub(
    initialStatus: SimulationStatus,
    maxHistory: Int = 200,
    maxLogs: Int = 200
):
  final class SseClient(private val out: OutputStream):
    def send(frame: String): Boolean = this.synchronized {
      try
        out.write(frame.getBytes(StandardCharsets.UTF_8))
        out.flush()
        true
      catch case _: IOException => false
    }

    def closeQuietly(): Unit = this.synchronized {
      try out.close()
      catch case _: IOException => ()
    }

  private val clients                                      = new CopyOnWriteArraySet[SseClient]()
  private val keepaliveScheduler: ScheduledExecutorService =
    Executors.newSingleThreadScheduledExecutor { r =>
      val t = new Thread(r, "ui-sse-keepalive")
      t.setDaemon(true)
      t
    }

  private var status: SimulationStatus                         = initialStatus
  private var epochHistory: Vector[EpochSnapshot]              = Vector.empty
  private var clusterMembers: Map[String, ClusterMemberInfo]   = Map.empty
  private var managedBackends: Map[String, ManagedBackendInfo] = Map.empty
  private var eventLog: Vector[EventLogEntry]                  = Vector.empty
  private var nextLogId: Long                                  = 1L

  private val _ = keepaliveScheduler.scheduleAtFixedRate(
    () => broadcastRaw(": ping\n\n"),
    15L,
    15L,
    TimeUnit.SECONDS
  )

  def addClient(out: OutputStream): SseClient =
    val client = new SseClient(out)
    val _      = clients.add(client)
    val _      = client.send(": connected\n\n")
    client

  def removeClient(client: SseClient): Unit =
    val _ = clients.remove(client)
    client.closeQuietly()

  def publish(event: UiEvent): Unit =
    val framesToSend: Vector[(String, String)] = this.synchronized {
      event match
        case UiEvent.EpochCompleted(snapshot) =>
          if snapshot.simulationId != status.simulationId then Vector.empty
          else
            val filtered = epochHistory.filterNot(_.epoch == snapshot.epoch) :+ snapshot
            epochHistory =
              if filtered.size > maxHistory then filtered.takeRight(maxHistory) else filtered
            val aliveCount   = snapshot.cells.count(_.alive)
            val backendCount =
              snapshot.cells.map(_.nodeAddress).filter(_.nonEmpty).distinct.size
            val logEntry = appendLog(
              "epoch",
              s"Epoch ${snapshot.epoch}: $aliveCount/${snapshot.cells.size} alive across $backendCount backend(s)",
              snapshot.timestampMs
            )
            Vector(
              "epoch" -> UiJson.toJson(UiJson.encodeEpoch(snapshot)),
              "log"   -> UiJson.toJson(UiJson.encodeLogEntry(logEntry))
            )

        case UiEvent.SimulationStatusUpdated(newStatus) =>
          status = newStatus
          Vector("status" -> UiJson.toJson(UiJson.encodeSimulationStatus(newStatus)))

        case UiEvent.ClusterMemberUpdated(member) =>
          clusterMembers = clusterMembers + (member.address -> member)
          val memberFrame =
            "cluster-member" -> UiJson.toJson(UiJson.encodeClusterMember(member))
          if member.status == "Up" then
            managedBackends.values.find(b =>
              b.address == member.address && b.status == "Starting"
            ) match
              case Some(startingBackend) =>
                val promoted = startingBackend.copy(status = "Running")
                managedBackends = managedBackends + (promoted.id -> promoted)
                Vector(
                  memberFrame,
                  "backend-process" -> UiJson.toJson(UiJson.encodeManagedBackend(promoted))
                )
              case None =>
                Vector(memberFrame)
          else Vector(memberFrame)

        case UiEvent.ClusterMemberRemoved(address) =>
          clusterMembers = clusterMembers - address
          val obj = UiJson.mapper.createObjectNode()
          val _   = obj.put("address", address)
          Vector("cluster-member-removed" -> UiJson.toJson(obj))

        case UiEvent.ManagedBackendUpdated(backend) =>
          managedBackends = managedBackends + (backend.id -> backend)
          Vector("backend-process" -> UiJson.toJson(UiJson.encodeManagedBackend(backend)))

        case crash @ UiEvent.CellCrashedEvent(pos, epoch, nodeAddress, ts) =>
          val owner    = if nodeAddress.nonEmpty then s" on ${shortAddr(nodeAddress)}" else ""
          val logEntry = appendLog(
            "crash",
            s"Cell (${pos.x},${pos.y}) crashed at epoch $epoch$owner — restarting & replaying history",
            ts
          )
          Vector(
            "cell-crash" -> UiJson.toJson(UiJson.encodeCellCrash(crash)),
            "log"        -> UiJson.toJson(UiJson.encodeLogEntry(logEntry))
          )

        case UiEvent.SimulationReset(simId, spec) =>
          epochHistory = Vector.empty
          status = status.copy(simulationId = simId, boardSpec = spec, step = 0, crashCount = 0)
          val obj = UiJson.mapper.createObjectNode()
          val _   = obj.put("simulationId", simId)
          val _   = obj.set[com.fasterxml.jackson.databind.JsonNode](
            "boardSpec",
            UiJson.encodeBoardSpec(spec)
          )
          Vector("reset" -> UiJson.toJson(obj))

        case UiEvent.LogMessage(category, message, ts) =>
          val logEntry = appendLog(category, message, ts)
          Vector("log" -> UiJson.toJson(UiJson.encodeLogEntry(logEntry)))
    }

    framesToSend.foreach { case (eventName, json) =>
      broadcastRaw(s"event: $eventName\ndata: $json\n\n")
    }

  def fullStateJson(): String = this.synchronized {
    val root = UiJson.mapper.createObjectNode()
    val _    = root.set[com.fasterxml.jackson.databind.JsonNode](
      "status",
      UiJson.encodeSimulationStatus(status)
    )
    val _ = root.set[com.fasterxml.jackson.databind.JsonNode]("patterns", UiJson.encodePatterns())

    val membersArr = root.putArray("clusterMembers")
    clusterMembers.values.toVector
      .sortBy(_.address)
      .foreach { m =>
        val _ = membersArr.add(UiJson.encodeClusterMember(m))
      }

    val backendsArr = root.putArray("managedBackends")
    managedBackends.values.toVector
      .sortBy(_.port)
      .foreach { b =>
        val _ = backendsArr.add(UiJson.encodeManagedBackend(b))
      }

    val historyArr = root.putArray("epochHistory")
    epochHistory.foreach { e =>
      val _ = historyArr.add(UiJson.encodeEpoch(e))
    }

    val logsArr = root.putArray("eventLog")
    eventLog.foreach { l =>
      val _ = logsArr.add(UiJson.encodeLogEntry(l))
    }

    UiJson.toJson(root)
  }

  def historySnapshot(): Vector[EpochSnapshot] = this.synchronized(epochHistory)

  def currentStatus(): SimulationStatus = this.synchronized(status)

  def close(): Unit =
    val _  = keepaliveScheduler.shutdownNow()
    val it = clients.iterator()
    while it.hasNext do
      val client = it.next()
      client.closeQuietly()
    clients.clear()

  private def appendLog(category: String, message: String, timestampMs: Long): EventLogEntry =
    val entry = EventLogEntry(nextLogId, timestampMs, category, message)
    nextLogId += 1L
    val updated = eventLog :+ entry
    eventLog = if updated.size > maxLogs then updated.takeRight(maxLogs) else updated
    entry

  private def broadcastRaw(frame: String): Unit =
    val it = clients.iterator()
    while it.hasNext do
      val client = it.next()
      if !client.send(frame) then
        val _ = clients.remove(client)
        client.closeQuietly()

  private def shortAddr(addr: String): String =
    addr.lastIndexOf('@') match
      case idx if idx >= 0 => addr.substring(idx + 1)
      case _               => addr
