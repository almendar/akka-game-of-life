package gameoflife

import gameoflife.ui.{CellSnapshot, EpochSnapshot}
import org.apache.pekko.actor.typed.Behavior
import org.apache.pekko.actor.typed.scaladsl.Behaviors
import org.slf4j.LoggerFactory
import scala.annotation.tailrec

object LoggerActor:
  sealed trait Command extends CborSerializable

  final case class CellStateReported(
      position: Position,
      alive: Boolean,
      epoch: Int,
      nodeAddress: String = "",
      simulationId: Int = 0
  ) extends Command

  final case class CellCrashed(
      position: Position,
      epoch: Int,
      nodeAddress: String = "",
      simulationId: Int = 0
  ) extends Command

  final case class Reset(
      boardSize: BoardSize,
      simulationId: Int
  ) extends Command

  private val boardLogger = LoggerFactory.getLogger("gameoflife.board")

  def apply(
      boardSize: BoardSize,
      onBoardRendered: (Int, String) => Unit = (_, _) => (),
      onEpochSnapshot: EpochSnapshot => Unit = _ => (),
      onCellCrashed: CellCrashed => Unit = _ => ()
  ): Behavior[Command] =
    active(
      boardSize = boardSize,
      simulationId = 0,
      pending = Map.empty,
      watermark = -1,
      loggedEpochs = Set.empty,
      onBoardRendered = onBoardRendered,
      onEpochSnapshot = onEpochSnapshot,
      onCellCrashed = onCellCrashed
    )

  private def active(
      boardSize: BoardSize,
      simulationId: Int,
      pending: Map[Int, Map[Position, (CellState, String)]],
      watermark: Int,
      loggedEpochs: Set[Int],
      onBoardRendered: (Int, String) => Unit,
      onEpochSnapshot: EpochSnapshot => Unit,
      onCellCrashed: CellCrashed => Unit
  ): Behavior[Command] =
    Behaviors.receiveMessage {
      case CellStateReported(position, alive, epoch, nodeAddress, msgSimId) =>
        if msgSimId != simulationId || epoch <= watermark || loggedEpochs.contains(
            epoch
          ) || !boardSize
            .contains(position)
        then Behaviors.same
        else
          val cellState          = CellState.fromBoolean(alive)
          val updatedEpochStates =
            pending.getOrElse(epoch, Map.empty) + (position -> (cellState, nodeAddress))

          if updatedEpochStates.size == boardSize.cellCount then
            val stateOnly = updatedEpochStates.view.mapValues(_._1).toMap
            val rendered  = GameOfLife.formatBoard(epoch, boardSize, stateOnly)
            boardLogger.info(rendered)
            onBoardRendered(epoch, rendered)

            val orderedCells = boardSize.allPositions.map { pos =>
              val (st, addr) = updatedEpochStates.getOrElse(pos, (CellState.Dead, ""))
              CellSnapshot(pos.x, pos.y, st.isAlive, addr)
            }
            onEpochSnapshot(
              EpochSnapshot(
                simulationId = simulationId,
                epoch = epoch,
                width = boardSize.width,
                height = boardSize.height,
                timestampMs = System.currentTimeMillis(),
                cells = orderedCells
              )
            )

            val (nextWatermark, nextLogged) = advanceWatermark(watermark, loggedEpochs + epoch)
            active(
              boardSize,
              simulationId,
              pending - epoch,
              nextWatermark,
              nextLogged,
              onBoardRendered,
              onEpochSnapshot,
              onCellCrashed
            )
          else
            active(
              boardSize,
              simulationId,
              pending + (epoch -> updatedEpochStates),
              watermark,
              loggedEpochs,
              onBoardRendered,
              onEpochSnapshot,
              onCellCrashed
            )

      case crash: CellCrashed =>
        if crash.simulationId == simulationId then onCellCrashed(crash)
        Behaviors.same

      case Reset(newBoardSize, newSimulationId) =>
        active(
          boardSize = newBoardSize,
          simulationId = newSimulationId,
          pending = Map.empty,
          watermark = -1,
          loggedEpochs = Set.empty,
          onBoardRendered = onBoardRendered,
          onEpochSnapshot = onEpochSnapshot,
          onCellCrashed = onCellCrashed
        )
    }

  @tailrec
  private def advanceWatermark(watermark: Int, logged: Set[Int]): (Int, Set[Int]) =
    val next = watermark + 1
    if logged.contains(next) then advanceWatermark(next, logged - next)
    else (watermark, logged)
