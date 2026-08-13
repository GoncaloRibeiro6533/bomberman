package com.evolution.domain.game

import com.evolution.domain.bomb.Bomb
import com.evolution.domain.cell.CellType._
import com.evolution.domain.cell.{Cell, Position}
import com.evolution.domain.player.Player._

import java.time.Instant

final case class Game(
                       id: GameId,
                       activePlayers: List[ActivePlayer],
                       deadPlayers: List[DeadPlayer] = Nil,
                       bombs: List[Bomb],
                       walls: List[Cell],
                       blocks: List[Cell],
                       startedAt: Instant,
                       private val width: Int,
                       private val height: Int
                     ){

  def getPositions: List[Position] = {
    val playerPositions = this.activePlayers.map(_.cell.toPosition(PlayerPosition))
    val walls = this.walls.map(_.toPosition(Wall))
    val bombs = this.bombs.map(_.cell.toPosition(BombPlacement))
    val blocks = this.blocks.map(_.toPosition(DestructibleBlock))
    List.concat(playerPositions, walls, bombs, blocks)
  }

  def getMaze: Maze = {
    Maze(width, height, getPositions)
  }
}