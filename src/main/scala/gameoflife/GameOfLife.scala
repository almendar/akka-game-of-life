package gameoflife

import scala.util.Random

final case class Position(x: Int, y: Int):
  def entityId: String = s"$x,$y"

object Position:
  def fromEntityId(entityId: String): Position =
    entityId.split(",") match
      case Array(xStr, yStr) => Position(xStr.toInt, yStr.toInt)
      case _ => throw new IllegalArgumentException(s"Invalid cell entityId: '$entityId'")

final case class BoardSize(width: Int, height: Int):
  require(width > 0 && height > 0, s"Board dimensions must be positive, got ($width, $height)")

  def cellCount: Int = width * height

  def contains(pos: Position): Boolean =
    pos.x >= 0 && pos.x < width && pos.y >= 0 && pos.y < height

  def allPositions: Vector[Position] =
    (for
      y <- 0 until height
      x <- 0 until width
    yield Position(x, y)).toVector

  def neighbours(pos: Position): Vector[Position] =
    val offsets = Vector(-1, 0, 1)
    for
      dx <- offsets
      dy <- offsets
      if !(dx == 0 && dy == 0)
      candidate = Position(pos.x + dx, pos.y + dy)
      if contains(candidate)
    yield candidate

enum CellState:
  case Alive, Dead

  def isAlive: Boolean = this == CellState.Alive
  def toInt: Int       = if isAlive then 1 else 0

object CellState:
  def fromBoolean(alive: Boolean): CellState =
    if alive then Alive else Dead

enum BoardPattern(val id: String, val label: String, val recommendedSize: Option[BoardSize]):
  case Random     extends BoardPattern("random", "Random (Seeded)", None)
  case Glider     extends BoardPattern("glider", "Glider", Some(BoardSize(10, 10)))
  case Blinker    extends BoardPattern("blinker", "Blinker", Some(BoardSize(7, 7)))
  case Beacon     extends BoardPattern("beacon", "Beacon", Some(BoardSize(8, 8)))
  case Pulsar     extends BoardPattern("pulsar", "Pulsar", Some(BoardSize(17, 17)))
  case RPentomino extends BoardPattern("r-pentomino", "R-pentomino", Some(BoardSize(16, 16)))
  case GosperGliderGun
      extends BoardPattern("gosper-glider-gun", "Gosper Glider Gun", Some(BoardSize(38, 18)))

object BoardPattern:
  def fromId(id: String): BoardPattern =
    values
      .find(_.id.equalsIgnoreCase(id.trim))
      .getOrElse(Random)

  private val gliderCoords: Set[(Int, Int)] =
    Set((1, 0), (2, 1), (0, 2), (1, 2), (2, 2))

  private val blinkerCoords: Set[(Int, Int)] =
    Set((1, 0), (1, 1), (1, 2))

  private val beaconCoords: Set[(Int, Int)] =
    Set((0, 0), (1, 0), (0, 1), (3, 2), (2, 3), (3, 3))

  private val rPentominoCoords: Set[(Int, Int)] =
    Set((1, 0), (2, 0), (0, 1), (1, 1), (1, 2))

  private val pulsarCoords: Set[(Int, Int)] =
    val arms = Set(2, 3, 4, 8, 9, 10)
    val bars = Set(0, 5, 7, 12)
    (for b <- bars; a <- arms yield (a, b)) ++ (for b <- bars; a <- arms yield (b, a))

  private val gosperGliderGunCoords: Set[(Int, Int)] = Set(
    (0, 4),
    (0, 5),
    (1, 4),
    (1, 5),
    (10, 4),
    (10, 5),
    (10, 6),
    (11, 3),
    (11, 7),
    (12, 2),
    (12, 8),
    (13, 2),
    (13, 8),
    (14, 5),
    (15, 3),
    (15, 7),
    (16, 4),
    (16, 5),
    (16, 6),
    (17, 5),
    (20, 2),
    (20, 3),
    (20, 4),
    (21, 2),
    (21, 3),
    (21, 4),
    (22, 1),
    (22, 5),
    (24, 0),
    (24, 1),
    (24, 5),
    (24, 6),
    (34, 2),
    (34, 3),
    (35, 2),
    (35, 3)
  )

  private def patternTemplate(pattern: BoardPattern): Option[(Int, Int, Set[(Int, Int)])] =
    pattern match
      case Random          => None
      case Glider          => Some((3, 3, gliderCoords))
      case Blinker         => Some((3, 3, blinkerCoords))
      case Beacon          => Some((4, 4, beaconCoords))
      case Pulsar          => Some((13, 13, pulsarCoords))
      case RPentomino      => Some((3, 3, rPentominoCoords))
      case GosperGliderGun => Some((36, 9, gosperGliderGunCoords))

  def isAliveAt(pos: Position, boardSize: BoardSize, pattern: BoardPattern): Option[Boolean] =
    patternTemplate(pattern).map { case (patW, patH, coords) =>
      val offsetX = math.max(0, (boardSize.width - patW) / 2)
      val offsetY = math.max(0, (boardSize.height - patH) / 2)
      coords.contains((pos.x - offsetX, pos.y - offsetY))
    }

final case class BoardSpec(
    size: BoardSize,
    seed: Long,
    pattern: BoardPattern = BoardPattern.Random
)

final case class CellEntityId(
    position: Position,
    simulationId: Int = 0,
    boardSpec: Option[BoardSpec] = None
):
  def encode: String =
    boardSpec match
      case None if simulationId == 0 =>
        position.entityId
      case None =>
        s"${position.entityId}|$simulationId"
      case Some(spec) =>
        s"${position.entityId}|$simulationId|${spec.size.width}|${spec.size.height}|${spec.seed}|${spec.pattern.id}"

  def forPosition(pos: Position): String =
    copy(position = pos).encode

object CellEntityId:
  def decode(entityId: String): CellEntityId =
    entityId.split("\\|") match
      case Array(posPart) =>
        CellEntityId(Position.fromEntityId(posPart))
      case Array(posPart, simPart) =>
        CellEntityId(Position.fromEntityId(posPart), simPart.toInt)
      case Array(posPart, simPart, wStr, hStr, seedStr, patStr) =>
        val spec = BoardSpec(
          size = BoardSize(wStr.toInt, hStr.toInt),
          seed = seedStr.toLong,
          pattern = BoardPattern.fromId(patStr)
        )
        CellEntityId(Position.fromEntityId(posPart), simPart.toInt, Some(spec))
      case _ =>
        throw new IllegalArgumentException(s"Invalid cell entityId: '$entityId'")

object GameOfLife:
  /** Conway's Game of Life transition rule:
    *   - A live cell survives with 2 or 3 live neighbours; otherwise it dies.
    *   - A dead cell becomes alive with exactly 3 live neighbours; otherwise it stays dead.
    */
  def nextState(current: CellState, aliveNeighbours: Int): CellState =
    (current, aliveNeighbours) match
      case (CellState.Alive, 2 | 3) => CellState.Alive
      case (CellState.Dead, 3)      => CellState.Alive
      case _                        => CellState.Dead

  /** Deterministically derives a cell's epoch-0 state from its position and the board seed so cells
    * recreated after restarts or cluster rebalancing recover consistently.
    */
  def initialCellState(pos: Position, seed: Long): CellState =
    val mixed = seed ^ (pos.x.toLong * 0x9e3779b97f4a7c15L) ^ (pos.y.toLong * 0xbf58476d1ce4e5b9L)
    CellState.fromBoolean(new Random(mixed).nextBoolean())

  def initialCellState(pos: Position, spec: BoardSpec): CellState =
    BoardPattern.isAliveAt(pos, spec.size, spec.pattern) match
      case Some(alive) => CellState.fromBoolean(alive)
      case None        => initialCellState(pos, spec.seed)

  def formatBoard(epoch: Int, boardSize: BoardSize, states: Map[Position, CellState]): String =
    val border = "-" * (boardSize.width * 2 + 1)
    val rows   = (0 until boardSize.height).map { y =>
      val rowCells = (0 until boardSize.width).map { x =>
        states.getOrElse(Position(x, y), CellState.Dead).toInt
      }
      rowCells.mkString("[", ",", "]")
    }
    (Seq(s"At epoch:$epoch", border) ++ rows ++ Seq(border, "")).mkString("\n")
