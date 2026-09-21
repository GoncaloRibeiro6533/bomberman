package com.evolution.cell

import io.circe.{Decoder, Encoder}

case class PositiveNumber private (value: Int) extends AnyVal

object PositiveNumber {
  val One: PositiveNumber = new PositiveNumber(1)
  val Two: PositiveNumber = new PositiveNumber(2)

  implicit val encoder: Encoder[PositiveNumber] = Encoder.encodeInt.contramap(_.value)
  implicit val decoder: Decoder[PositiveNumber] = Decoder.decodeInt.emap { value =>
    PositiveNumber(value).toRight(s"Invalid PositiveNumber: $value")
  }

  def apply(value: Int): Option[PositiveNumber] = if (value >= 0) Some(new PositiveNumber(value))
  else None

  def fromInt(n: Int): Option[PositiveNumber] = apply(n)
}
