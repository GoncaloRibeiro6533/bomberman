package com.evolution.domain.cell

import com.evolution.domain.cell.CellType._

case class Position(cell: Cell, cellType: CellType) {
  def toChar: Char = cellType match {
    case Wall => '#'
    case BombPlacement => '*'
    case PlayerPosition => 'P'
    case DestructibleBlock => '%'
  }
}

