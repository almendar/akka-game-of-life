package gameoflife

import akka.actor.{ActorLogging, Actor}
import scala.concurrent.duration._
import scala.concurrent.ExecutionContext.Implicits.global
/**
 * Created by tomaszk on 3/9/15.
 */
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

object NextStateCellGathererActor {
  case class GatherForEpoch(epoch : Epoch, neighbours : Neighbours)
  case class StateForEpoch(epoch:Epoch, value:CellState, position : Position)
  case object Retry
}