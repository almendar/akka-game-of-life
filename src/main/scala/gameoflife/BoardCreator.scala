package gameoflife

import akka.actor.SupervisorStrategy.{Resume, Restart}
import akka.event.LoggingAdapter

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

class NextStateCellGathererActor(position : Position, epoch:Epoch, whoToAsk:Neighbours, currentState : CellState ) extends Actor with ActorLogging {
  import NextStateCellGathererActor._

  var retries = 0;

  case class GatheredData(epoch : Epoch, gatheredState : Set[StateForEpoch] = Set.empty, val howManyAnswersShouldThereBe :Int) {

    def needMore : Boolean = gatheredState.size < howManyAnswersShouldThereBe

    def pushNewState(stateToAdd:StateForEpoch) : GatheredData = {
      copy(gatheredState = gatheredState + stateToAdd)
    }
  }


  var gatheredData :  GatheredData = null

  override def preStart(): Unit = {
    gatheredData = GatheredData(epoch = epoch, howManyAnswersShouldThereBe = whoToAsk.size)
    askForValues
    context.system.scheduler.scheduleOnce(1 seconds,self, Retry)
  }


  def askForValues: Unit = {
    whoToAsk.foreach { case (i, j) =>
      val selection = context.actorSelection(s"/user/BoardCreator/Cell-$i,$j")
      selection ! CellActor.GetStateFromEpoch(epoch)
    }
  }
  
  override def receive: Receive = {
    case p @ StateForEpoch(_,_,_) => {
      gatheredData = gatheredData.pushNewState(p)
      if (!gatheredData.needMore) {
        val aliveNeighbours : Int = gatheredData.gatheredState.map(_.value).foldLeft(0)((acc, value) => if (value) acc + 1 else acc)
        val newState : Boolean = if(currentState && aliveNeighbours == 3 ) !currentState else currentState
//          if(currentState == true) {
//            if(aliveNeighbours < 2) false
//            else if(aliveNeighbours == 2 || aliveNeighbours == 3) true
//            else false //if(aliveNeighbours > 3) false
////            else currentState
//        } else { //is dead
//          if(aliveNeighbours == 3) true
//          else currentState
//        }
        context.parent ! CellActor.SetNewStateMsg(newState, epoch + 1)
        context.stop(self)
      }
    }
    case Retry =>
      retries += 1
      if(retries >= 2) {
        context.parent ! CellActor.FailedToGatherInfoMsg
        context.stop(self)
      }
      else {
        askForValues
        context.system.scheduler.scheduleOnce(1 seconds,self, Retry)
      }
  }
}


object CellActor {
  case class CurrentEpochMsg(value:Epoch)
  case object GetToNextEpoch
  case class GetStateFromEpoch(value : Int)
  case class SetNewStateMsg(state:CellState,epoch:Epoch)
  case class GetStateForEpochMsg(epoch:Epoch)
  case object DoCrashMsg
  case object FailedToGatherInfoMsg

}
class CellActor(position:Position, neighbours : Neighbours, initialState : CellState) extends Actor with ActorLogging {
  import CellActor._

  @throws[Exception](classOf[Exception])
  override def postRestart(reason: Throwable): Unit = {
    super.postRestart(reason)
    log.info("I need to regenerate")
  }

  val previouseEpochWasComputed: (Epoch, History) => Boolean =
    (epoch:Epoch,history:History) => history.contains(epoch-1)

  var waitingForNewState = false
  var globalEpoch : Int = 0
  var epochToState : History = Map(0 -> initialState)
  var enqueuedRequest : List[(ActorSelection,GetStateFromEpoch)] = List.empty

  private def isThisCellBehindGlobalEpoch : Boolean = !epochToState.contains(globalEpoch)
  private def myCurrentEpoch : Epoch = epochToState.keys.max

  private def scheduleTransitionToNextepochIfNeeded: Unit = {
    if (isThisCellBehindGlobalEpoch
      && !waitingForNewState
    ) {
      self ! GetToNextEpoch
    }
//    if(isMoreThanEpochBehind(2))
//      log.info(s"Regenerating from $myCurrentEpoch to $globalEpoch")
  }

  def isMoreThanEpochBehind(distance:Int) : Boolean = {
    globalEpoch - myCurrentEpoch >= distance
  }

  def crashThisCell: Unit = {
    throw new scala.Exception(s"Random die of Cell${position}} at state ${myCurrentEpoch} with global $globalEpoch")
  }

  override def receive : Receive = {
    case CurrentEpochMsg(number) =>
      globalEpoch = number
      scheduleTransitionToNextepochIfNeeded

    case GetToNextEpoch =>
      waitingForNewState = true
      context.actorOf(Props(classOf[NextStateCellGathererActor], position, myCurrentEpoch, neighbours, epochToState(myCurrentEpoch)))

    case GetStateFromEpoch(epoch) =>
      epochToState.get(epoch) match {
        case Some(state) =>
          sender ! NextStateCellGathererActor.StateForEpoch(epoch, state, position)
        case None =>
          enqueuedRequest = (context.actorSelection(sender.path), GetStateFromEpoch(epoch)) :: enqueuedRequest
      }

    case SetNewStateMsg(state, epoch) if (previouseEpochWasComputed(epoch, epochToState)) => {
      epochToState += (epoch -> state)
      val (toBeProcessed, tooNewRequests) = enqueuedRequest.partition {
        case (_, requestEpoch) => epochToState.contains(requestEpoch.value)
      }
      scheduleTransitionToNextepochIfNeeded
      enqueuedRequest = tooNewRequests
      toBeProcessed.foreach { case (actor, GetStateFromEpoch(older)) => actor ! NextStateCellGathererActor.StateForEpoch(older, epochToState(older), position)}
      context.actorSelection("/user/Logger") ! BoardCreator.CellStateMsg(position, state, myCurrentEpoch)
      waitingForNewState = false
      //`maybeCrashThisCell?`
    }
    case FailedToGatherInfoMsg =>
      waitingForNewState = false
    case CellActor.DoCrashMsg =>
        crashThisCell
  }



}


class BoardCreator(boardSize : (Int,Int) ) extends Actor with ActorLogging {
  import gameoflife.BoardCreator._

  var simulationSteps : Cancellable = null
  var step : Int = 0
  var howManyCellsHaveICrushed = 0;
  var howManyCanICrash = 0

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

  def pickRandomChild : ActorRef = {
    val children = context.children.toList
    val randomPick = Random.nextInt(children.size)
    children(randomPick)
  }

  def crashIfIMay = {
    if(howManyCellsHaveICrushed < howManyCanICrash) {
      pickRandomChild ! CellActor.DoCrashMsg
      howManyCellsHaveICrushed+=1
    }
  }

  override def receive: Receive = {
    case StartSimulation =>
      simulationSteps = context.system.scheduler.schedule(0 seconds, 1 second, self, NextStep)
      context.system.scheduler.schedule(1 seconds, 1 seconds){crashIfIMay}
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



  val boardSize : BoardSize = (30,30)
  def numberOfCells = boardSize._1 * boardSize._2
  val mainBoard = system.actorOf(BoardCreator.props(boardSize),"BoardCreator")

  val a = actor("Logger")(new Act {

    var map : Map[Epoch,BoardStateAtTime] = Map.empty
    var log : LoggingAdapter = akka.event.Logging(context.system, this)

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
          log.info(s"At epoch:$epoch")
          formatedRows.foreach(log.info)
        }
      }
    }
  })

  mainBoard ! StartSimulation
}
