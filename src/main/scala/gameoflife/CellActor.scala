package gameoflife

import akka.actor._

import scala.util.Random

/**
 * Created by tomaszk on 3/9/15.
 */
class CellActor(position:Position, initialState : CellState, val loggerRef:ActorRef) extends Actor with ActorLogging {
  import CellActor._


  @throws[Exception](classOf[Exception])
  override def preStart(): Unit = {
    super.preStart()
    println(s"Cell${position}: I'm alive")
  }

  @throws[Exception](classOf[Exception])
  override def postRestart(reason: Throwable): Unit = {
    super.postRestart(reason)
    context.parent ! BoardCreator.SendMeMyNeighbours(position)
    println(s"Cell${position}: I've restarted")
  }



  val previouseEpochWasComputed: (Epoch, History) => Boolean =
    (epoch:Epoch,history:History) => history.contains(epoch-1)

  var waitingForNewState = false
  var globalEpoch : Int = 0
  var epochToState : History = Map(0 -> initialState)
  var enqueuedRequest : List[(ActorSelection,GetStateFromEpoch)] = List.empty
  var neighboursRefs : Set[ActorRef] = Set.empty

  private def isThisCellBehindGlobalEpoch : Boolean = !epochToState.contains(globalEpoch)
  private def myCurrentEpoch : Epoch = epochToState.keys.max

  private def scheduleTransitionToNextepochIfNeeded: Unit = {
    if (isThisCellBehindGlobalEpoch
      && !waitingForNewState
    ) {
      self ! GetToNextEpoch
    }
  }

  def isMoreThanEpochBehind(distance:Int) : Boolean = {
    globalEpoch - myCurrentEpoch >= distance
  }

  def crashThisCell: Unit = {
    throw new scala.Exception(s"Random die of Cell${position}} at state ${myCurrentEpoch} with global $globalEpoch")
  }

  def receive : Receive = {
    case NeighboursRefs(actors) =>
      neighboursRefs.foreach(context.unwatch(_))
      neighboursRefs = actors.toSet
      neighboursRefs.foreach(context.watch(_))

    case CurrentEpochMsg(number) =>
      globalEpoch = number
      scheduleTransitionToNextepochIfNeeded

    case GetToNextEpoch =>
      waitingForNewState = true
      context.actorOf(NextStateCellGathererActor.props(position, myCurrentEpoch, neighboursRefs, epochToState(myCurrentEpoch)))

    case GetStateFromEpoch(epoch) =>
      epochToState.get(epoch) match {
        case Some(state) =>
          sender ! NextStateCellGathererActor.StateForEpoch(epoch, state, position)
        case None =>
          enqueuedRequest = (context.actorSelection(sender.path), GetStateFromEpoch(epoch)) :: enqueuedRequest
      }

    case SetNewStateMsg(state, epoch) if previouseEpochWasComputed(epoch, epochToState) => {
      println(s"Cell${position}: Changing state ${epochToState(myCurrentEpoch)} -> $state")
      epochToState += (epoch -> state)
      val (toBeProcessed, tooNewRequests) = enqueuedRequest.partition {
        case (_, requestEpoch) => epochToState.contains(requestEpoch.value)
      }
      waitingForNewState = false
      scheduleTransitionToNextepochIfNeeded
      enqueuedRequest = tooNewRequests
      toBeProcessed.foreach { case (actor, GetStateFromEpoch(older)) => actor ! NextStateCellGathererActor.StateForEpoch(older, epochToState(older), position)}
      loggerRef ! BoardCreator.CellStateMsg(position, state, myCurrentEpoch)
      //`maybeCrashThisCell?`
    }
    case FailedToGatherInfoMsg =>
      waitingForNewState = false
      context.parent ! BoardCreator.SendMeMyNeighbours(position)
    case CellActor.DoCrashMsg =>
        crashThisCell
    case Terminated(actorRef) =>
  }



}

object CellActor {
  case class CurrentEpochMsg(value:Epoch)
  case class NeighboursRefs(actors : List[ActorRef])
  case object GetToNextEpoch
  case class GetStateFromEpoch(value : Int)
  case class SetNewStateMsg(state:CellState,epoch:Epoch)
  case class GetStateForEpochMsg(epoch:Epoch)
  case object DoCrashMsg
  case object FailedToGatherInfoMsg

}