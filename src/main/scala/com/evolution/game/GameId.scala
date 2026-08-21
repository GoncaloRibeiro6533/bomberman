package com.evolution.game

import io.circe.generic.JsonCodec
import java.util.UUID

@JsonCodec
case class GameId(id: UUID) extends AnyVal
