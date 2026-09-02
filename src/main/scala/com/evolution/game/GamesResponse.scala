package com.evolution.game

import io.circe.generic.JsonCodec

@JsonCodec
final case class GamesResponse(games: List[GameResponse])
