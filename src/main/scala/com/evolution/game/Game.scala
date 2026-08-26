package com.evolution.game

import com.evolution.bomb.{Bomb, BombCount, BombId}
import com.evolution.cell.*
import com.evolution.cell.CellType.*
import com.evolution.direction.Direction
import com.evolution.direction.Direction.*
import com.evolution.player.Player.*
import com.evolution.player.{Player, PlayerId}
import io.circe.generic.JsonCodec

import java.time.{Duration, Instant}
import java.util.UUID
import scala.annotation.tailrec

@JsonCodec
sealed trait Game {
  def id: GameId
  def nPlayers: PositiveNumber
}

@JsonCodec
final case class GameWaiting(id: GameId, players: List[JoiningPlayer], nPlayers: PositiveNumber = PositiveNumber.Two)
    extends Game {
  def start(startedAt: Instant): GameRunning = {
    val maze = Maze()
    GameRunning(
      id = id,
      nPlayers = nPlayers,
      activePlayers = maze.insertPlayers(players),
      deadPlayers = Nil,
      bombs = Nil,
      walls = maze.walls,
      blocks = maze.blocks,
      startedAt = startedAt,
      width = maze.width,
      height = maze.height,
      remainingTime = Duration.ofMinutes(2)
    )
  }
}

@JsonCodec
final case class GameRunning(
    id: GameId,
    nPlayers: PositiveNumber,
    activePlayers: List[ActivePlayer],
    deadPlayers: List[DeadPlayer] = Nil,
    bombs: List[Bomb],
    walls: List[Cell],
    blocks: List[Cell],
    startedAt: Instant,
    remainingTime: Duration,
    width: Int,
    height: Int
) extends Game {

  private def getPositions: List[Position] = {
    val playerPositions = activePlayers.map(_.cell.toPosition(PlayerPosition))
    val wallsPositions  = walls.map(_.toPosition(Wall))
    val bombsPositions  = bombs.map(_.cell.toPosition(BombPlacement))
    val blocksPositions = blocks.map(_.toPosition(DestructibleBlock))
    List.concat(playerPositions, wallsPositions, bombsPositions, blocksPositions)
  }

  def getMaze: Maze = {
    Maze(width, height, getPositions)
  }

  def processMovement(playerId: PlayerId, direction: Direction): GameRunning = {
    val updatedGame = for {
      player  <- activePlayers.find(_.id == playerId)
      newCell <- player.cell + direction
      if isWalkableCell(newCell)
      playersUpdated = updatePlayerPosition(player, newCell)
    } yield copy(activePlayers = playersUpdated)
    updatedGame match {
      case Some(value) => value
      case None        => this
    }
  }

  def processBombPlanting(id: PlayerId, instant: Instant): GameRunning = {
    val updatedGame: Option[GameRunning] =
      for {
        player <- activePlayers.find(_.id == id)
        if canPlantBomb(player)
        bombId             = BombId(UUID.randomUUID())
        updatedPlayerBombs = updatePlayerBombs(player, bombCount = BombCount.Zero)
        updatedGameBombs   = Bomb(bombId, player.cell, id, instant) +: bombs
      } yield copy(
        bombs = updatedGameBombs,
        activePlayers = updatedPlayerBombs
      )
    updatedGame match {
      case Some(value) => value
      case None        => this
    }
  }

  def triggerBombs(now: Instant): Either[GameFinished, GameRunning] = {
    val bombsToDetonate = bombs.filter { bomb => bomb.isExpired(now) }
    val cellsAffected: List[Cell] =
      bombsToDetonate.flatMap(bomb => cellsInRadius(bomb.cell, bomb.radius.value)).distinct
    val remainingBlocks = blocks.filterNot(block => cellsAffected.contains(block))
    val killedPlayers   = activePlayers.filter(player => cellsAffected.contains(player.cell)).map(_.toDeadPlayer)
    val killedPlayersId = killedPlayers.map(_.id).toSet
    val players: List[ActivePlayer] = activePlayers
      .filterNot(p => killedPlayersId.contains(p.id))
      .map(player =>
        if (bombsToDetonate.exists(_.plantedBy == player.id)) player.copy(bombs = BombCount.One) else player
      )
    val duration: Duration = Duration.between(startedAt, now)
    val remainingTime      = Duration.ofMinutes(2).minus(duration)
    if (activePlayers.isEmpty || remainingTime.isNegative || remainingTime.isZero) {
      scala.Left(finish)
    } else {
      val remainingBombs = bombs.filterNot(bombsToDetonate.contains(_))
      scala.Right(
        copy(
          activePlayers = players,
          deadPlayers = deadPlayers,
          blocks = remainingBlocks,
          bombs = remainingBombs,
          remainingTime = remainingTime
        )
      )
    }
  }

  private def canPlantBomb(player: ActivePlayer): Boolean =
    player.bombs.value > 0 && !bombs.exists(_.cell == player.cell)

  private def updatePlayerPosition(player: ActivePlayer, value: Cell): List[ActivePlayer] = {
    val others     = activePlayers.filter(_.id != player.id)
    val newPlayers = player.copy(cell = value) +: others
    newPlayers
  }

  private def updatePlayerBombs(player: ActivePlayer, bombCount: BombCount): List[ActivePlayer] = {
    val others     = activePlayers.filter(_.id != player.id)
    val newPlayers = player.copy(bombs = bombCount) +: others
    newPlayers
  }

  private def isWalkableCell(value: Cell) = {
    !walls.contains(value) && !blocks.contains(value) && !bombs.exists(_.cell == value)
  }

  private def cellsInRadius(center: Cell, radius: Int): List[Cell] = {
    @tailrec
    def getCells(cells: List[Cell], center: Cell, radius: Int, direction: Direction): List[Cell] = {
      if (radius == 0) cells
      else {
        center + direction match {
          case Some(value) =>
            getCells(value +: cells, value, radius - 1, direction)
          case None =>
            cells
        }
      }
    }
    List(Up, Down, Left, Right).flatMap(getCells(List(center), center, radius, _))
  }

  private def finish: GameFinished = {
    val winner = activePlayers.sortBy(_.score.value).headOption
    GameFinished(
      id = id,
      nPlayers = nPlayers,
      winner = winner,
      survivors = activePlayers,
      killed = deadPlayers
    )
  }
}

@JsonCodec
final case class GameFinished(
    id: GameId,
    nPlayers: PositiveNumber,
    winner: Option[Player],
    survivors: List[ActivePlayer],
    killed: List[DeadPlayer]
) extends Game
