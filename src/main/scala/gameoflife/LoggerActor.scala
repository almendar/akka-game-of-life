package gameoflife

import org.apache.pekko.actor.typed.Behavior
import org.apache.pekko.actor.typed.scaladsl.Behaviors
import org.slf4j.LoggerFactory
import scala.annotation.tailrec

object LoggerActor:
  sealed trait Command extends CborSerializable

  final case class CellStateReported(
      position: Position,
      alive: Boolean,
      epoch: Int
  ) extends Command

  private val boardLogger = LoggerFactory.getLogger("gameoflife.board")

  def apply(
      boardSize: BoardSize,
      onBoardRendered: (Int, String) => Unit = (_, _) => ()
  ): Behavior[Command] =
    active(
      boardSize = boardSize,
      pending = Map.empty,
      watermark = -1,
      loggedEpochs = Set.empty,
      onBoardRendered = onBoardRendered
    )

  private def active(
      boardSize: BoardSize,
      pending: Map[Int, Map[Position, CellState]],
      watermark: Int,
      loggedEpochs: Set[Int],
      onBoardRendered: (Int, String) => Unit
  ): Behavior[Command] =
    Behaviors.receiveMessage { case CellStateReported(position, alive, epoch) =>
      if epoch <= watermark || loggedEpochs.contains(epoch) || !boardSize.contains(position) then
        Behaviors.same
      else
        val updatedEpochStates =
          pending.getOrElse(epoch, Map.empty) + (position -> CellState.fromBoolean(alive))

        if updatedEpochStates.size == boardSize.cellCount then
          val rendered = GameOfLife.formatBoard(epoch, boardSize, updatedEpochStates)
          boardLogger.info(rendered)
          onBoardRendered(epoch, rendered)
          val (nextWatermark, nextLogged) = advanceWatermark(watermark, loggedEpochs + epoch)
          active(boardSize, pending - epoch, nextWatermark, nextLogged, onBoardRendered)
        else
          active(
            boardSize,
            pending + (epoch -> updatedEpochStates),
            watermark,
            loggedEpochs,
            onBoardRendered
          )
    }

  @tailrec
  private def advanceWatermark(watermark: Int, logged: Set[Int]): (Int, Set[Int]) =
    val next = watermark + 1
    if logged.contains(next) then advanceWatermark(next, logged - next)
    else (watermark, logged)
