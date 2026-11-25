package gameoflife

import org.apache.pekko.actor.typed.{ActorRef, Behavior, RecipientRef}
import org.apache.pekko.actor.typed.scaladsl.{Behaviors, TimerScheduler}
import scala.concurrent.duration.*

object NextStateCellGathererActor:
  sealed trait Command

  final case class StateForEpoch(
      epoch: Int,
      alive: Boolean,
      position: Position
  ) extends Command
      with CborSerializable

  private case object Retry extends Command

  def apply(
      epoch: Int,
      neighbours: Vector[RecipientRef[CellActor.Command]],
      currentState: CellState,
      parent: ActorRef[CellActor.Command],
      retryInterval: FiniteDuration = 1.second,
      maxRetries: Int = 2
  ): Behavior[Command] =
    Behaviors.setup { context =>
      Behaviors.withTimers { timers =>
        def askNeighbours(): Unit =
          neighbours.foreach(_ ! CellActor.GetStateForEpoch(epoch, context.self))

        if neighbours.isEmpty then
          val next = GameOfLife.nextState(currentState, aliveNeighbours = 0)
          parent ! CellActor.StateComputed(epoch + 1, next.isAlive)
          Behaviors.stopped
        else
          askNeighbours()
          timers.startSingleTimer(Retry, retryInterval)
          collecting(
            epoch = epoch,
            expectedCount = neighbours.size,
            currentState = currentState,
            gathered = Map.empty,
            retries = 0,
            maxRetries = maxRetries,
            retryInterval = retryInterval,
            parent = parent,
            askNeighbours = askNeighbours,
            timers = timers
          )
      }
    }

  private def collecting(
      epoch: Int,
      expectedCount: Int,
      currentState: CellState,
      gathered: Map[Position, CellState],
      retries: Int,
      maxRetries: Int,
      retryInterval: FiniteDuration,
      parent: ActorRef[CellActor.Command],
      askNeighbours: () => Unit,
      timers: TimerScheduler[Command]
  ): Behavior[Command] =
    Behaviors.receiveMessage {
      case StateForEpoch(msgEpoch, alive, position) if msgEpoch == epoch =>
        val updated = gathered + (position -> CellState.fromBoolean(alive))
        if updated.size == expectedCount then
          val aliveNeighbours = updated.values.count(_.isAlive)
          val next            = GameOfLife.nextState(currentState, aliveNeighbours)
          parent ! CellActor.StateComputed(epoch + 1, next.isAlive)
          Behaviors.stopped
        else
          collecting(
            epoch = epoch,
            expectedCount = expectedCount,
            currentState = currentState,
            gathered = updated,
            retries = retries,
            maxRetries = maxRetries,
            retryInterval = retryInterval,
            parent = parent,
            askNeighbours = askNeighbours,
            timers = timers
          )

      case StateForEpoch(_, _, _) =>
        Behaviors.same

      case Retry =>
        val nextRetries = retries + 1
        if nextRetries >= maxRetries then
          parent ! CellActor.GatherFailed
          Behaviors.stopped
        else
          askNeighbours()
          timers.startSingleTimer(Retry, retryInterval)
          collecting(
            epoch = epoch,
            expectedCount = expectedCount,
            currentState = currentState,
            gathered = gathered,
            retries = nextRetries,
            maxRetries = maxRetries,
            retryInterval = retryInterval,
            parent = parent,
            askNeighbours = askNeighbours,
            timers = timers
          )
    }
