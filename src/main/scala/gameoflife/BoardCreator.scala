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
        context.parent ! CellActor.SetNewStateMsg(newState.getOrElse(false), epoch+1)
        context.stop(self)
      }
    }
    case Retry =>
      askForValues
      context.system.scheduler.scheduleOnce(3 seconds,self, Retry)
  }
}


object CellActor {
  case class CurrentEpochMsg(value:Epoch)
  case object GetToNextEpoch
  case class GetStateFromEpoch(value : Int)
  case class SetNewStateMsg(state:CellState,epoch:Epoch)
  case class GetStateForEpochMsg(epoch:Epoch)


}
class CellActor(position:Position, neighbours : Neighbours, initialState : CellState) extends Actor with ActorLogging {
  import CellActor._


  val previouseEpochWasComputed: (Epoch, History) => Boolean =
    (epoch:Epoch,history:History) => history.contains(epoch-1)

  var globalEpoch : Int = 0
  var epochToState : History = Map(0 -> initialState)
  var enqueuedRequest : List[(ActorSelection,GetStateFromEpoch)] = List.empty

  private def isThisCellBehindGlobalEpoch : Boolean = !epochToState.contains(globalEpoch)
  private def myCurrentEpoch : Epoch = epochToState.keys.max

  private def scheduleTransitionToNextepochIfNeeded: Unit = {
    if (isThisCellBehindGlobalEpoch) self ! GetToNextEpoch
    if(isMoreThanEpochBehind(2))
      log.info(s"Regenerating from $myCurrentEpoch to $globalEpoch")
  }

  def isMoreThanEpochBehind(distance:Int) : Boolean = {
    globalEpoch - myCurrentEpoch >= distance
  }

  def `maybeCrashThisCell?`: Unit = {
    if (Random.nextInt() % 1000 == 0) throw new scala.Exception("Random die")
  }

  override def receive : Receive = {
    case CurrentEpochMsg(number) =>
      globalEpoch = number
      scheduleTransitionToNextepochIfNeeded

    case GetToNextEpoch =>
      context.actorOf(Props(classOf[NextStateCellGathererActor],position,myCurrentEpoch,neighbours))
      `maybeCrashThisCell?`

    case GetStateFromEpoch(epoch) =>
      epochToState.get(epoch) match {
        case Some(state) =>
          sender ! NextStateCellGathererActor.StateForEpoch(epoch,state,position)
        case None =>
          enqueuedRequest = (context.actorSelection(sender.path), GetStateFromEpoch(epoch)) :: enqueuedRequest
      }

    case SetNewStateMsg(state,epoch) if(previouseEpochWasComputed(epoch,epochToState)) => {
      epochToState += (epoch -> state)
      val (toBeProcessed,tooNewRequests) = enqueuedRequest.partition {
        case (_,requestEpoch) => requestEpoch.value <= myCurrentEpoch
      }

      enqueuedRequest = tooNewRequests
      toBeProcessed.foreach{ case(actor,GetStateFromEpoch(older)) => actor ! NextStateCellGathererActor.StateForEpoch(older,epochToState(older),position) }
      scheduleTransitionToNextepochIfNeeded
      context.actorSelection("/user/Logger") ! BoardCreator.CellStateMsg(position,state,epoch)
    }
  }



}


class BoardCreator(boardSize : (Int,Int) ) extends Actor with ActorLogging {
  import gameoflife.BoardCreator._

  var simulationSteps : Cancellable = null
  var step : Int = 0

  override val supervisorStrategy =
    OneForOneStrategy(maxNrOfRetries = 10, withinTimeRange = 1 minute) {
      case _: Exception => Restart
    }

  private def generateAllCoordinates(boardSize: BoardSize) : List[Position] = {
    val (w:Int,h:Int) = boardSize
    (for {
      i <- 0 to w
      j <- 0 to h
    } yield (i, j)).toList
  }

  private def getNameForCell(position: Position) : String = {
    val (i,j) = position
    s"Cell-$i,$j"
  }

  override def preStart(): Unit = {
    super.preStart()
     generateAllCoordinates(boardSize)
      .foreach {pos =>
      context.actorOf(Props(classOf[CellActor],
          pos,generateNeighbourAddresses(boardSize,pos),Random.nextBoolean()),getNameForCell(pos)
        )
    }
  }



  override def receive: Receive = {
    case StartSimulation =>
      simulationSteps = context.system.scheduler.schedule(0 seconds, 1 seconds, self, NextStep)
    case PauseSimulation =>
    case StopSimulation =>
    case NextStep =>
      step+=1
      context.children.foreach(ch => ch ! CellActor.CurrentEpochMsg(step))

  }
}


object BoardCreator {



  def props(size : BoardSize) : Props = Props(classOf[BoardCreator],size)

  case class CellStateMsg(position:Position, state:CellState,epoch:Epoch)
  case object StartSimulation
  case object StopSimulation
  case object PauseSimulation
  case object NextStep

}

object Run extends App {

  import akka.actor.ActorDSL._
  import gameoflife.BoardCreator._

  implicit val system = ActorSystem("TheGameOfLife")
  val getBoardRow : (Int,BoardStateAtTime,BoardSize) => List[CellStateMsg] = (row,board,boardSize) => board.slice(row * boardSize._1, (row+1) * boardSize._1)
  val cellStateToInts : (CellStateMsg) => Int = cellState => if(cellState.state) 1 else 0
  val getStringReprOsRow : (List[Int]) => String = cells => cells.mkString("[",",","]")



  val boardSize : BoardSize = (10,10)
  def numberOfCells = boardSize._1 * boardSize._2
  val mainBoard = system.actorOf(BoardCreator.props(boardSize),"BoardCreator")

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
          val formatedRows : List[String] =
            (0 until y).toList map( getBoardRow(_,cells,boardSize) map cellStateToInts) map getStringReprOsRow

          println(s"At epoch:$epoch")
          formatedRows.foreach(println)
        }
      }
    }
  })





  mainBoard ! StartSimulation
}
