package com.evolution.game

import io.circe.generic.JsonCodec

@JsonCodec
case class GameResponse(id: GameId)