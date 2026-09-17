package com.evolution.player

import io.circe.generic.JsonCodec

import java.time.Instant
import java.util.UUID

@JsonCodec
case class TokenInfo(value: UUID)
@JsonCodec
case class Token(token: TokenInfo, playerId: PlayerId, createdAt: Instant)
