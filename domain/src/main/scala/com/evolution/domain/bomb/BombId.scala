package com.evolution.domain.bomb

import java.util.UUID

case class BombId private (id: Long) extends AnyVal

object BombId {
  def apply(value: Long): Option[BombId] = if (value < 0) None else Some(new BombId(value))
}
