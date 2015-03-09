package gameoflife

import akka.actor.SupervisorStrategy.{Resume, Restart}

import scala.concurrent.ExecutionContext.Implicits.global
import akka.actor._

import scala.concurrent.duration._
import scala.util.Random


case class SimulationParams(startDelay : FiniteDuration, tick:FiniteDuration,
                            firstErrorAfter:FiniteDuration, errorEvery:FiniteDuration,maxNumberOfCrashes :Int = Int.MaxValue)

class BoardCreator(boardSize : (Int,Int), simulationParams: SimulationParams ) extends Actor with ActorLogging {
  import gameoflife.BoardCreator._

  var simulationSteps : Cancellable = null
  var step : Int = 0
  var howManyCellsHaveICrushed = 0;
  var howManyCanICrash = simulationParams.maxNumberOfCrashes

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
      simulationSteps = context.system.scheduler.schedule(simulationParams.startDelay, simulationParams.tick,self, NextStep)
      context.system.scheduler.schedule(simulationParams.firstErrorAfter,simulationParams.errorEvery){crashIfIMay}
    case PauseSimulation =>
    case StopSimulation =>
    case NextStep =>
      step+=1
      context.children.foreach(ch => ch ! CellActor.CurrentEpochMsg(step))

  }
}

object BoardCreator {
  def props(size : BoardSize,simulationParams: SimulationParams) : Props = Props(classOf[BoardCreator],size,simulationParams)

  case class CellStateMsg(position:Position, state:CellState,epoch:Epoch)
  case object StartSimulation
  case object StopSimulation
  case object PauseSimulation
  case object NextStep

}


