package gameoflife

import akka.actor._
import akka.actor.SupervisorStrategy.{Resume, Restart}
import akka.cluster.{MemberStatus, Cluster, Member}
import akka.cluster.ClusterEvent._
import akka.remote.RemoteScope
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration._
import scala.util.Random


case class SimulationParams(startDelay : FiniteDuration, tick:FiniteDuration, firstErrorAfter:FiniteDuration,
                            errorEvery:FiniteDuration,maxNumberOfCrashes :Int = Int.MaxValue)



class BoardCreator(boardSize : (Int,Int), simulationParams: SimulationParams ) extends Actor with ActorLogging {
  import gameoflife.BoardCreator._


  val cluster = Cluster(context.system)
  val initialState: Map[Position, CellState] = (generateAllCoordinates(boardSize) zip List.fill(generateAllCoordinates(boardSize).size)(Random.nextBoolean())).toMap
  val loggerActor = LoggerActor.startMe(boardSize)(context.system)

  var simulationSteps : Cancellable = null
  var step : Int = 0
  var howManyCellsHaveICrushed = 0;
  var howManyCanICrash = simulationParams.maxNumberOfCrashes
  var cells : Map[Position,ActorRef] = Map.empty
  var nodes : Set[Member] = Set.empty

  def getRandomNode : Member = {
      val size = nodes.size
    nodes.drop(Random.nextInt(size)).head
  }





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


  private def getAllNeighbours(at:Position) : List[ActorRef] = {
    generateNeighbourAddresses(boardSize,at).map(cells(_))
  }

  private def getNameForCell(position: Position) : String = {
    val (i,j) = position
    s"Cell-$i,$j"
  }

  def deplyActorWithPosition(at:Position) : ActorRef = {
    val node = getRandomNode
    context.actorOf(Props(classOf[CellActor],
      at,initialState(at),loggerActor).withDeploy(Deploy(scope = RemoteScope(node.address))),getNameForCell(at)
    )
  }

  override def preStart(): Unit = {
    super.preStart()
      cluster.subscribe(self, initialStateMode = InitialStateAsEvents,
        classOf[MemberEvent], classOf[UnreachableMember])
  }


  def createAllInitialActors: Unit = {
    generateAllCoordinates(boardSize)
      .foreach { pos: Position =>
      val cell: ActorRef = deplyActorWithPosition(pos)
      context.watch(cell)
      cells = cells + (pos -> cell)
    }
    cells.foreach { case (pos, actor) =>
      actor ! CellActor.NeighboursRefs(getAllNeighbours(pos))
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
      createAllInitialActors
      simulationSteps = context.system.scheduler.schedule(simulationParams.startDelay, simulationParams.tick,self, NextStep)
      context.system.scheduler.schedule(simulationParams.firstErrorAfter,simulationParams.errorEvery){crashIfIMay}
    case PauseSimulation =>
      simulationSteps.cancel()
    case ResumeSimulation =>
      simulationSteps = context.system.scheduler.schedule(simulationParams.startDelay, simulationParams.tick,self, NextStep)
    case NextStep =>
      step+=1
      println(s"Epoch: $step")
      cells.foreach{case (poc,ch) => ch ! CellActor.CurrentEpochMsg(step)}
    case SendMeMyNeighbours(position) =>
      sender ! CellActor.NeighboursRefs(generateNeighbourAddresses(boardSize,position).map(cells(_)))

    case Terminated(actorRef) =>
      onCellTermination(actorRef)

    case state: CurrentClusterState =>
      nodes = nodes ++  state.members.filter(_.status == MemberStatus.up)
    case MemberUp(member) if(member.hasRole("backend")) =>
      nodes = nodes + member
    case UnreachableMember(member) if(member.hasRole("backend")) =>

    case MemberRemoved(member, previousStatus) if(member.hasRole("backend")) =>
      nodes = nodes - member

    case _: MemberEvent => // ignore

  }



  def onCellTermination(actorRef: ActorRef): Unit = {
    cells.filter { case (x, ref) =>
      actorRef == ref
    }.toList.headOption match {
      case Some((deadCellPosition: Position, _)) =>
        cells = cells - deadCellPosition
        val newActorRef: ActorRef = deplyActorWithPosition(deadCellPosition)
        cells = cells + (deadCellPosition -> newActorRef)
        context.watch(newActorRef)
        val neightoubrs = getAllNeighbours(deadCellPosition)
        newActorRef ! CellActor.NeighboursRefs(neightoubrs)
        generateNeighbourAddresses(boardSize, deadCellPosition).foreach { posOfactorsToNotify =>
          cells(posOfactorsToNotify) ! CellActor.NeighboursRefs(getAllNeighbours(posOfactorsToNotify))
        }
      case p@_ => log.error(s"This should not happen: Got terminated on actor ${actorRef} that was not in my register")
    }
  }
}

object BoardCreator {
  def props(size : BoardSize,simulationParams: SimulationParams) : Props = Props(classOf[BoardCreator],size,simulationParams)
  case class CellStateMsg(position:Position, state:CellState,epoch:Epoch)
  case object StartSimulation
  case object ResumeSimulation
  case object PauseSimulation
  case object NextStep
  case class SendMeMyNeighbours(position:Position)


}


