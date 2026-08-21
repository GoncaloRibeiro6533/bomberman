package com.evolution.player

import io.circe.{Decoder, Encoder}

final class Score private (val value: Int) extends AnyVal

object Score {
  val Zero = new Score(0)

  def apply(value: Int): Option[Score] =
    if (value >= 0) Some(new Score(value))
    else None

  implicit val encoder: Encoder[Score] = Encoder.encodeInt.contramap(_.value)
  implicit val decoder: Decoder[Score] = Decoder.decodeInt.emap { value =>
    Score(value).toRight(s"Invalid Score: $value")
  }
}
