package com.evolution.player

import io.circe.{Decoder, Encoder}

final class Username private (val value: String) extends AnyVal

object Username {
  def apply(username: String): Option[Username] = if (username.length > 3) Some(new Username(username))
  else None

  implicit val encoder: Encoder[Username] = Encoder.encodeString.contramap(_.value)
  implicit val decoder: Decoder[Username] = Decoder.decodeString.emap { value =>
    Username(value).toRight(s"Invalid Username: $value")
  }
}
