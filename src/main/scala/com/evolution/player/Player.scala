package com.evolution.player

import com.evolution.bomb.BombCount
import com.evolution.cell.Cell
import io.circe.generic.JsonCodec

import java.util.UUID

@JsonCodec
case class PlayerId(value: UUID) extends AnyVal

@JsonCodec
sealed trait Player {
  def id: PlayerId
  def username: Username
}

object Player {

  @JsonCodec
  final case class IdlePlayer(id: PlayerId, username: Username) extends Player {
    def toJoiningPlayer: JoiningPlayer = JoiningPlayer(id, username)
  }

  @JsonCodec
  final case class JoiningPlayer(id: PlayerId, username: Username) extends Player {
    def toActivePlayer(cell: Cell) = ActivePlayer(id = id, username = username, cell = cell, score = Score.Zero)
  }
  @JsonCodec
  final case class ActivePlayer(
      id: PlayerId,
      username: Username,
      cell: Cell,
      bombs: BombCount = BombCount.One,
      score: Score
  ) extends Player {
    def toDeadPlayer: DeadPlayer = DeadPlayer(id, username, score)
  }

  @JsonCodec
  final case class DeadPlayer(id: PlayerId, username: Username, score: Score) extends Player
}
