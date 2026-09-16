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

  def allPlayers: Set[PlayerId]
}

object Game {
  @JsonCodec
  final case class GameWaiting(id: GameId, players: Set[JoiningPlayer], nPlayers: PositiveNumber = PositiveNumber.Two)
      extends Game {
    private def start(startedAt: Instant): GameRunning = {
      val maze = Maze()
      GameRunning(
        id = id,
        nPlayers = nPlayers,
        activePlayers = maze.insertPlayers(players),
        deadPlayers = Set.empty,
        bombs = Set.empty,
        walls = maze.walls,
        blocks = maze.blocks,
        startedAt = startedAt,
        width = maze.width,
        height = maze.height,
        remainingTime = Duration.ofMinutes(2),
        duration = Duration.ofMinutes(2)
      )
    }

    def join(player: IdlePlayer, startedAt: Instant): Either[String, Game] = {
      if (players.size == nPlayers.value) scala.Left("Game is full")
      else if (players.exists(_.id == player.id)) scala.Right(this)
      else {
        val newPlayers = players + player.toJoiningPlayer
        if (newPlayers.size == nPlayers.value) scala.Right(this.copy(players = newPlayers).start(startedAt))
        else scala.Right(copy(players = newPlayers))
      }
    }

    override def allPlayers: Set[PlayerId] = players.map(_.id)
  }

  @JsonCodec
  final case class GameRunning(
      id: GameId,
      nPlayers: PositiveNumber,
      activePlayers: Set[ActivePlayer],
      deadPlayers: Set[DeadPlayer] = Set.empty,
      bombs: Set[Bomb],
      walls: Set[Cell],
      blocks: Set[Cell],
      startedAt: Instant,
      remainingTime: Duration,
      duration: Duration,
      width: Int,
      height: Int
  ) extends Game {

    private def getPositions: Set[Position] = {
      val playerPositions = activePlayers.map(_.cell.toPosition(PlayerPosition))
      val wallsPositions  = walls.map(_.toPosition(Wall))
      val bombsPositions  = bombs.map(_.cell.toPosition(BombPlacement))
      val blocksPositions = blocks.map(_.toPosition(DestructibleBlock))
      Set.concat(playerPositions, wallsPositions, bombsPositions, blocksPositions)
    }

    override def allPlayers: Set[PlayerId] = activePlayers.map(_.id) ++ deadPlayers.map(_.id)

    def getMaze: Maze = {
      Maze(width, height, getPositions)
    }

    def processMovement(playerId: PlayerId, direction: Direction): Either[String, GameRunning] = {
      for {
        player  <- activePlayers.find(_.id == playerId).toRight("player is dead. cannot perform movements")
        newCell <- (player.cell + direction).toRight("invalid position")
        _       <- Either.cond(isWalkableCell(newCell), (), "invalid position")
        playersUpdated = updatePlayerPosition(player, newCell)
      } yield copy(activePlayers = playersUpdated)
    }

    def processBombPlanting(id: PlayerId, instant: Instant): Either[String, GameRunning] = {
      for {
        player <- activePlayers.find(_.id == id).toRight("player is dead. cannot plant a bomb")
        _      <- Either.cond(canPlantBomb(player), (), "player has no bombs left")
        bombId                      = BombId(UUID.randomUUID())
        updatedPlayerBombs          = updatePlayerBombs(player, bombCount = BombCount.Zero)
        updatedGameBombs: Set[Bomb] = bombs + Bomb(bombId, player.cell, id, instant)
      } yield copy(
        bombs = updatedGameBombs,
        activePlayers = updatedPlayerBombs
      )
    }

    def triggerBombs(now: Instant): Game = {
      val bombsToDetonate            = bombs.filter { bomb => bomb.isExpired(now) }
      val elapsed: Duration          = Duration.between(startedAt, now)
      val newRemainingTime: Duration = duration.minus(elapsed)
      val playersAlive               = nPlayers.value - deadPlayers.size
      if (
        activePlayers.isEmpty ||
        (nPlayers.value == 1 && playersAlive == 0) ||
        (nPlayers.value >= 2 && playersAlive <= 1) || newRemainingTime.isNegative || newRemainingTime.isZero
      ) { // print at least once the empty board
        finish(activePlayers, deadPlayers)
      } else {
        if (bombsToDetonate.nonEmpty) {
          val bombsWithAffectedCells: Map[Bomb, Set[Cell]] =
            bombsToDetonate.foldRight(Map[Bomb, Set[Cell]]().empty)((bomb, map) =>
              map.updated(bomb, cellsInRadius(this.getPositions, bomb.cell, bomb.radius.value))
            )
          val remainingBlocks                          = blocks.diff(bombsWithAffectedCells.values.flatten.toSet)
          val killedPlayers: Map[ActivePlayer, Killer] = getKilledPlayers(activePlayers, bombsWithAffectedCells)
          val (newDeadPlayers, newActivePlayers) =
            getPlayersWithScoreUpdated(activePlayers, deadPlayers, killedPlayers, bombsToDetonate)
          val remainingBombs = bombs.diff(bombsToDetonate)
          copy(
            activePlayers = newActivePlayers,
            deadPlayers = newDeadPlayers,
            blocks = remainingBlocks,
            bombs = remainingBombs,
            remainingTime = newRemainingTime
          )
        } else copy(remainingTime = newRemainingTime)
      }
    }

    private type Killer = PlayerId
    private def getKilledPlayers(
        activePlayers: Set[ActivePlayer],
        affectedCells: Map[Bomb, Set[Cell]]
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
        activePlayers: Set[ActivePlayer],
        deadPlayers: Set[DeadPlayer],
        killedPlayers: Map[ActivePlayer, Killer],
        bombsToDetonate: Set[Bomb]
    ): (Set[DeadPlayer], Set[ActivePlayer]) = {
      val invertedMap: Map[Killer, Iterable[ActivePlayer]] = killedPlayers
        .groupBy { case (_, killer) =>
          killer
        }
        .map { case (killer, players) =>
          killer -> players.keys
        }
      val (deadPlayersUpdatedScore, activePlayersUpdatedScore) =
        updateKillersScores(invertedMap, deadPlayers, activePlayers)
      val playersKilled = killedPlayers.keySet
      val updatedDeadPlayers =
        newDeadPlayers(deadPlayers, deadPlayersUpdatedScore, activePlayersUpdatedScore, playersKilled)
      val updatedActivePlayers =
        newActivePlayers(activePlayers, activePlayersUpdatedScore, playersKilled, bombsToDetonate)
      (updatedDeadPlayers, updatedActivePlayers)
    }

    private def newDeadPlayers(
        deadPlayers: Set[DeadPlayer],
        deadPlayersUpdatedScore: Set[DeadPlayer],
        activePlayersUpdatedScore: Set[ActivePlayer],
        playersKilled: Set[ActivePlayer]
    ): Set[DeadPlayer] = {
      val activeToDeadPlayersNoScoreUpdated = playersKilled
        .filterNot(player => activePlayersUpdatedScore.exists(_.id == player.id))
        .map(_.toDeadPlayer)
      val activeToDeadPlayersScoreUpdated =
        playersKilled.filter(player => activePlayersUpdatedScore.exists(_.id == player.id)).map(_.toDeadPlayer)
      val deadPlayersWithNoUpdatedScore =
        deadPlayers.filter(player => deadPlayersUpdatedScore.exists(_.id == player.id))
      deadPlayersWithNoUpdatedScore ++ deadPlayersUpdatedScore ++ activeToDeadPlayersNoScoreUpdated ++ activeToDeadPlayersScoreUpdated
    }

    private def newActivePlayers(
        activePlayers: Set[ActivePlayer],
        activePlayersUpdatedScore: Set[ActivePlayer],
        playersKilled: Set[ActivePlayer],
        bombsToDetonate: Set[Bomb]
    ): Set[ActivePlayer] = {
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
        deadPlayers: Set[DeadPlayer],
        activePlayers: Set[ActivePlayer]
    ): (Set[DeadPlayer], Set[ActivePlayer]) = {
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
      (deadPlayersUpdatedScore.toSet, activePlayersUpdatedScore.toSet)
    }

    private def canPlantBomb(player: ActivePlayer): Boolean =
      player.bombs.value > 0 && !bombs.exists(_.cell == player.cell)

    private def updatePlayerPosition(player: ActivePlayer, value: Cell): Set[ActivePlayer] =
      activePlayers.filter(_.id != player.id) + player.copy(cell = value)

    private def updatePlayerBombs(player: ActivePlayer, bombCount: BombCount): Set[ActivePlayer] =
      activePlayers.filter(_.id != player.id) + player.copy(bombs = bombCount)

    private def isWalkableCell(value: Cell) = {
      !walls.contains(value) && !blocks.contains(value) && !bombs.exists(_.cell == value)
    }

    private def cellsInRadius(positions: Set[Position], center: Cell, radius: Int): Set[Cell] = {
      @tailrec
      def getCells(cells: Set[Cell], center: Cell, radius: Int, direction: Direction): Set[Cell] = {
        if (radius == 0) cells
        else {
          center + direction match {
            case Some(value) =>
              positions.find(_.cell == value) match {
                case Some(newPos) if newPos.cellType == Wall => cells
                case _                                       => getCells(cells + value, value, radius - 1, direction)
              }
            case None =>
              cells
          }
        }
      }
      Set(Up, Down, Left, Right).flatMap(getCells(Set(center), center, radius, _))
    }

    private def finish(activePlayers: Set[ActivePlayer], deadPlayers: Set[DeadPlayer]): GameFinished = {
      val winner = activePlayers.toList.sortBy(_.score.value).headOption match {
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
      survivors: Set[ActivePlayer],
      killed: Set[DeadPlayer]
  ) extends Game {
    override def allPlayers: Set[PlayerId] = survivors.map(_.id) ++ killed.map(_.id)
  }
}
