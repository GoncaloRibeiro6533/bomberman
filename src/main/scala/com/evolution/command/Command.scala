package com.evolution.command

import com.evolution.direction.Direction
import com.evolution.player.PlayerId
import io.circe.generic.JsonCodec

@JsonCodec
sealed trait Command

object Command {
  case object Tick                                                    extends Command
  final case class Movement(playerId: PlayerId, direction: Direction) extends Command
  final case class PlantBomb(playerId: PlayerId)                      extends Command
}
