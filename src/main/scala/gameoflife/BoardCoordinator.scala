package gameoflife

import org.apache.pekko.actor.typed.{ActorRef, Behavior, RecipientRef}
import org.apache.pekko.actor.typed.scaladsl.{Behaviors, TimerScheduler}
import org.apache.pekko.cluster.{ClusterEvent, Member}
import org.apache.pekko.cluster.typed.{Cluster, Subscribe}
import scala.util.Random

object BoardCoordinator:
  sealed trait Command

  case object StartSimulation  extends Command
  case object PauseSimulation  extends Command
  case object ResumeSimulation extends Command

  private case object Tick                                                     extends Command
  private case object TriggerCrash                                             extends Command
  final private case class ClusterMemberEvent(event: ClusterEvent.MemberEvent) extends Command

  private case object StartTimerKey
  private case object TickTimerKey
  private case object CrashTimerKey

  final private case class CoordinatorState(
      backendMembers: Set[Member],
      started: Boolean,
      paused: Boolean,
      step: Int,
      crashCount: Int
  )

  def apply(
      config: GameConfig,
      cellRefFor: Position => RecipientRef[CellActor.Command],
      subscribeToCluster: Boolean = true
  ): Behavior[Command] =
    Behaviors.setup { context =>
      Behaviors.withTimers { timers =>
        val loggerRef    = context.spawn(LoggerActor(config.boardSize), "logger")
        val allPositions = config.boardSize.allPositions

        if subscribeToCluster then
          val memberEventAdapter =
            context.messageAdapter[ClusterEvent.MemberEvent](ClusterMemberEvent.apply)
          Cluster(context.system).subscriptions ! Subscribe(
            memberEventAdapter,
            classOf[ClusterEvent.MemberEvent]
          )

        active(
          config = config,
          allPositions = allPositions,
          cellRefFor = cellRefFor,
          loggerRef = loggerRef,
          timers = timers,
          requireClusterBackends = subscribeToCluster,
          state = CoordinatorState(
            backendMembers = Set.empty,
            started = false,
            paused = false,
            step = 0,
            crashCount = 0
          )
        )
      }
    }

  private def active(
      config: GameConfig,
      allPositions: Vector[Position],
      cellRefFor: Position => RecipientRef[CellActor.Command],
      loggerRef: ActorRef[LoggerActor.Command],
      timers: TimerScheduler[Command],
      requireClusterBackends: Boolean,
      state: CoordinatorState
  ): Behavior[Command] =
    Behaviors.receive { (context, message) =>
      val simParams   = config.simulationParams
      val hasBackends = !requireClusterBackends || state.backendMembers.nonEmpty

      message match
        case ClusterMemberEvent(ClusterEvent.MemberUp(member)) if member.hasRole("backend") =>
          val updatedBackends = state.backendMembers + member
          context.log.info(
            "Backend member {} is Up (total backends: {})",
            member.address,
            updatedBackends.size
          )
          if !state.started && !timers.isTimerActive(StartTimerKey) then
            context.log.info(
              "Scheduling simulation start in {}",
              simParams.waitForBackends
            )
            timers.startSingleTimer(StartTimerKey, StartSimulation, simParams.waitForBackends)
          active(
            config,
            allPositions,
            cellRefFor,
            loggerRef,
            timers,
            requireClusterBackends,
            state.copy(backendMembers = updatedBackends)
          )

        case ClusterMemberEvent(ClusterEvent.MemberRemoved(member, _))
            if member.hasRole("backend") =>
          val updatedBackends = state.backendMembers - member
          context.log.warn(
            "Backend member {} removed (remaining backends: {})",
            member.address,
            updatedBackends.size
          )
          active(
            config,
            allPositions,
            cellRefFor,
            loggerRef,
            timers,
            requireClusterBackends,
            state.copy(backendMembers = updatedBackends)
          )

        case ClusterMemberEvent(_) =>
          Behaviors.same

        case StartSimulation =>
          if state.started then Behaviors.same
          else if !hasBackends then
            context.log.warn("No backend nodes available yet; waiting before starting simulation")
            Behaviors.same
          else
            context.log.info(
              "Starting Game of Life simulation on {}x{} board (seed={})",
              config.boardSize.width,
              config.boardSize.height,
              config.boardSeed
            )
            timers.startTimerWithFixedDelay(
              TickTimerKey,
              Tick,
              simParams.startDelay,
              simParams.tick
            )
            if simParams.maxNumberOfCrashes > 0 then
              timers.startTimerWithFixedDelay(
                CrashTimerKey,
                TriggerCrash,
                simParams.firstErrorAfter,
                simParams.errorEvery
              )
            active(
              config,
              allPositions,
              cellRefFor,
              loggerRef,
              timers,
              requireClusterBackends,
              state.copy(started = true, paused = false)
            )

        case PauseSimulation =>
          if state.started && !state.paused then
            timers.cancel(TickTimerKey)
            context.log.info("Simulation paused at epoch {}", state.step)
            active(
              config,
              allPositions,
              cellRefFor,
              loggerRef,
              timers,
              requireClusterBackends,
              state.copy(paused = true)
            )
          else Behaviors.same

        case ResumeSimulation =>
          if state.started && state.paused then
            timers.startTimerWithFixedDelay(
              TickTimerKey,
              Tick,
              simParams.startDelay,
              simParams.tick
            )
            context.log.info("Simulation resumed from epoch {}", state.step)
            active(
              config,
              allPositions,
              cellRefFor,
              loggerRef,
              timers,
              requireClusterBackends,
              state.copy(paused = false)
            )
          else Behaviors.same

        case Tick =>
          if !hasBackends then
            context.log.warn("No backend nodes available; skipping tick at epoch {}", state.step)
            Behaviors.same
          else
            val nextStep = state.step + 1
            context.log.info("Epoch: {}", nextStep)
            allPositions.foreach(pos =>
              cellRefFor(pos) ! CellActor.AdvanceToEpoch(nextStep, loggerRef)
            )
            active(
              config,
              allPositions,
              cellRefFor,
              loggerRef,
              timers,
              requireClusterBackends,
              state.copy(step = nextStep)
            )

        case TriggerCrash =>
          if !hasBackends then Behaviors.same
          else if state.crashCount >= simParams.maxNumberOfCrashes then
            timers.cancel(CrashTimerKey)
            Behaviors.same
          else
            val target = allPositions(Random.nextInt(allPositions.size))
            context.log.info("Injecting simulated crash on cell {}", target)
            cellRefFor(target) ! CellActor.DoCrash
            active(
              config,
              allPositions,
              cellRefFor,
              loggerRef,
              timers,
              requireClusterBackends,
              state.copy(crashCount = state.crashCount + 1)
            )
    }
