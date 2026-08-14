package com.evolution.game

class GameId private (val id: Long) extends AnyVal
object GameId {
  def apply(value: Long): Option[GameId] = if (value < 0) None else Some(new GameId(value))
}
