package com.evolution.domain.bomb

/**
 * Represents an amount of bombs
 * @param value Number of existing bombs
 */
final case class BombCount private (value: Int) extends AnyVal

object BombCount {
  val Zero = new BombCount(0)
  val One = new BombCount(1)

  def apply(value: Int): Option[BombCount] =
    if (value >= 0) Some(new BombCount(value)) else None
}