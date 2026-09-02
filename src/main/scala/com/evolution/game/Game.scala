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
      remainingTime = Duration.ofMinutes(2),
      duration = Duration.ofMinutes(2)
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
    duration: Duration,
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
    val bombsToDetonate            = bombs.filter { bomb => bomb.isExpired(now) }
    val elapsed: Duration          = Duration.between(startedAt, now)
    val newRemainingTime: Duration = duration.minus(elapsed)
    if (activePlayers.isEmpty || newRemainingTime.isNegative || newRemainingTime.isZero) { // print at least once the empty board
      scala.Left(finish(activePlayers, deadPlayers))
    } else {
      if (bombsToDetonate.nonEmpty) {
        val bombsWithAffectedCells: Map[Bomb, List[Cell]] =
          bombsToDetonate.foldRight(Map[Bomb, List[Cell]]().empty)((bomb, map) =>
            map.updated(bomb, cellsInRadius(bomb.cell, bomb.radius.value))
          )
        val remainingBlocks = blocks.filterNot(block => bombsWithAffectedCells.values.flatten.toSet.contains(block))
        val killedPlayers: Map[ActivePlayer, Killer] = getKilledPlayers(activePlayers, bombsWithAffectedCells)
        val (newDeadPlayers, newActivePlayers) =
          getPlayersWithScoreUpdated(activePlayers, deadPlayers, killedPlayers, bombsToDetonate)
        val remainingBombs = bombs.filterNot(bombsToDetonate.contains(_))
        scala.Right(
          copy(
            activePlayers = newActivePlayers,
            deadPlayers = newDeadPlayers,
            blocks = remainingBlocks,
            bombs = remainingBombs,
            remainingTime = newRemainingTime
          )
        )
      } else scala.Right(copy(remainingTime = newRemainingTime))
    }
  }

  private type Killer = PlayerId
  private def getKilledPlayers(
      activePlayers: List[ActivePlayer],
      affectedCells: Map[Bomb, List[Cell]]
  ): Map[ActivePlayer, Killer] = {
    val killedPlayers: Map[ActivePlayer, Killer] = activePlayers.foldRight(Map[ActivePlayer, Killer]().empty) {
      (player, map) =>
        affectedCells.find { case (_, cells) =>
          cells.contains(player.cell)
        } match {
          case Some(value) => map.updated(player, value._1.plantedBy)
          case None        => map
        }
    }
    killedPlayers
  }

  private def getPlayersWithScoreUpdated(
      activePlayers: List[ActivePlayer],
      deadPlayers: List[DeadPlayer],
      killedPlayers: Map[ActivePlayer, Killer],
      bombsToDetonate: List[Bomb]
  ): (List[DeadPlayer], List[ActivePlayer]) = {
    val invertedMap: Map[Killer, Iterable[ActivePlayer]] = killedPlayers
      .groupBy { case (_, killer) =>
        killer
      }
      .map { case (killer, players) =>
        killer -> players.keys
      }
    val (deadPlayersUpdatedScore, activePlayersUpdatedScore) =
      updateKillersScores(invertedMap, deadPlayers, activePlayers)
    val playersKilled = killedPlayers.keys.toList
    val updatedDeadPlayers =
      newDeadPlayers(deadPlayers, deadPlayersUpdatedScore, activePlayersUpdatedScore, playersKilled)
    val updatedActivePlayers =
      newActivePlayers(activePlayers, activePlayersUpdatedScore, playersKilled, bombsToDetonate)
    (updatedDeadPlayers, updatedActivePlayers)
  }

  private def newDeadPlayers(
      deadPlayers: List[DeadPlayer],
      deadPlayersUpdatedScore: List[DeadPlayer],
      activePlayersUpdatedScore: List[ActivePlayer],
      playersKilled: List[ActivePlayer]
  ): List[DeadPlayer] = {
    val activeToDeadPlayersNoScoreUpdated = playersKilled.distinct
      .filterNot(player => activePlayersUpdatedScore.exists(_.id == player.id))
      .map(_.toDeadPlayer)
    val activeToDeadPlayersScoreUpdated =
      playersKilled.distinct.filter(player => activePlayersUpdatedScore.exists(_.id == player.id)).map(_.toDeadPlayer)
    val deadPlayersWithNoUpdatedScore = deadPlayers.filter(player => deadPlayersUpdatedScore.exists(_.id == player.id))
    deadPlayersWithNoUpdatedScore ++ deadPlayersUpdatedScore ++ activeToDeadPlayersNoScoreUpdated ++ activeToDeadPlayersScoreUpdated
  }

  private def newActivePlayers(
      activePlayers: List[ActivePlayer],
      activePlayersUpdatedScore: List[ActivePlayer],
      playersKilled: List[ActivePlayer],
      bombsToDetonate: List[Bomb]
  ): List[ActivePlayer] = {
    val activePlayersWithNoUpdatedScoreNotKilled = activePlayers.filter(player =>
      !playersKilled.exists(_.id == player.id) && !activePlayersUpdatedScore.exists(_.id == player.id)
    )
    val activePlayersScoreUpdatedNotKilled =
      activePlayersUpdatedScore.filter(player => !playersKilled.exists(_.id == player.id))
    (activePlayersWithNoUpdatedScoreNotKilled ++ activePlayersScoreUpdatedNotKilled).map(player =>
      if (bombsToDetonate.exists(_.plantedBy == player.id)) player.copy(bombs = BombCount.One) else player
    )
  }

  private def updateKillersScores(
      invertedMap: Map[Killer, Iterable[ActivePlayer]],
      deadPlayers: List[DeadPlayer],
      activePlayers: List[ActivePlayer]
  ): (List[DeadPlayer], List[ActivePlayer]) = {
    val updatedKillersScore = invertedMap
      .map { case (killer, players) =>
        val points = players.map(player => if (player.id != killer) 1 else -1).sum
        activePlayers.find(_.id == killer) match {
          case Some(activePlayer) =>
            val totalPoints = activePlayer.score + points
            Some(activePlayer.copy(score = totalPoints))
          case None =>
            deadPlayers.find(_.id == killer) match {
              case Some(deadPlayer) => Some(deadPlayer.copy(score = deadPlayer.score + points))
              case None             => None
            }
        }
      }
      .toList
      .flatten
    val deadPlayersUpdatedScore   = updatedKillersScore.collect { case player: DeadPlayer => player }
    val activePlayersUpdatedScore = updatedKillersScore.collect { case player: ActivePlayer => player }
    (deadPlayersUpdatedScore, activePlayersUpdatedScore)
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

  private def finish(activePlayers: List[ActivePlayer], deadPlayers: List[DeadPlayer]): GameFinished = {
    val winner = activePlayers.sortBy(_.score.value).headOption match {
      case Some(value) => value
      case None        => deadPlayers.maxBy(_.score.value)
    }
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
    winner: Player,
    survivors: List[ActivePlayer],
    killed: List[DeadPlayer]
) extends Game
