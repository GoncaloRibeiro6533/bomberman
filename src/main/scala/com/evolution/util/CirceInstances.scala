package com.evolution.util

import io.circe.{Decoder, Encoder}

import java.time.Instant
import scala.util.Try

object CirceInstances {
  implicit val encodeInstant: Encoder[Instant] =
    Encoder.encodeString.contramap[Instant](_.toString)

  implicit val decodeInstant: Decoder[Instant] =
    Decoder.decodeString.emapTry { str =>
      Try(Instant.parse(str))
    }

}
