package com.evolution.player

import io.circe.{Decoder, Encoder}
import io.circe.generic.JsonCodec

@JsonCodec
final case class PlayerCredentials(username: Username, password: PasswordIn)

sealed class PasswordIn private (val value: String)

object PasswordIn {
  def apply(value: String): Option[PasswordIn] = if (value.length < 12) None else Some(new PasswordIn(value))

  implicit val encoder: Encoder[PasswordIn] = Encoder.encodeString.contramap(_.value)
  implicit val decoder: Decoder[PasswordIn] = Decoder.decodeString.emap { value =>
    PasswordIn(value).toRight(s"Invalid Password: $value")
  }
}
