package com.evolution.http.dtos

import com.evolution.player.Username
import io.circe.generic.JsonCodec

object PlayerDto {

  @JsonCodec
  final case class PlayerInDto(username: Username)

}
