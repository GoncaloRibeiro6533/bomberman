package com.evolution.player

final class Score private (val value: Int) extends AnyVal

object Score {
  val Zero = new Score(0)

  def apply(value: Int): Option[Score] =
    if (value >= 0) Some(new Score(value))
    else None
}
