package com.evolution.websocket

import com.evolution.direction.Direction
import com.evolution.game.Command
import com.evolution.player.PlayerId
import io.circe.generic.JsonCodec

@JsonCodec
sealed trait WebsocketMessage {
  def toCommand(playerId: PlayerId): Command
}

object WebsocketMessage {
  final case class Movement(direction: Direction) extends WebsocketMessage {
    override def toCommand(playerId: PlayerId): Command = Command.Movement(playerId, direction)
  }
  case object PlantBomb extends WebsocketMessage {
    override def toCommand(playerId: PlayerId): Command.PlantBomb = Command.PlantBomb(playerId)
  }
}
