package com.evolution.game

import io.circe.generic.JsonCodec

import java.util.UUID
import scala.util.Try

@JsonCodec
case class GameId(id: UUID) extends AnyVal

object GameId {

  object Var {
    def unapply(value: String): Option[GameId] = Try(UUID.fromString(value)).toOption.map(uuid => GameId(uuid))
  }
}
