package com.evolution.domain

import com.evolution.domain.player.PlayerId

sealed trait Command {
  def playerId: PlayerId
}

object Command {
  final case class Movement(playerId: PlayerId, direction: Direction) extends Command
  final case class PlantBomb(playerId: PlayerId) extends Command
}