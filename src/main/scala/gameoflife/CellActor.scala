package gameoflife

import akka.actor.{Props, ActorSelection, ActorLogging, Actor}

/**
 * Created by tomaszk on 3/9/15.
 */
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

    case SetNewStateMsg(state, epoch) if previouseEpochWasComputed(epoch, epochToState) => {
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

object CellActor {
  case class CurrentEpochMsg(value:Epoch)
  case object GetToNextEpoch
  case class GetStateFromEpoch(value : Int)
  case class SetNewStateMsg(state:CellState,epoch:Epoch)
  case class GetStateForEpochMsg(epoch:Epoch)
  case object DoCrashMsg
  case object FailedToGatherInfoMsg

}