package gameoflife.ui

import com.fasterxml.jackson.databind.{JsonNode, ObjectMapper}
import com.fasterxml.jackson.databind.node.{ArrayNode, ObjectNode}
import gameoflife.{BoardPattern, BoardSpec, Position}

final case class CellSnapshot(
    x: Int,
    y: Int,
    alive: Boolean,
    nodeAddress: String
)

final case class EpochSnapshot(
    simulationId: Int,
    epoch: Int,
    width: Int,
    height: Int,
    timestampMs: Long,
    cells: Vector[CellSnapshot]
)

final case class ClusterMemberInfo(
    address: String,
    roles: Set[String],
    status: String,
    reachable: Boolean
)

final case class ManagedBackendInfo(
    id: String,
    port: Int,
    pid: Long,
    status: String,
    address: String,
    startedAtMs: Long,
    exitCode: Option[Int]
)

final case class SimulationStatus(
    simulationId: Int,
    boardSpec: BoardSpec,
    started: Boolean,
    paused: Boolean,
    step: Int,
    tickMs: Long,
    autoCrashEnabled: Boolean,
    autoCrashEveryMs: Long,
    crashCount: Int
)

final case class EventLogEntry(
    id: Long,
    timestampMs: Long,
    category: String,
    message: String
)

sealed trait UiEvent
object UiEvent:
  final case class EpochCompleted(snapshot: EpochSnapshot)            extends UiEvent
  final case class SimulationStatusUpdated(status: SimulationStatus)  extends UiEvent
  final case class ClusterMemberUpdated(member: ClusterMemberInfo)    extends UiEvent
  final case class ClusterMemberRemoved(address: String)              extends UiEvent
  final case class ManagedBackendUpdated(backend: ManagedBackendInfo) extends UiEvent
  final case class CellCrashedEvent(
      position: Position,
      epoch: Int,
      nodeAddress: String,
      timestampMs: Long
  ) extends UiEvent
  final case class SimulationReset(simulationId: Int, boardSpec: BoardSpec) extends UiEvent
  final case class LogMessage(
      category: String,
      message: String,
      timestampMs: Long = System.currentTimeMillis()
  ) extends UiEvent

object UiJson:
  val mapper: ObjectMapper = new ObjectMapper()

  def parseObject(body: String): JsonNode =
    if body == null || body.trim.isEmpty then mapper.createObjectNode()
    else mapper.readTree(body)

  def encodeCell(c: CellSnapshot): ObjectNode =
    val obj = mapper.createObjectNode()
    val _   = obj.put("x", c.x)
    val _   = obj.put("y", c.y)
    val _   = obj.put("alive", c.alive)
    val _   = obj.put("nodeAddress", c.nodeAddress)
    obj

  def encodeEpoch(e: EpochSnapshot): ObjectNode =
    val obj   = mapper.createObjectNode()
    val _     = obj.put("simulationId", e.simulationId)
    val _     = obj.put("epoch", e.epoch)
    val _     = obj.put("width", e.width)
    val _     = obj.put("height", e.height)
    val _     = obj.put("timestampMs", e.timestampMs)
    val cells = obj.putArray("cells")
    e.cells.foreach { c =>
      val _ = cells.add(encodeCell(c))
    }
    obj

  def encodeClusterMember(m: ClusterMemberInfo): ObjectNode =
    val obj   = mapper.createObjectNode()
    val _     = obj.put("address", m.address)
    val _     = obj.put("status", m.status)
    val _     = obj.put("reachable", m.reachable)
    val roles = obj.putArray("roles")
    m.roles.toVector.sorted.foreach { r =>
      val _ = roles.add(r)
    }
    obj

  def encodeManagedBackend(b: ManagedBackendInfo): ObjectNode =
    val obj = mapper.createObjectNode()
    val _   = obj.put("id", b.id)
    val _   = obj.put("port", b.port)
    val _   = obj.put("pid", b.pid)
    val _   = obj.put("status", b.status)
    val _   = obj.put("address", b.address)
    val _   = obj.put("startedAtMs", b.startedAtMs)
    b.exitCode match
      case Some(code) => val _ = obj.put("exitCode", code)
      case None       => val _ = obj.putNull("exitCode")
    obj

  def encodeBoardSpec(spec: BoardSpec): ObjectNode =
    val obj = mapper.createObjectNode()
    val _   = obj.put("width", spec.size.width)
    val _   = obj.put("height", spec.size.height)
    val _   = obj.put("seed", spec.seed)
    val _   = obj.put("pattern", spec.pattern.id)
    obj

  def encodeSimulationStatus(s: SimulationStatus): ObjectNode =
    val obj = mapper.createObjectNode()
    val _   = obj.put("simulationId", s.simulationId)
    val _   = obj.set[JsonNode]("boardSpec", encodeBoardSpec(s.boardSpec))
    val _   = obj.put("started", s.started)
    val _   = obj.put("paused", s.paused)
    val _   = obj.put("step", s.step)
    val _   = obj.put("tickMs", s.tickMs)
    val _   = obj.put("autoCrashEnabled", s.autoCrashEnabled)
    val _   = obj.put("autoCrashEveryMs", s.autoCrashEveryMs)
    val _   = obj.put("crashCount", s.crashCount)
    obj

  def encodeLogEntry(e: EventLogEntry): ObjectNode =
    val obj = mapper.createObjectNode()
    val _   = obj.put("id", e.id)
    val _   = obj.put("timestampMs", e.timestampMs)
    val _   = obj.put("category", e.category)
    val _   = obj.put("message", e.message)
    obj

  def encodePatterns(): ArrayNode =
    val arr = mapper.createArrayNode()
    BoardPattern.values.foreach { p =>
      val obj = mapper.createObjectNode()
      val _   = obj.put("id", p.id)
      val _   = obj.put("label", p.label)
      p.recommendedSize match
        case Some(sz) =>
          val _ = obj.put("recommendedWidth", sz.width)
          val _ = obj.put("recommendedHeight", sz.height)
        case None =>
          val _ = obj.putNull("recommendedWidth")
          val _ = obj.putNull("recommendedHeight")
      val _ = arr.add(obj)
    }
    arr

  def encodeCellCrash(e: UiEvent.CellCrashedEvent): ObjectNode =
    val obj = mapper.createObjectNode()
    val _   = obj.put("x", e.position.x)
    val _   = obj.put("y", e.position.y)
    val _   = obj.put("epoch", e.epoch)
    val _   = obj.put("nodeAddress", e.nodeAddress)
    val _   = obj.put("timestampMs", e.timestampMs)
    obj

  def encodeError(message: String): String =
    val obj = mapper.createObjectNode()
    val _   = obj.put("error", message)
    mapper.writeValueAsString(obj)

  def encodeOk(message: String = "ok"): String =
    val obj = mapper.createObjectNode()
    val _   = obj.put("status", message)
    mapper.writeValueAsString(obj)

  def encodeLogs(id: String, lines: Vector[String]): String =
    val obj = mapper.createObjectNode()
    val _   = obj.put("id", id)
    val arr = obj.putArray("lines")
    lines.foreach { line =>
      val _ = arr.add(line)
    }
    mapper.writeValueAsString(obj)

  def toJson(node: JsonNode): String =
    mapper.writeValueAsString(node)
