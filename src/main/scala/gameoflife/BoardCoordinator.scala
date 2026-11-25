package gameoflife

import gameoflife.ui.{ClusterMemberInfo, SimulationStatus, UiEvent}
import org.apache.pekko.actor.typed.{ActorRef, Behavior, RecipientRef}
import org.apache.pekko.actor.typed.scaladsl.{Behaviors, TimerScheduler}
import org.apache.pekko.cluster.{ClusterEvent, Member}
import org.apache.pekko.cluster.typed.{Cluster, Subscribe}
import scala.concurrent.duration.*
import scala.util.Random

object BoardCoordinator:
  sealed trait Command

  case object StartSimulation                               extends Command
  case object PauseSimulation                               extends Command
  case object ResumeSimulation                              extends Command
  case object StepOnce                                      extends Command
  final case class UpdateTickInterval(tick: FiniteDuration) extends Command
  final case class SetAutoCrash(
      enabled: Boolean,
      interval: Option[FiniteDuration] = None
  ) extends Command
  final case class CrashCell(position: Position) extends Command
  case object CrashRandomCell                    extends Command
  final case class ResetSimulation(
      boardSpec: BoardSpec,
      autoStart: Boolean = false
  ) extends Command
  final case class GetStatus(replyTo: ActorRef[SimulationStatus]) extends Command

  private case object Tick                                                     extends Command
  private case object TriggerCrash                                             extends Command
  final private case class ClusterMemberEvent(event: ClusterEvent.MemberEvent) extends Command
  final private case class ClusterReachabilityEvent(event: ClusterEvent.ReachabilityEvent)
      extends Command

  private case object StartTimerKey
  private case object TickTimerKey
  private case object CrashTimerKey

  final private case class CoordinatorState(
      backendMembers: Set[Member],
      membersByAddress: Map[String, ClusterMemberInfo],
      simulationId: Int,
      boardSpec: BoardSpec,
      allPositions: Vector[Position],
      started: Boolean,
      paused: Boolean,
      step: Int,
      tick: FiniteDuration,
      autoCrashEnabled: Boolean,
      autoCrashEvery: FiniteDuration,
      crashCount: Int,
      epochZeroTriggered: Boolean
  ):
    def toStatus: SimulationStatus =
      SimulationStatus(
        simulationId = simulationId,
        boardSpec = boardSpec,
        started = started,
        paused = paused,
        step = step,
        tickMs = tick.toMillis,
        autoCrashEnabled = autoCrashEnabled,
        autoCrashEveryMs = autoCrashEvery.toMillis,
        crashCount = crashCount
      )

  def apply(
      config: GameConfig,
      cellRefFor: Position => RecipientRef[CellActor.Command],
      subscribeToCluster: Boolean = true
  ): Behavior[Command] =
    withEntityLookup(
      config = config,
      entityRefFor = entityId => cellRefFor(CellEntityId.decode(entityId).position),
      subscribeToCluster = subscribeToCluster,
      autoStartOnBackendJoin = true,
      autoCrashInitiallyEnabled = config.simulationParams.maxNumberOfCrashes > 0,
      onUiEvent = _ => ()
    )

  def withEntityLookup(
      config: GameConfig,
      entityRefFor: String => RecipientRef[CellActor.Command],
      subscribeToCluster: Boolean = true,
      autoStartOnBackendJoin: Boolean = true,
      autoCrashInitiallyEnabled: Boolean = true,
      onUiEvent: UiEvent => Unit = _ => ()
  ): Behavior[Command] =
    Behaviors.setup { context =>
      Behaviors.withTimers { timers =>
        val initialSpec  = BoardSpec(config.boardSize, config.boardSeed, BoardPattern.Random)
        val allPositions = initialSpec.size.allPositions
        val loggerRef    = context.spawn(
          LoggerActor(
            boardSize = config.boardSize,
            onEpochSnapshot = snapshot => onUiEvent(UiEvent.EpochCompleted(snapshot)),
            onCellCrashed = crash =>
              onUiEvent(
                UiEvent.CellCrashedEvent(
                  position = crash.position,
                  epoch = crash.epoch,
                  nodeAddress = crash.nodeAddress,
                  timestampMs = System.currentTimeMillis()
                )
              )
          ),
          "logger"
        )

        var initialMembers = Map.empty[String, ClusterMemberInfo]
        if subscribeToCluster then
          val cluster    = Cluster(context.system)
          val selfMember = cluster.selfMember
          val selfAddr   = selfMember.address.toString
          val selfInfo   = ClusterMemberInfo(
            address = selfAddr,
            roles = selfMember.roles,
            status = selfMember.status.toString,
            reachable = true
          )
          initialMembers = Map(selfAddr -> selfInfo)
          onUiEvent(UiEvent.ClusterMemberUpdated(selfInfo))

          val memberEventAdapter =
            context.messageAdapter[ClusterEvent.MemberEvent](ClusterMemberEvent.apply)
          cluster.subscriptions ! Subscribe(
            memberEventAdapter,
            classOf[ClusterEvent.MemberEvent]
          )

          val reachabilityAdapter =
            context.messageAdapter[ClusterEvent.ReachabilityEvent](ClusterReachabilityEvent.apply)
          cluster.subscriptions ! Subscribe(
            reachabilityAdapter,
            classOf[ClusterEvent.ReachabilityEvent]
          )

        val initialState = CoordinatorState(
          backendMembers = Set.empty,
          membersByAddress = initialMembers,
          simulationId = 0,
          boardSpec = initialSpec,
          allPositions = allPositions,
          started = false,
          paused = false,
          step = 0,
          tick = config.simulationParams.tick,
          autoCrashEnabled = autoCrashInitiallyEnabled,
          autoCrashEvery = config.simulationParams.errorEvery,
          crashCount = 0,
          epochZeroTriggered = false
        )
        onUiEvent(UiEvent.SimulationStatusUpdated(initialState.toStatus))

        active(
          config = config,
          entityRefFor = entityRefFor,
          loggerRef = loggerRef,
          timers = timers,
          requireClusterBackends = subscribeToCluster,
          autoStartOnBackendJoin = autoStartOnBackendJoin,
          onUiEvent = onUiEvent,
          state = initialState
        )
      }
    }

  private def entityIdFor(pos: Position, state: CoordinatorState): String =
    if state.simulationId == 0 && state.boardSpec.pattern == BoardPattern.Random then
      CellEntityId(pos).encode
    else CellEntityId(pos, state.simulationId, Some(state.boardSpec)).encode

  private def memberInfo(member: Member, status: String, reachable: Boolean): ClusterMemberInfo =
    ClusterMemberInfo(
      address = member.address.toString,
      roles = member.roles,
      status = status,
      reachable = reachable
    )

  private def active(
      config: GameConfig,
      entityRefFor: String => RecipientRef[CellActor.Command],
      loggerRef: ActorRef[LoggerActor.Command],
      timers: TimerScheduler[Command],
      requireClusterBackends: Boolean,
      autoStartOnBackendJoin: Boolean,
      onUiEvent: UiEvent => Unit,
      state: CoordinatorState
  ): Behavior[Command] =
    Behaviors.receive { (context, message) =>
      val simParams   = config.simulationParams
      val hasBackends = !requireClusterBackends || state.backendMembers.nonEmpty

      def broadcastEpoch(targetState: CoordinatorState, epoch: Int): Unit =
        targetState.allPositions.foreach { pos =>
          val id = entityIdFor(pos, targetState)
          entityRefFor(id) ! CellActor.AdvanceToEpoch(epoch, loggerRef)
        }

      def nextWith(updated: CoordinatorState, emitStatus: Boolean = false): Behavior[Command] =
        if emitStatus then onUiEvent(UiEvent.SimulationStatusUpdated(updated.toStatus))
        active(
          config,
          entityRefFor,
          loggerRef,
          timers,
          requireClusterBackends,
          autoStartOnBackendJoin,
          onUiEvent,
          updated
        )

      message match
        case ClusterMemberEvent(ClusterEvent.MemberUp(member)) =>
          val addr = member.address.toString
          val info = memberInfo(member, "Up", reachable = true)
          onUiEvent(UiEvent.ClusterMemberUpdated(info))

          if member.hasRole("backend") then
            val updatedBackends = state.backendMembers + member
            context.log.info(
              "Backend member {} is Up (total backends: {})",
              member.address,
              updatedBackends.size
            )
            onUiEvent(
              UiEvent.LogMessage(
                "cluster",
                s"Backend $addr is Up (${updatedBackends.size} active)"
              )
            )
            var nextState = state.copy(
              backendMembers = updatedBackends,
              membersByAddress = state.membersByAddress + (addr -> info)
            )
            if !state.epochZeroTriggered && state.step == 0 then
              broadcastEpoch(nextState, 0)
              nextState = nextState.copy(epochZeroTriggered = true)

            if autoStartOnBackendJoin && !nextState.started && !timers.isTimerActive(StartTimerKey)
            then
              context.log.info("Scheduling simulation start in {}", simParams.waitForBackends)
              timers.startSingleTimer(StartTimerKey, StartSimulation, simParams.waitForBackends)
            nextWith(nextState)
          else
            onUiEvent(
              UiEvent.LogMessage("cluster", s"Member $addr (${member.roles.mkString(",")}) is Up")
            )
            nextWith(state.copy(membersByAddress = state.membersByAddress + (addr -> info)))

        case ClusterMemberEvent(ClusterEvent.MemberRemoved(member, previousStatus)) =>
          val addr            = member.address.toString
          val updatedBackends =
            if member.hasRole("backend") then state.backendMembers - member
            else state.backendMembers
          if member.hasRole("backend") then
            context.log.warn(
              "Backend member {} removed (remaining backends: {})",
              member.address,
              updatedBackends.size
            )
          onUiEvent(UiEvent.ClusterMemberRemoved(addr))
          onUiEvent(
            UiEvent.LogMessage(
              "cluster",
              s"Member $addr Removed (was $previousStatus; ${updatedBackends.size} backends left)"
            )
          )
          nextWith(
            state.copy(
              backendMembers = updatedBackends,
              membersByAddress = state.membersByAddress - addr
            )
          )

        case ClusterMemberEvent(otherEvent) =>
          val member    = otherEvent.member
          val addr      = member.address.toString
          val statusStr = otherEvent match
            case _: ClusterEvent.MemberJoined   => "Joining"
            case _: ClusterEvent.MemberWeaklyUp => "WeaklyUp"
            case _: ClusterEvent.MemberLeft     => "Leaving"
            case _: ClusterEvent.MemberExited   => "Exiting"
            case _: ClusterEvent.MemberDowned   => "Down"
            case _                              => member.status.toString
          val prevReachable = state.membersByAddress.get(addr).forall(_.reachable)
          val info          = memberInfo(member, statusStr, reachable = prevReachable)
          onUiEvent(UiEvent.ClusterMemberUpdated(info))
          onUiEvent(UiEvent.LogMessage("cluster", s"Member $addr → $statusStr"))
          nextWith(state.copy(membersByAddress = state.membersByAddress + (addr -> info)))

        case ClusterReachabilityEvent(ClusterEvent.UnreachableMember(member)) =>
          val addr = member.address.toString
          val prev = state.membersByAddress.getOrElse(
            addr,
            memberInfo(member, member.status.toString, reachable = false)
          )
          val info = prev.copy(reachable = false)
          context.log.warn("Member {} marked UNREACHABLE", member.address)
          onUiEvent(UiEvent.ClusterMemberUpdated(info))
          onUiEvent(UiEvent.LogMessage("cluster", s"Member $addr became UNREACHABLE"))
          nextWith(state.copy(membersByAddress = state.membersByAddress + (addr -> info)))

        case ClusterReachabilityEvent(ClusterEvent.ReachableMember(member)) =>
          val addr = member.address.toString
          val prev = state.membersByAddress.getOrElse(
            addr,
            memberInfo(member, member.status.toString, reachable = true)
          )
          val info = prev.copy(reachable = true)
          context.log.info("Member {} is REACHABLE again", member.address)
          onUiEvent(UiEvent.ClusterMemberUpdated(info))
          onUiEvent(UiEvent.LogMessage("cluster", s"Member $addr is REACHABLE again"))
          nextWith(state.copy(membersByAddress = state.membersByAddress + (addr -> info)))

        case StartSimulation =>
          if state.started && !state.paused then Behaviors.same
          else if !hasBackends then
            context.log.warn("No backend nodes available yet; waiting before starting simulation")
            onUiEvent(UiEvent.LogMessage("sim", "Cannot start: no backend nodes are Up yet"))
            Behaviors.same
          else
            context.log.info(
              "Starting Game of Life simulation on {}x{} board (seed={})",
              state.boardSpec.size.width,
              state.boardSpec.size.height,
              state.boardSpec.seed
            )
            timers.startTimerWithFixedDelay(
              TickTimerKey,
              Tick,
              simParams.startDelay,
              state.tick
            )
            if state.autoCrashEnabled && simParams.maxNumberOfCrashes > 0 then
              timers.startTimerWithFixedDelay(
                CrashTimerKey,
                TriggerCrash,
                simParams.firstErrorAfter,
                state.autoCrashEvery
              )
            onUiEvent(
              UiEvent.LogMessage(
                "sim",
                s"Simulation started (${state.boardSpec.size.width}x${state.boardSpec.size.height}, ${state.boardSpec.pattern.label}, tick=${state.tick.toMillis}ms)"
              )
            )
            nextWith(state.copy(started = true, paused = false), emitStatus = true)

        case PauseSimulation =>
          if state.started && !state.paused then
            timers.cancel(TickTimerKey)
            context.log.info("Simulation paused at epoch {}", state.step)
            onUiEvent(UiEvent.LogMessage("sim", s"Simulation paused at epoch ${state.step}"))
            nextWith(state.copy(paused = true), emitStatus = true)
          else Behaviors.same

        case ResumeSimulation =>
          if state.started && state.paused then
            timers.startTimerWithFixedDelay(
              TickTimerKey,
              Tick,
              100.millis,
              state.tick
            )
            context.log.info("Simulation resumed from epoch {}", state.step)
            onUiEvent(UiEvent.LogMessage("sim", s"Simulation resumed from epoch ${state.step}"))
            nextWith(state.copy(paused = false), emitStatus = true)
          else if !state.started then
            context.self ! StartSimulation
            Behaviors.same
          else Behaviors.same

        case StepOnce =>
          if !hasBackends then
            onUiEvent(UiEvent.LogMessage("sim", "Cannot step: no backend nodes are Up"))
            Behaviors.same
          else
            val nextStep = state.step + 1
            context.log.info("Single-stepping to epoch: {}", nextStep)
            broadcastEpoch(state, nextStep)
            nextWith(
              state.copy(step = nextStep, epochZeroTriggered = true),
              emitStatus = true
            )

        case UpdateTickInterval(newTick) =>
          val clamped =
            if newTick < 100.millis then 100.millis
            else if newTick > 10.seconds then 10.seconds
            else newTick
          if state.started && !state.paused then
            timers.startTimerWithFixedDelay(TickTimerKey, Tick, clamped, clamped)
          onUiEvent(UiEvent.LogMessage("sim", s"Tick interval updated to ${clamped.toMillis}ms"))
          nextWith(state.copy(tick = clamped), emitStatus = true)

        case SetAutoCrash(enabled, maybeInterval) =>
          val newEvery = maybeInterval
            .map(d => if d < 500.millis then 500.millis else d)
            .getOrElse(state.autoCrashEvery)
          if enabled then
            timers.startTimerWithFixedDelay(CrashTimerKey, TriggerCrash, newEvery, newEvery)
            onUiEvent(
              UiEvent.LogMessage("crash", s"Auto-crash enabled (every ${newEvery.toMillis}ms)")
            )
          else
            timers.cancel(CrashTimerKey)
            onUiEvent(UiEvent.LogMessage("crash", "Auto-crash disabled"))
          nextWith(
            state.copy(autoCrashEnabled = enabled, autoCrashEvery = newEvery),
            emitStatus = true
          )

        case CrashCell(position) =>
          if !hasBackends || !state.boardSpec.size.contains(position) then Behaviors.same
          else
            val id = entityIdFor(position, state)
            context.log.info("Injecting manual crash on cell {}", position)
            entityRefFor(id) ! CellActor.DoCrash
            onUiEvent(
              UiEvent.LogMessage(
                "crash",
                s"Manual DoCrash sent to cell (${position.x},${position.y})"
              )
            )
            nextWith(state.copy(crashCount = state.crashCount + 1), emitStatus = true)

        case CrashRandomCell =>
          if !hasBackends then Behaviors.same
          else
            val target = state.allPositions(Random.nextInt(state.allPositions.size))
            val id     = entityIdFor(target, state)
            context.log.info("Injecting simulated crash on random cell {}", target)
            entityRefFor(id) ! CellActor.DoCrash
            onUiEvent(
              UiEvent.LogMessage(
                "crash",
                s"Random DoCrash sent to cell (${target.x},${target.y})"
              )
            )
            nextWith(state.copy(crashCount = state.crashCount + 1), emitStatus = true)

        case ResetSimulation(newSpec, autoStart) =>
          timers.cancel(TickTimerKey)
          timers.cancel(StartTimerKey)
          // Passivate previous simulation's cell entities so shards stay clean
          if hasBackends then
            state.allPositions.foreach { pos =>
              entityRefFor(entityIdFor(pos, state)) ! CellActor.Passivate
            }

          val nextSimId = state.simulationId + 1
          loggerRef ! LoggerActor.Reset(newSpec.size, nextSimId)

          val shouldRun  = autoStart && hasBackends
          val resetState = state.copy(
            simulationId = nextSimId,
            boardSpec = newSpec,
            allPositions = newSpec.size.allPositions,
            started = shouldRun,
            paused = false,
            step = 0,
            crashCount = 0,
            epochZeroTriggered = hasBackends
          )

          onUiEvent(UiEvent.SimulationReset(nextSimId, newSpec))
          onUiEvent(
            UiEvent.LogMessage(
              "sim",
              s"Board reset → ${newSpec.size.width}x${newSpec.size.height}, pattern=${newSpec.pattern.label}, seed=${newSpec.seed}"
            )
          )

          if hasBackends then
            broadcastEpoch(resetState, 0)
            if shouldRun then
              timers.startTimerWithFixedDelay(TickTimerKey, Tick, 300.millis, resetState.tick)

          nextWith(resetState, emitStatus = true)

        case GetStatus(replyTo) =>
          replyTo ! state.toStatus
          Behaviors.same

        case Tick =>
          if !hasBackends then
            context.log.warn("No backend nodes available; skipping tick at epoch {}", state.step)
            Behaviors.same
          else
            val nextStep = state.step + 1
            context.log.info("Epoch: {}", nextStep)
            broadcastEpoch(state, nextStep)
            nextWith(
              state.copy(step = nextStep, epochZeroTriggered = true),
              emitStatus = true
            )

        case TriggerCrash =>
          if !hasBackends || !state.autoCrashEnabled then Behaviors.same
          else if state.crashCount >= simParams.maxNumberOfCrashes then
            timers.cancel(CrashTimerKey)
            Behaviors.same
          else
            val target = state.allPositions(Random.nextInt(state.allPositions.size))
            val id     = entityIdFor(target, state)
            context.log.info("Injecting simulated crash on cell {}", target)
            entityRefFor(id) ! CellActor.DoCrash
            onUiEvent(
              UiEvent.LogMessage(
                "crash",
                s"Auto DoCrash sent to cell (${target.x},${target.y})"
              )
            )
            nextWith(state.copy(crashCount = state.crashCount + 1), emitStatus = true)
    }
