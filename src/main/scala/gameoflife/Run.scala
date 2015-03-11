package gameoflife

import java.util.concurrent.TimeUnit

import akka.actor.ActorSystem
import com.typesafe.config.{ConfigFactory, Config}

import scala.concurrent.duration._
import scala.concurrent.ExecutionContext.Implicits.global
/**
 * Created by tomaszk on 3/9/15.
 */


object RunFrontend extends App {

  def convertTime(l:Long) : FiniteDuration = {
    FiniteDuration(l,TimeUnit.MILLISECONDS)
  }

  def getTimeFromConfig(config:Config)(key:String) : FiniteDuration = {
    convertTime(config.getDuration(key,TimeUnit.MILLISECONDS))
  }

  def runFrontend(args : Array[String]) = {

    val port = if(!args.isEmpty) args(0).toInt else 0

    import gameoflife.BoardCreator._
    val config : Config = ConfigFactory.parseString(s"akka.remote.netty.tcp.port=${port}")
      .withFallback(ConfigFactory.parseString("akka.cluster.roles = [frontend]"))
      .withFallback(ConfigFactory.load())
    val (x,y) = (config.getInt("game-of-life.board.size.x"), config.getInt("game-of-life.board.size.y"))

    val thisConfig = getTimeFromConfig(config) _
    implicit val system = ActorSystem("TheGameOfLife",config)

    val simulatonParams = SimulationParams(
      thisConfig("game-of-life.simulation.start-delay"),
      thisConfig("game-of-life.simulation.tick"),
      thisConfig("game-of-life.errors.delay"),
      thisConfig("game-of-life.errors.every"),
      config.getInt("game-of-life.simulation.max-crashes")
    )

    val delay = thisConfig("game-of-life.simulation.wait-for-backends")
    val boardSize : BoardSize = (x,y)
    def numberOfCells = boardSize._1 * boardSize._2
    val mainBoard = system.actorOf(BoardCreator.props(boardSize,simulatonParams),"BoardCreator")
    system.scheduler.scheduleOnce(delay,mainBoard, StartSimulation)
  }
  runFrontend(args)

}

object RunBackend extends App {
  def runBackend(args : Array[String]) = {
    val port = if(!args.isEmpty) args(0).toInt else 0
    val config : Config = ConfigFactory.parseString(s"akka.remote.netty.tcp.port=${port}")
      .withFallback(ConfigFactory.parseString("akka.cluster.roles = [backend]"))
      .withFallback(ConfigFactory.load())
    val system = ActorSystem("TheGameOfLife",config)
  }
  runBackend(args)
}
