package com.evolution.player

import com.evolution.player.Player.IdlePlayer
import io.circe.generic.JsonCodec

@JsonCodec
final case class AuthPlayer(player: IdlePlayer, token: Token)
