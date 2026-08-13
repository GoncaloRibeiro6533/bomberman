package com.evolution.domain.player

import com.evolution.domain.bomb.BombCount
import com.evolution.domain.cell.Cell

case class PlayerId private(value: Long) extends AnyVal

object PlayerId {
  def apply(value: Long): Option[PlayerId] = if(value < 0) None else Some(new PlayerId(value))
}

sealed trait Player{
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
                                 score: Score= Score.Zero
                               ) extends Player

  final case class DeadPlayer(id: PlayerId, username: Username, score: Score) extends Player
}