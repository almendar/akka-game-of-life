package gameoflife

import akka.actor._
import gameoflife.BoardCreator.CellStateMsg


/**
 * Created by tomaszk on 3/9/15.
 */

object LoggerActor {
  private def props(boardSize: BoardSize) = Props(classOf[LoggerActor],boardSize)
  private val NAME = "Logger"
  def startMe(boardSize: BoardSize)(implicit as : ActorSystem) : ActorRef = as.actorOf(props(boardSize),NAME)
}

private class LoggerActor(boardSize:BoardSize) extends Actor with ActorLogging{
  var map : Map[Epoch,BoardStateAtTime] = Map.empty
  val numberOfCells = boardSize._1 * boardSize._2

  override def receive = {
    case cs @ CellStateMsg(position, cellState, epoch) => {
      val otherCells: List[CellStateMsg] = map.getOrElse(epoch, List.empty)
      val newEntry: (Int, BoardStateAtTime) = epoch -> (cs :: otherCells)
      map = map + newEntry
      if (newEntry._2.size == numberOfCells) {
        val x = boardSize._1
        val y = boardSize._2
        val cells = newEntry._2
        val formatedRows: List[String] =
          (0 until y).toList map (getBoardRow(_, cells, boardSize) map cellStateToInts) map getStringReprOsRow
        log.info(s"At epoch:$epoch")
        log.info("-" * (x * 2 + 1))
        formatedRows.foreach(log.info)
        log.info("-" * (x * 2 + 1) + "\n")
      }
    }
  }

}
