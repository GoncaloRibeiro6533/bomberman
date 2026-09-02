package com.evolution.player

import io.circe.generic.JsonCodec

@JsonCodec
final case class PlayerRequest(username: Username)
