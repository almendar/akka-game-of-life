package gameoflife

import java.util.concurrent.TimeUnit

import org.apache.pekko.actor.ActorSystem
import com.typesafe.config.{Config, ConfigFactory}

import scala.concurrent.duration._
import scala.concurrent.ExecutionContext.Implicits.global

/**
 * Created by tomaszk on 3/9/15.
 */
object RunFrontend {

  def convertTime(l: Long): FiniteDuration =
    FiniteDuration(l, TimeUnit.MILLISECONDS)

  def getTimeFromConfig(config: Config)(key: String): FiniteDuration =
    convertTime(config.getDuration(key, TimeUnit.MILLISECONDS))

  def runFrontend(args: Array[String]): Unit = {
    val port = if (args.nonEmpty) args(0).toInt else 0

    import gameoflife.BoardCreator._
    val config: Config = ConfigFactory.parseString(s"pekko.remote.artery.canonical.port=$port")
      .withFallback(ConfigFactory.parseString("pekko.cluster.roles = [frontend]"))
      .withFallback(ConfigFactory.load())
    val (x, y) = (config.getInt("game-of-life.board.size.x"), config.getInt("game-of-life.board.size.y"))

    val thisConfig = getTimeFromConfig(config)
    implicit val system: ActorSystem = ActorSystem("TheGameOfLife", config)

    val simulatonParams = SimulationParams(
      thisConfig("game-of-life.simulation.start-delay"),
      thisConfig("game-of-life.simulation.tick"),
      thisConfig("game-of-life.errors.delay"),
      thisConfig("game-of-life.errors.every"),
      config.getInt("game-of-life.simulation.max-crashes")
    )

    val delay = thisConfig("game-of-life.simulation.wait-for-backends")
    val boardSize: BoardSize = (x, y)
    val mainBoard = system.actorOf(BoardCreator.props(boardSize, simulatonParams), "BoardCreator")
    system.scheduler.scheduleOnce(delay, mainBoard, StartSimulation)
  }

  def main(args: Array[String]): Unit =
    runFrontend(args)
}

object RunBackend {
  def runBackend(args: Array[String]): Unit = {
    val port = if (args.nonEmpty) args(0).toInt else 0
    val config: Config = ConfigFactory.parseString(s"pekko.remote.artery.canonical.port=$port")
      .withFallback(ConfigFactory.parseString("pekko.cluster.roles = [backend]"))
      .withFallback(ConfigFactory.load())
    ActorSystem("TheGameOfLife", config)
  }

  def main(args: Array[String]): Unit =
    runBackend(args)
}
