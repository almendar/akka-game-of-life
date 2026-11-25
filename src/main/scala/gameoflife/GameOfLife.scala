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

  def formatBoard(epoch: Int, boardSize: BoardSize, states: Map[Position, CellState]): String =
    val border = "-" * (boardSize.width * 2 + 1)
    val rows   = (0 until boardSize.height).map { y =>
      val rowCells = (0 until boardSize.width).map { x =>
        states.getOrElse(Position(x, y), CellState.Dead).toInt
      }
      rowCells.mkString("[", ",", "]")
    }
    (Seq(s"At epoch:$epoch", border) ++ rows ++ Seq(border, "")).mkString("\n")
