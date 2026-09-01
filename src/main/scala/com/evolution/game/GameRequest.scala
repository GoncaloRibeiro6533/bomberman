package com.evolution.game

import io.circe.generic.JsonCodec

@JsonCodec
final case class GameRequest(nPlayers: Int)
