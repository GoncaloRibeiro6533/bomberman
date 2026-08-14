package com.evolution.bomb

/** Represents an amount of bombs
  * @param value
  *   Number of existing bombs
  */
final class BombCount private (val value: Int) extends AnyVal

object BombCount {
  val Zero: BombCount = new BombCount(0)
  val One: BombCount  = new BombCount(1)

  def apply(value: Int): Option[BombCount] =
    if (value >= 0) Some(new BombCount(value)) else None
}
