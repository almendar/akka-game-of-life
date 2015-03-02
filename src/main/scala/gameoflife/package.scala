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


  val generateNeighbourAddresses : (BoardSize,Position) => List[Position] = {case ((w,h),(x,y)) =>
    val moves = List(-1,0,1)
    for {
      i <- moves
      j <- moves
      newX = i+x
      newY = j+y
      if(0 until w contains newX)
      if(0 until h contains newY)
      if((newX,newY) != (x,y))
    } yield (newX,newY)
  }

}
