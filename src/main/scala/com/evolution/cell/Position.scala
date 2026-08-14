package com.evolution.cell

import com.evolution.cell.CellType.*

case class Position(cell: Cell, cellType: CellType) {
  def toChar: Char = cellType match {
    case Wall              => '#'
    case BombPlacement     => '*'
    case PlayerPosition    => 'P'
    case DestructibleBlock => '%'
  }
}
