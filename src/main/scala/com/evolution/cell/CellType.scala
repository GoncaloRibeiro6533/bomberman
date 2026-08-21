package com.evolution.cell

import io.circe.generic.JsonCodec

/** Represents a type of given [Cell]
  */
@JsonCodec
sealed trait CellType

object CellType {
  case object Wall              extends CellType
  case object BombPlacement     extends CellType
  case object PlayerPosition    extends CellType
  case object DestructibleBlock extends CellType

  def fromChar(char: Char): Option[CellType] = char match {
    case '#' => Some(Wall)
    case '%' => Some(DestructibleBlock)
    case '*' => Some(BombPlacement)
    case 'P' => Some(PlayerPosition)
    case _   => None
  }
}
