package gameoflife

import akka.actor.SupervisorStrategy.Restart
import gameoflife.BoardCreator.CellState
import gameoflife.CellActor.{GetStateForEpoche, Neighbours, GoToEpoche}
import gameoflife.NextStateCellGathererActor.StateForEpoche

import scala.concurrent.ExecutionContext.Implicits.global
import akka.actor._

import scala.concurrent.duration._
import scala.util.Random

/**
 * Created by tomaszk on 2/28/15.
 */

object NextStateCellGathererActor {
  case class GatherForEpoche(epoche : Int, neighbours : List[(Int,Int)])
  case class StateForEpoche(epoche:Int,value:Boolean,position : (Int,Int))
  case object Retry
}

class NextStateCellGathererActor(position : (Int,Int), epoche:Int, whoToAsk:Neighbours ) extends Actor with ActorLogging {


  case class GatheredData(epoche : Int, gatheredState : Set[StateForEpoche] = Set.empty, val howManyAnswers :Int) {

    def needMore : Boolean = gatheredState.size < howManyAnswers

    def pushNewState(stateToAdd:StateForEpoche) : GatheredData = {
      copy(gatheredState =   gatheredState + stateToAdd)
    }
  }

  import NextStateCellGathererActor._


  var gathering :  Option[GatheredData] = None

  override def preStart(): Unit = {
    gathering = Some(GatheredData(epoche = epoche, howManyAnswers = whoToAsk.size))
    askForValues
    context.system.scheduler.scheduleOnce(3 seconds,self, Retry)
  }


  def askForValues: Unit = {
    whoToAsk.foreach { case (i, j) =>
      val selection = context.actorSelection(s"/user/BoardCreator/Cell-$i,$j")
      selection ! CellActor.GetStateFromEpoche(epoche)
    }
  }

  @throws[Exception](classOf[Exception])
  override def preRestart(reason: Throwable, message: Option[Any]): Unit = {
    super.preRestart(reason, message)
    log.error("Dying")
  }

  override def receive: Receive = {

    case p @ StateForEpoche(_,_,_) => {
      gathering = gathering.map(g => g.pushNewState(p))
      if (!gathering.map(_.needMore).getOrElse(false)) {
        val newState : Option[Boolean] = gathering.map {x =>
          x.gatheredState.map(_.value).foldLeft(0)((acc, value) => if (value) acc + 1 else acc) % 3 == 0
        }
        context.parent ! CellActor.SetNewState(newState.getOrElse(false), epoche+1)
        gathering = None
        context.stop(self)
      }
    }
    case Retry => askForValues
  }
}


object CellActor {
  case class GoToEpoche(value:Int)
  case object GetToNextEpoche
  case class GetStateFromEpoche(value : Int)
  case class SetNewState(state:Boolean,epoche:Int)
  case class GetStateForEpoche(epoche:Int)

  type Neighbours = List[(Int,Int)]

}
class CellActor(position:(Int,Int), neighbours : Neighbours, state : Boolean) extends Actor with ActorLogging {
  import CellActor._

  var globalEpoche : Int = 0
  var epocheToState : Map[Int,Boolean] = Map(0 -> state)
  var enquedRequest : List[(ActorSelection,GetStateFromEpoche)] = List.empty

  def iambehind : Boolean = !epocheToState.contains(globalEpoche)
  def myCurrentEpoche : Int = epocheToState.keys.max

  override def receive : Receive = {
    case GoToEpoche(number) =>
      globalEpoche = number
      scheduleTransitionToNextEpocheIfNeeded

    case GetToNextEpoche =>
      context.actorOf(Props(classOf[NextStateCellGathererActor],position,myCurrentEpoche,neighbours))
      if(Random.nextInt() % 100==0) throw new Exception("Random die")

    case GetStateFromEpoche(epoche) =>
      epocheToState.get(epoche) match {
        case Some(state) => sender ! NextStateCellGathererActor.StateForEpoche(epoche,state,position)
        case None => enquedRequest = (context.actorSelection(sender.path), GetStateFromEpoche(epoche)) :: enquedRequest
      }

    case SetNewState(state,epoche) => {
      if((myCurrentEpoche + 1 != epoche))
      {}
      else {
      epocheToState += (epoche -> state)
      val (toBeProcessed,left) = enquedRequest.partition(p => p._2.value <= myCurrentEpoche)
      enquedRequest = left
      //log.info(epocheToState.keys.toList.sorted.mkString(","))
      toBeProcessed.foreach{ case(actor,GetStateFromEpoche(older)) => actor ! NextStateCellGathererActor.StateForEpoche(epoche,epocheToState(older),position)
      }
      scheduleTransitionToNextEpocheIfNeeded
        context.actorSelection("/user/Logger") ! CellState(position,state,epoche)
      //log.info(s"${epocheToState(myCurrentEpoche-1)} -> ${epocheToState(myCurrentEpoche)} ")
      }
    }
    case p @ _ =>
      println(s"Got message $p")
  }


  def scheduleTransitionToNextEpocheIfNeeded: Unit = {
    if (iambehind) self ! GetToNextEpoche
    if(globalEpoche - myCurrentEpoche > 1) log.info(s"Regenerating from $myCurrentEpoche to $globalEpoche")
  }
}


class BoardCreator(boardSize : (Int,Int) ) extends Actor with ActorLogging {
  import gameoflife.BoardCreator._

  var simulationSteps : Cancellable = null
  var step : Int = 0
  var myChildresn : List[ActorRef] = null

  override val supervisorStrategy =
    OneForOneStrategy(maxNrOfRetries = 10, withinTimeRange = 1 minute) {
      case _: Exception => Restart
    }


  override def preStart(): Unit = {
    super.preStart()
    val (x:Int,y:Int) = boardSize

    myChildresn = (for{
      i <- 0 to x
      j <- 0 to y
    } yield(i,j)).map {case (i,j) => context.actorOf(Props(classOf[CellActor],(i,j),BoardCreator.neighboursAddresses(boardSize,(i,j)),Random.nextBoolean()),s"Cell-$i,$j")}.toList
  }



  override def receive: Receive = {
    case StartSimulation =>
      simulationSteps = context.system.scheduler.schedule(0 seconds, 1 seconds, self,NextStep)
    case PauseSimulation =>
    case StopSimulation =>
    case NextStep => {
      step+=1
      myChildresn.foreach(u => u ! GoToEpoche(step))
      import akka.pattern.ask
      implicit val timeout : akka.util.Timeout  = 5 seconds
//      val p = myChildresn.toList.map(_ ? GetStateForEpoche(step-1)).map{_.mapTo[CellState]}
//      scala.concurrent.Future.sequence(p).onSuccess {case x: List[CellState] =>
//        log.info(x.mkString("\n"))
//      }

    }
  }
}


object BoardCreator {

  val neighboursAddresses : ((Int,Int),(Int,Int)) => List[(Int,Int)] = {case ((w,h),(x,y)) =>
    val moves = List(-1,0,1)
    for {
      i <- moves
      j <- moves
      newX = i+x
      newY = j+y
      if(0 until w contains newX)
      if(0 until h contains newY)
      if((newX,newY) != (x,y))
    } yield (newX,newY)
  }

  def props(size : (Int,Int)) : Props = Props(classOf[BoardCreator],size)

  case class CellState(position:(Int,Int), state:Boolean,epoche:Int)
  case object StartSimulation
  case object StopSimulation
  case object PauseSimulation
  case object NextStep

}

object Run extends App {
  import gameoflife.BoardCreator._
  implicit val system = ActorSystem("TheGameOfLife")
  val boardSize = (10,10)
  def numberOfCells = boardSize._1 * boardSize._2
  val mainBoard = system.actorOf(BoardCreator.props(boardSize),"BoardCreator")

  import akka.actor.ActorDSL._
  val a = actor("Logger")(new Act {

    var map : Map[Int,List[CellState]] = Map.empty

    become {
      case cs @ CellState(_,_,_) => {
        val otherCells = map.getOrElse(cs.epoche, Nil)
        val newEntry : (Int,List[CellState]) = (cs.epoche -> (cs :: otherCells).sortBy(_.position))
        map = map + newEntry
        if(newEntry._2.size == numberOfCells) {
          val x = boardSize._1
          val y = boardSize._2
          val cells = newEntry._2
          println(s"At epoche:${cs.epoche}")
          for {i <- 0 until y} {
            println(cells.slice(i * x, x *(i + 1)).map(s => if(s.state) 1 else 0).mkString("[",",","]"))
          }
          println()
        }
      }
    }
  })





  mainBoard ! StartSimulation
}
