package com.evolution.domain.cell

/**
 * Represents a type of given [Cell]
 * */
sealed trait CellType

object CellType {
  case object Wall extends CellType
  case object BombPlacement extends CellType
  case object PlayerPosition extends CellType
  case object DestructibleBlock extends CellType
}