package gameoflife

import java.util.concurrent.TimeUnit

import akka.actor.ActorSystem
import com.typesafe.config.{ConfigFactory, Config}

import scala.concurrent.duration.FiniteDuration

/**
 * Created by tomaszk on 3/9/15.
 */


object Run extends App {

  def convertTime(l:Long) : FiniteDuration = {
    FiniteDuration(l,TimeUnit.MILLISECONDS)
  }

  def getTimeFromConfig(config:Config)(key:String) : FiniteDuration = {
    convertTime(config.getDuration(key,TimeUnit.MILLISECONDS))
  }

  import gameoflife.BoardCreator._
  implicit val system = ActorSystem("TheGameOfLife")
  val config : Config = ConfigFactory.load().getConfig("game-of-life")
  val (x,y) = (config.getInt("board.size.x"), config.getInt("board.size.y"))

  val thisConfig = getTimeFromConfig(config) _

  val simulatonParams = SimulationParams(
    thisConfig("simulation.start-delay"),
    thisConfig("simulation.tick"),
    thisConfig("errors.delay"),
    thisConfig("errors.every"),
    config.getInt("simulation.max-crashes")
  )


  val boardSize : BoardSize = (x,y)
  def numberOfCells = boardSize._1 * boardSize._2
  val mainBoard = system.actorOf(BoardCreator.props(boardSize,simulatonParams),"BoardCreator")
  LoggerActor.startMe(boardSize)
  mainBoard ! StartSimulation
}
