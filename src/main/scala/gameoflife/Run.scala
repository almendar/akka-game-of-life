package gameoflife

import com.typesafe.config.{Config, ConfigFactory}
import org.apache.pekko.actor.typed.ActorSystem
import org.apache.pekko.actor.typed.scaladsl.Behaviors
import org.apache.pekko.cluster.sharding.typed.scaladsl.{ClusterSharding, Entity}

private[gameoflife] object ClusterBootstrap:
  val SystemName = "TheGameOfLife"

  def loadConfig(port: Int, roles: Seq[String], seedPort: Option[Int] = None): Config =
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
          sharding = sharding
        )
      ).withRole("backend")
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
    val port       = args.headOption.map(_.toInt).getOrElse(0)
    val config     = ClusterBootstrap.loadConfig(port, roles = Seq("backend"))
    val gameConfig = GameConfig.fromConfig(config)

    val _ = ActorSystem(
      Behaviors.setup[Nothing] { context =>
        val _ = ClusterBootstrap.initCellSharding(context.system, gameConfig)
        Behaviors.empty
      },
      ClusterBootstrap.SystemName,
      config
    )

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
