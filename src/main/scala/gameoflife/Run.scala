package gameoflife

import com.typesafe.config.{Config, ConfigFactory}
import gameoflife.ui.{BackendProcessManager, SimulationStatus, UiEventHub, UiHttpServer}
import org.apache.pekko.actor.typed.ActorSystem
import org.apache.pekko.actor.typed.scaladsl.Behaviors
import org.apache.pekko.cluster.sharding.typed.scaladsl.{ClusterSharding, Entity}
import scala.concurrent.duration.*

private[gameoflife] object ClusterBootstrap:
  val SystemName = "TheGameOfLife"

  def loadConfig(
      port: Int,
      roles: Seq[String],
      seedPort: Option[Int] = None,
      extraHocon: String = ""
  ): Config =
    val rolesHocon   = roles.mkString("[", ", ", "]")
    val seedOverride = seedPort match
      case Some(sp) =>
        s"""pekko.cluster.seed-nodes = ["pekko://$SystemName@127.0.0.1:$sp"]"""
      case None => ""

    ConfigFactory
      .parseString(
        s"""pekko.remote.artery.canonical.port = $port
           |pekko.cluster.roles = $rolesHocon
           |$seedOverride
           |$extraHocon
           |""".stripMargin
      )
      .withFallback(ConfigFactory.load())

  def initCellSharding(system: ActorSystem[?], gameConfig: GameConfig): ClusterSharding =
    val sharding = ClusterSharding(system)
    val _        = sharding.init(
      Entity(CellActor.TypeKey)(entityContext =>
        CellActor.sharded(
          entityId = entityContext.entityId,
          boardSize = gameConfig.boardSize,
          boardSeed = gameConfig.boardSeed,
          sharding = sharding,
          shardRef = Some(entityContext.shard)
        )
      )
        .withRole("backend")
        .withStopMessage(CellActor.Stop)
    )
    sharding

object RunFrontend:
  def main(args: Array[String]): Unit =
    val port       = args.headOption.map(_.toInt).getOrElse(0)
    val config     = ClusterBootstrap.loadConfig(port, roles = Seq("frontend"))
    val gameConfig = GameConfig.fromConfig(config)

    val _ = ActorSystem(
      Behaviors.setup[BoardCoordinator.Command] { context =>
        val sharding = ClusterBootstrap.initCellSharding(context.system, gameConfig)
        BoardCoordinator(
          config = gameConfig,
          cellRefFor = pos => sharding.entityRefFor(CellActor.TypeKey, pos.entityId)
        )
      },
      ClusterBootstrap.SystemName,
      config
    )

object RunBackend:
  def main(args: Array[String]): Unit =
    val port     = args.headOption.map(_.toInt).getOrElse(0)
    val seedPort = args
      .lift(1)
      .map(_.toInt)
      .orElse(sys.env.get("PEKKO_SEED_PORT").map(_.toInt))
    val config     = ClusterBootstrap.loadConfig(port, roles = Seq("backend"), seedPort = seedPort)
    val gameConfig = GameConfig.fromConfig(config)

    val system = ActorSystem(
      Behaviors.setup[Nothing] { context =>
        val _ = ClusterBootstrap.initCellSharding(context.system, gameConfig)
        Behaviors.empty
      },
      ClusterBootstrap.SystemName,
      config
    )

    Option(System.getProperty("gameoflife.parent.pid"))
      .flatMap(_.toLongOption)
      .foreach { parentPid =>
        ProcessHandle.of(parentPid).ifPresent { handle =>
          val _ = handle.onExit().thenRun(() => system.terminate())
        }
      }

object RunStandalone:
  def main(args: Array[String]): Unit =
    val port   = args.headOption.map(_.toInt).getOrElse(2551)
    val config =
      ClusterBootstrap.loadConfig(port, roles = Seq("frontend", "backend"), seedPort = Some(port))
    val gameConfig = GameConfig.fromConfig(config)

    val _ = ActorSystem(
      Behaviors.setup[BoardCoordinator.Command] { context =>
        val sharding = ClusterBootstrap.initCellSharding(context.system, gameConfig)
        BoardCoordinator(
          config = gameConfig,
          cellRefFor = pos => sharding.entityRefFor(CellActor.TypeKey, pos.entityId)
        )
      },
      ClusterBootstrap.SystemName,
      config
    )

object RunUI:
  final private case class UiCliOptions(
      uiPort: Int = 8080,
      clusterPort: Int = 2551,
      initialBackends: Int = 2
  )

  private def parseArgs(args: Array[String]): UiCliOptions =
    var opts = UiCliOptions(
      uiPort = sys.env.get("UI_PORT").flatMap(_.toIntOption).getOrElse(8080),
      clusterPort = sys.env.get("PEKKO_PORT").flatMap(_.toIntOption).getOrElse(2551),
      initialBackends = sys.env.get("INITIAL_BACKENDS").flatMap(_.toIntOption).getOrElse(2)
    )
    var i = 0
    while i < args.length do
      args(i) match
        case "--ui-port" if i + 1 < args.length =>
          opts = opts.copy(uiPort = args(i + 1).toInt)
          i += 2
        case "--cluster-port" if i + 1 < args.length =>
          opts = opts.copy(clusterPort = args(i + 1).toInt)
          i += 2
        case "--backends" if i + 1 < args.length =>
          opts = opts.copy(initialBackends = math.max(0, args(i + 1).toInt))
          i += 2
        case other =>
          other.toIntOption.foreach(p => opts = opts.copy(uiPort = p))
          i += 1
    opts

  def main(args: Array[String]): Unit =
    val opts     = parseArgs(args)
    val sbrHocon =
      """pekko.cluster.split-brain-resolver {
        |  active-strategy = keep-oldest
        |  keep-oldest.down-if-alone = off
        |  stable-after = 5s
        |}
        |pekko.cluster.sharding.rebalance-interval = 3s
        |""".stripMargin

    val config = ClusterBootstrap.loadConfig(
      port = opts.clusterPort,
      roles = Seq("frontend"),
      seedPort = Some(opts.clusterPort),
      extraHocon = sbrHocon
    )
    val rawConfig = GameConfig.fromConfig(config)
    // Use a responsive default tick (1s) in UI mode while honoring user overrides later
    val uiGameConfig = rawConfig.copy(
      simulationParams = rawConfig.simulationParams.copy(
        waitForBackends = 2.seconds,
        startDelay = 300.millis,
        tick = 1000.millis
      )
    )

    val initialStatus = SimulationStatus(
      simulationId = 0,
      boardSpec = BoardSpec(uiGameConfig.boardSize, uiGameConfig.boardSeed, BoardPattern.Random),
      started = false,
      paused = false,
      step = 0,
      tickMs = uiGameConfig.simulationParams.tick.toMillis,
      autoCrashEnabled = false,
      autoCrashEveryMs = uiGameConfig.simulationParams.errorEvery.toMillis,
      crashCount = 0
    )

    val eventHub       = new UiEventHub(initialStatus)
    val backendManager = new BackendProcessManager(
      seedPort = opts.clusterPort,
      onEvent = eventHub.publish
    )

    val system = ActorSystem(
      Behaviors.setup[BoardCoordinator.Command] { context =>
        val sharding = ClusterBootstrap.initCellSharding(context.system, uiGameConfig)
        BoardCoordinator.withEntityLookup(
          config = uiGameConfig,
          entityRefFor = entityId => sharding.entityRefFor(CellActor.TypeKey, entityId),
          subscribeToCluster = true,
          autoStartOnBackendJoin = true,
          autoCrashInitiallyEnabled = false,
          onUiEvent = eventHub.publish
        )
      },
      ClusterBootstrap.SystemName,
      config
    )

    val httpServer = new UiHttpServer(
      port = opts.uiPort,
      coordinator = system,
      eventHub = eventHub,
      backendManager = backendManager
    )
    val boundUiPort = httpServer.start()

    Runtime.getRuntime.addShutdownHook(
      new Thread(
        () => {
          httpServer.stop()
          backendManager.stopAll()
          system.terminate()
        },
        "ui-shutdown-hook"
      )
    )

    println(
      s"""============================================================
         | Pekko Game of Life Web UI
         |   URL:          http://localhost:$boundUiPort
         |   Cluster Seed: pekko://${ClusterBootstrap.SystemName}@127.0.0.1:${opts.clusterPort}
         |   Launching ${opts.initialBackends} backend JVM(s)...
         |============================================================
         |""".stripMargin
    )

    (1 to opts.initialBackends).foreach { _ =>
      val _ = backendManager.startBackend()
    }
