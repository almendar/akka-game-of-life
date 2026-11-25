package gameoflife

import org.apache.pekko.actor.typed.{
  ActorRef,
  Behavior,
  PreRestart,
  RecipientRef,
  SupervisorStrategy
}
import org.apache.pekko.actor.typed.scaladsl.{ActorContext, Behaviors}
import org.apache.pekko.cluster.sharding.typed.scaladsl.{ClusterSharding, EntityTypeKey}
import org.apache.pekko.cluster.typed.Cluster
import scala.concurrent.duration.*

object CellActor:
  val TypeKey: EntityTypeKey[Command] = EntityTypeKey[Command]("Cell")

  sealed trait Command

  final case class AdvanceToEpoch(
      epoch: Int,
      loggerRef: ActorRef[LoggerActor.Command]
  ) extends Command
      with CborSerializable

  final case class GetStateForEpoch(
      epoch: Int,
      replyTo: ActorRef[NextStateCellGathererActor.Command]
  ) extends Command
      with CborSerializable

  case object DoCrash   extends Command with CborSerializable
  case object Passivate extends Command with CborSerializable
  case object Stop      extends Command with CborSerializable

  final private[gameoflife] case class StateComputed(epoch: Int, alive: Boolean) extends Command
  private[gameoflife] case object GatherFailed                                   extends Command

  final private case class CellActorState(
      history: Vector[CellState],
      globalEpoch: Int,
      waitingForNewState: Boolean,
      loggerRef: Option[ActorRef[LoggerActor.Command]],
      pendingRequests: Map[Int, Set[ActorRef[NextStateCellGathererActor.Command]]]
  ):
    def currentEpoch: Int       = history.length - 1
    def currentState: CellState = history.last
    def isBehindGlobal: Boolean = currentEpoch < globalEpoch

  def sharded(
      entityId: String,
      boardSize: BoardSize,
      boardSeed: Long,
      sharding: ClusterSharding,
      shardRef: Option[ActorRef[ClusterSharding.ShardCommand]] = None
  ): Behavior[Command] =
    Behaviors.setup { context =>
      val decoded       = CellEntityId.decode(entityId)
      val effectiveSpec = decoded.boardSpec.getOrElse(BoardSpec(boardSize, boardSeed))
      val initialState  = GameOfLife.initialCellState(decoded.position, effectiveSpec)
      val nodeAddress   = Cluster(context.system).selfMember.address.toString
      apply(
        position = decoded.position,
        boardSize = effectiveSpec.size,
        initialState = initialState,
        neighbourLookup = pos => sharding.entityRefFor(TypeKey, decoded.forPosition(pos)),
        nodeAddress = nodeAddress,
        simulationId = decoded.simulationId,
        shardRef = shardRef
      )
    }

  def apply(
      position: Position,
      boardSize: BoardSize,
      initialState: CellState,
      neighbourLookup: Position => RecipientRef[Command],
      gatherRetryInterval: FiniteDuration = 1.second,
      nodeAddress: String = "local",
      simulationId: Int = 0,
      shardRef: Option[ActorRef[ClusterSharding.ShardCommand]] = None
  ): Behavior[Command] =
    Behaviors
      .supervise(
        Behaviors.setup[Command] { context =>
          context.log.debug("Cell {} started with initial state {}", position, initialState)
          val neighbourPositions = boardSize.neighbours(position)
          active(
            position = position,
            neighbourPositions = neighbourPositions,
            neighbourLookup = neighbourLookup,
            gatherRetryInterval = gatherRetryInterval,
            nodeAddress = nodeAddress,
            simulationId = simulationId,
            shardRef = shardRef,
            state = CellActorState(
              history = Vector(initialState),
              globalEpoch = 0,
              waitingForNewState = false,
              loggerRef = None,
              pendingRequests = Map.empty
            )
          )
        }
      )
      .onFailure[Exception](
        SupervisorStrategy.restart.withLimit(maxNrOfRetries = 10, withinTimeRange = 1.minute)
      )

  private def active(
      position: Position,
      neighbourPositions: Vector[Position],
      neighbourLookup: Position => RecipientRef[Command],
      gatherRetryInterval: FiniteDuration,
      nodeAddress: String,
      simulationId: Int,
      shardRef: Option[ActorRef[ClusterSharding.ShardCommand]],
      state: CellActorState
  ): Behavior[Command] =
    Behaviors
      .receive[Command] { (context, message) =>
        message match
          case AdvanceToEpoch(epoch, loggerRef) =>
            if epoch == 0 && state.currentEpoch == 0 then
              loggerRef ! LoggerActor.CellStateReported(
                position,
                state.currentState.isAlive,
                0,
                nodeAddress,
                simulationId
              )
            val updated = state.copy(
              globalEpoch = math.max(state.globalEpoch, epoch),
              loggerRef = Some(loggerRef)
            )
            val next =
              maybeStartGatherer(
                context,
                neighbourPositions,
                neighbourLookup,
                gatherRetryInterval,
                updated
              )
            active(
              position,
              neighbourPositions,
              neighbourLookup,
              gatherRetryInterval,
              nodeAddress,
              simulationId,
              shardRef,
              next
            )

          case GetStateForEpoch(epoch, replyTo) =>
            state.history.lift(epoch) match
              case Some(cellState) =>
                replyTo ! NextStateCellGathererActor.StateForEpoch(
                  epoch,
                  cellState.isAlive,
                  position
                )
                Behaviors.same
              case None =>
                val waiters = state.pendingRequests.getOrElse(epoch, Set.empty) + replyTo
                val next = state.copy(pendingRequests = state.pendingRequests + (epoch -> waiters))
                active(
                  position,
                  neighbourPositions,
                  neighbourLookup,
                  gatherRetryInterval,
                  nodeAddress,
                  simulationId,
                  shardRef,
                  next
                )

          case StateComputed(epoch, alive) if epoch == state.history.length =>
            val newCellState = CellState.fromBoolean(alive)
            context.log.debug(
              "Cell {} transitioned {} -> {} at epoch {}",
              position,
              state.currentState,
              newCellState,
              epoch
            )
            val updatedHistory = state.history :+ newCellState
            state.loggerRef.foreach(
              _ ! LoggerActor.CellStateReported(position, alive, epoch, nodeAddress, simulationId)
            )

            val waiters = state.pendingRequests.getOrElse(epoch, Set.empty)
            waiters.foreach(
              _ ! NextStateCellGathererActor.StateForEpoch(epoch, alive, position)
            )

            val afterUpdate = state.copy(
              history = updatedHistory,
              waitingForNewState = false,
              pendingRequests = state.pendingRequests - epoch
            )
            val next = maybeStartGatherer(
              context,
              neighbourPositions,
              neighbourLookup,
              gatherRetryInterval,
              afterUpdate
            )
            active(
              position,
              neighbourPositions,
              neighbourLookup,
              gatherRetryInterval,
              nodeAddress,
              simulationId,
              shardRef,
              next
            )

          case StateComputed(_, _) =>
            Behaviors.same

          case GatherFailed =>
            context.log.warn(
              "Cell {} failed to gather neighbour states for epoch {}",
              position,
              state.currentEpoch
            )
            active(
              position,
              neighbourPositions,
              neighbourLookup,
              gatherRetryInterval,
              nodeAddress,
              simulationId,
              shardRef,
              state.copy(waitingForNewState = false)
            )

          case DoCrash =>
            throw new RuntimeException(
              s"Simulated crash of Cell $position at epoch ${state.currentEpoch} (global ${state.globalEpoch})"
            )

          case Passivate =>
            shardRef match
              case Some(shard) =>
                shard ! ClusterSharding.Passivate(context.self)
                Behaviors.same
              case None =>
                Behaviors.stopped

          case Stop =>
            Behaviors.stopped
      }
      .receiveSignal { case (context, PreRestart) =>
        context.log.warn("Cell {} is restarting", position)
        state.loggerRef.foreach(
          _ ! LoggerActor.CellCrashed(position, state.currentEpoch, nodeAddress, simulationId)
        )
        Behaviors.same
      }

  private def maybeStartGatherer(
      context: ActorContext[Command],
      neighbourPositions: Vector[Position],
      neighbourLookup: Position => RecipientRef[Command],
      gatherRetryInterval: FiniteDuration,
      state: CellActorState
  ): CellActorState =
    if state.isBehindGlobal && !state.waitingForNewState then
      val neighbourRefs = neighbourPositions.map(neighbourLookup)
      val _             = context.spawnAnonymous(
        NextStateCellGathererActor(
          epoch = state.currentEpoch,
          neighbours = neighbourRefs,
          currentState = state.currentState,
          parent = context.self,
          retryInterval = gatherRetryInterval
        )
      )
      state.copy(waitingForNewState = true)
    else state
