package com.evolution.direction

import com.evolution.cell.*
import io.circe.generic.JsonCodec

@JsonCodec
sealed abstract class Direction(val colDiff: Int, val lineDiff: Int)

object Direction {

  case object Left  extends Direction(-1, 0)
  case object Right extends Direction(+1, 0)
  case object Up    extends Direction(0, -1)
  case object Down  extends Direction(0, +1)

  trait DirectionOps[T] {

    def +(direction: Direction, value: T): Option[T]
  }

  implicit val DirectionCellOps: DirectionOps[Cell] = (direction: Direction, value: Cell) => {
    val newCol  = value.col.value.value + direction.colDiff
    val newLine = value.line.value.value + direction.lineDiff
    for {
      colNum  <- PositiveNumber(newCol)
      lineNum <- PositiveNumber(newLine)
    } yield value.copy(
      col = Column(colNum),
      line = Line(lineNum)
    )
  }

  implicit class DirectionOp[T](val value: T) {
    def +(direction: Direction)(implicit ops: DirectionOps[T]): Option[T] = ops.+(direction, value)
  }

}
