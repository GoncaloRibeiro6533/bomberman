package com.evolution.bomb

import io.circe.generic.JsonCodec

import java.util.UUID

@JsonCodec
case class BombId(id: UUID) extends AnyVal
