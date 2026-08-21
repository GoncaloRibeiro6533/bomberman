package com.evolution.bomb

import io.circe.{Decoder, Encoder}

/** Represents an amount of bombs
  *
  * @param value
  *   Number of existing bombs
  */
final class BombCount private (val value: Int) extends AnyVal

object BombCount {
  val Zero: BombCount = new BombCount(0)
  val One: BombCount  = new BombCount(1)

  implicit val encoder: Encoder[BombCount] = Encoder.encodeInt.contramap(_.value)
  implicit val decoder: Decoder[BombCount] = Decoder.decodeInt.emap { value =>
    BombCount(value).toRight(s"Invalid BombCount: $value")
  }

  def apply(value: Int): Option[BombCount] =
    if (value >= 0) Some(new BombCount(value)) else None
}
