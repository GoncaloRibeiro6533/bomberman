package com.evolution.player

import com.evolution.cell.Cell
import com.evolution.bomb.BombCount

class PlayerId private (val value: Long) extends AnyVal

object PlayerId {
  def apply(value: Long): Option[PlayerId] = if (value < 0) None else Some(new PlayerId(value))
}

sealed trait Player {
  def id: PlayerId
  def username: Username
}

object Player {

  final case class JoiningPlayer(id: PlayerId, username: Username) {
    def toActivePlayer(cell: Cell) = ActivePlayer(id = id, username = username, cell = cell)
  }
  final case class ActivePlayer(
      id: PlayerId,
      username: Username,
      cell: Cell,
      bombs: BombCount = BombCount.One,
      score: Score = Score.Zero
  ) extends Player {

    def toDeadPlayer: DeadPlayer = DeadPlayer(id, username, score)
  }

  final case class DeadPlayer(id: PlayerId, username: Username, score: Score) extends Player
}
