package com.evolution.domain.game

case class GameId private(id: Long) extends AnyVal
object GameId {
  def apply(value: Long): Option[GameId] = if(value < 0) None else Some(new GameId(value))
}
