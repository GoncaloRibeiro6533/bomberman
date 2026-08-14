package com.evolution.bomb

class BombId private (val id: Long) extends AnyVal

object BombId {
  def apply(value: Long): Option[BombId] = if (value < 0) None else Some(new BombId(value))
}
