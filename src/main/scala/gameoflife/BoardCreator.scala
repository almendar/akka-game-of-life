package gameoflife

import akka.actor.SupervisorStrategy.Restart

import scala.concurrent.ExecutionContext.Implicits.global
import akka.actor._

import scala.concurrent.duration._
import scala.util.Random

/**
 * Created by tomaszk on 2/28/15.
 */

object NextStateCellGathererActor {
  case class GatherForepoch(epoch : Epoch, neighbours : Neighbours)
  case class StateForEpoch(epoch:Epoch, value:CellState, position : Position)
  case object Retry
}

class NextStateCellGathererActor(position : Position, epoch:Epoch, whoToAsk:Neighbours ) extends Actor with ActorLogging {
  import NextStateCellGathererActor._

  case class GatheredData(epoch : Int, gatheredState : Set[StateForEpoch] = Set.empty, val howManyAnswers :Int) {

    def needMore : Boolean = gatheredState.size < howManyAnswers

    def pushNewState(stateToAdd:StateForEpoch) : GatheredData = {
      copy(gatheredState = gatheredState + stateToAdd)
    }
  }

  import NextStateCellGathererActor._

  var gathering :  Option[GatheredData] = None

  override def preStart(): Unit = {
    gathering = Some(GatheredData(epoch = epoch, howManyAnswers = whoToAsk.size))
    askForValues
    context.system.scheduler.scheduleOnce(3 seconds,self, Retry)
  }


  def askForValues: Unit = {
    whoToAsk.foreach { case (i, j) =>
      val selection = context.actorSelection(s"/user/BoardCreator/Cell-$i,$j")
      selection ! CellActor.GetStateFromEpoch(epoch)
    }
  }
  
  override def receive: Receive = {
    case p @ StateForEpoch(_,_,_) => {
      gathering = gathering.map(_.pushNewState(p))
      if (!gathering.map(_.needMore).getOrElse(false)) {
        val newState : Option[Boolean] = gathering.map {x =>
          x.gatheredState.map(_.value).foldLeft(0)((acc, value) => if (value) acc + 1 else acc) % 3 == 0
        }
        context.parent ! CellActor.SetNewState(newState.getOrElse(false), epoch+1)
        //gathering = None
        context.stop(self)
      }
    }
    case Retry => askForValues
  }
}


object CellActor {
  case class GoToEpoch(value:Epoch)
  case object GetToNextEpoch
  case class GetStateFromEpoch(value : Int)
  case class SetNewState(state:CellState,epoch:Epoch)
  case class GetStateForEpoch(epoch:Epoch)


}
class CellActor(position:Position, neighbours : Neighbours, initialState : CellState) extends Actor with ActorLogging {
  import CellActor._


  val previouseEpochWasComputed: (Epoch, History) => CellState =
    (epoch:Epoch,history:History) => history.contains(epoch-1)

  var globalEpoch : Int = 0
  var epochToState : History = Map(0 -> initialState)
  var enquedRequest : List[(ActorSelection,GetStateFromEpoch)] = List.empty

  def isThisCellBehindGlobalEpoch : Boolean = !epochToState.contains(globalEpoch)
  def myCurrentEpoch : Int = epochToState.keys.max

  override def receive : Receive = {
    case GoToEpoch(number) =>
      globalEpoch = number
      scheduleTransitionToNextepochIfNeeded

    case GetToNextEpoch =>
      context.actorOf(Props(classOf[NextStateCellGathererActor],position,myCurrentEpoch,neighbours))
      if(Random.nextInt() % 1000==0) throw new Exception("Random die")

    case GetStateFromEpoch(epoch) =>
      epochToState.get(epoch) match {
        case Some(state) =>
          sender ! NextStateCellGathererActor.StateForEpoch(epoch,state,position)
        case None =>
          enquedRequest = (context.actorSelection(sender.path), GetStateFromEpoch(epoch)) :: enquedRequest
      }

    case SetNewState(state,epoch) if(previouseEpochWasComputed(epoch,epochToState)) => {
      epochToState += (epoch -> state)
      val (toBeProcessed,left) = enquedRequest.partition(p => p._2.value <= myCurrentEpoch)
      enquedRequest = left
      //log.info(epochToState.keys.toList.sorted.mkString(","))
      toBeProcessed.foreach{ case(actor,GetStateFromEpoch(older)) => actor ! NextStateCellGathererActor.StateForEpoch(epoch,epochToState(older),position)
      }
      scheduleTransitionToNextepochIfNeeded
        context.actorSelection("/user/Logger") ! BoardCreator.CellStateMsg(position,state,epoch)
      //log.info(s"${epochToState(myCurrentepoch-1)} -> ${epochToState(myCurrentepoch)} ")
    }
    case p @ _ =>
      println(s"Got message $p")
  }


  def scheduleTransitionToNextepochIfNeeded: Unit = {
    if (isThisCellBehindGlobalEpoch) self ! GetToNextEpoch
    if(globalEpoch - myCurrentEpoch > 1) log.info(s"Regenerating from $myCurrentEpoch to $globalEpoch")
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
      myChildresn.foreach(u => u ! CellActor.GoToEpoch(step))
      import akka.pattern.ask
      implicit val timeout : akka.util.Timeout  = 5 seconds
//      val p = myChildresn.toList.map(_ ? GetStateForepoch(step-1)).map{_.mapTo[CellState]}
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

  def props(size : BoardSize) : Props = Props(classOf[BoardCreator],size)

  case class CellStateMsg(position:Position, state:CellState,epoch:Epoch)
  case object StartSimulation
  case object StopSimulation
  case object PauseSimulation
  case object NextStep

}

object Run extends App {
  import gameoflife.BoardCreator._
  implicit val system = ActorSystem("TheGameOfLife")


  val getBoardRow = ()

  val boardSize : BoardSize = (10,10)
  def numberOfCells = boardSize._1 * boardSize._2
  val mainBoard = system.actorOf(BoardCreator.props(boardSize),"BoardCreator")

  import akka.actor.ActorDSL._
  val a = actor("Logger")(new Act {

    var map : Map[Epoch,BoardStateAtTime] = Map.empty

    become {
      case cs @ CellStateMsg(position,cellState,epoch) => {
        val otherCells: List[CellStateMsg] = map.getOrElse(epoch,List.empty)
        val newEntry : (Int,BoardStateAtTime) = epoch -> (cs :: otherCells)
        map = map + newEntry
        if(newEntry._2.size == numberOfCells) {
          val x = boardSize._1
          val y = boardSize._2
          val cells = newEntry._2
          println(s"At epoch:${cs.epoch}")
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
