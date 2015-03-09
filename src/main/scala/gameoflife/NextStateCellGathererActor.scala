package gameoflife

import akka.actor.{Props, ActorRef, ActorLogging, Actor}
import scala.concurrent.duration._
import scala.concurrent.ExecutionContext.Implicits.global
import NextStateCellGathererActor._

/**
 * Created by tomaszk on 3/9/15.
 */

case class GatheredData(epoch : Epoch, gatheredState : Set[StateForEpoch] = Set.empty, val howManyAnswersShouldThereBe :Int) {

  def needMore : Boolean = gatheredState.size < howManyAnswersShouldThereBe

  def pushNewState(stateToAdd:StateForEpoch) : GatheredData = {
    copy(gatheredState = gatheredState + stateToAdd)
  }
}

class NextStateCellGathererActor(position : Position, epoch:Epoch, whoToAsk:Set[ActorRef], currentState : CellState ) extends Actor with ActorLogging {
  var retries = 0;
  var gatheredData :  GatheredData = null

  override def preStart(): Unit = {
    gatheredData = GatheredData(epoch = epoch, howManyAnswersShouldThereBe = whoToAsk.size)
    askForValues
    context.system.scheduler.scheduleOnce(1 seconds,self, Retry)
  }


  def askForValues: Unit = {
    whoToAsk.foreach {
      _ ! CellActor.GetStateFromEpoch(epoch)
    }
  }

  override def receive: Receive = {
    case p @ StateForEpoch(_,_,_) => {
      gatheredData = gatheredData.pushNewState(p)
      if (!gatheredData.needMore) {
        val aliveNeighbours : Int = gatheredData.gatheredState.map(_.value).foldLeft(0)((acc, value) => if (value) acc + 1 else acc)
        val newState : Boolean = if(currentState && aliveNeighbours == 3 ) !currentState else currentState
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
  def props(position : Position, epoch:Epoch, whoToAsk:Set[ActorRef], currentState : CellState) = Props(classOf[NextStateCellGathererActor],position,epoch,whoToAsk,currentState)
  case class GatherForEpoch(epoch : Epoch, neighbours : Neighbours)
  case class StateForEpoch(epoch:Epoch, value:CellState, position : Position)
  case object Retry
}