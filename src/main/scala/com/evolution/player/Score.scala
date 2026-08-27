package com.evolution.player

import io.circe.{Decoder, Encoder}

final class Score private (val value: Int) extends AnyVal{
  def +(value: Int): Score = new Score(Math.max(0, this.value + value))
}

object Score {
  val Zero = new Score(0) // TODO makes sense create a Monoid instead of this and the implict + couldbe a combine???

  def apply(value: Int): Option[Score] =
    if (value >= 0) Some(new Score(value))
    else None

  implicit val encoder: Encoder[Score] = Encoder.encodeInt.contramap(_.value)
  implicit val decoder: Decoder[Score] = Decoder.decodeInt.emap { value =>
    Score(value).toRight(s"Invalid Score: $value")
  }
}
