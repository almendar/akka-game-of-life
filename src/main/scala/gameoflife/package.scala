import gameoflife.BoardCreator.CellStateMsg

/**
 * Created by tomaszk on 3/2/15.
 */
package object gameoflife {

  type Position = (Int,Int)
  type BoardSize = (Int,Int)
  type Neighbours = List[Position]
  type Epoch = Int
  type CellState = Boolean
  type BoardStateAtTime = List[CellStateMsg]
  type History = Map[Epoch,CellState]
}
