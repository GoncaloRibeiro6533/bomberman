package com.evolution.http.dtos

import com.evolution.game.GameId
import io.circe.generic.JsonCodec

object GameDto {
  @JsonCodec
  case class GameIdDto(id: GameId)
  @JsonCodec
  final case class GameInDto(nPlayers: Int)

  @JsonCodec
  final case class GamesOut(games: List[GameIdDto])

}
