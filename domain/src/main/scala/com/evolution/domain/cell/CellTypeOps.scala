package com.evolution.domain.cell

import com.evolution.domain.cell.CellType._

object CellTypeOps {

  trait CellOps[T] {
    def toCellType(value: T): Option[CellType]
  }

  implicit val charCellOps: CellOps[Char] = new CellOps[Char] {
    override def toCellType(value: Char): Option[CellType] = value match {
      case '#' => Some(Wall)
      case '%' => Some(DestructibleBlock)
      case '*' => Some(BombPlacement)
      case 'P' => Some(PlayerPosition)
      case _ => None
    }
  }

  implicit class CellOperations[T](val value: T) {
    def toCellType(implicit cellOps: CellOps[T]): Option[CellType] = cellOps.toCellType(value)
  }
}