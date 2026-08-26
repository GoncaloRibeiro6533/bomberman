package com.evolution.game

import com.evolution.cell.PositiveNumber
import com.evolution.player.Player.IdlePlayer

import java.time.Instant

trait GameRepository[F[_]] {
  def findGame(gameId: GameId): F[Option[Game]]
  def findAll(): F[List[Game]]
  def insertGame(
      nPlayers: PositiveNumber,
      player: IdlePlayer
  ): F[GameWaiting]
  def deleteGame(game: Game): F[Unit]
  def update(game: Game): F[Unit]
  def insertGameLoop(gameRunning: GameRunning, loop: (GameLoop[F], F[Unit])): F[GameLoop[F]]
  def stopGameLoop(gameFinished: GameFinished): F[Unit]
  def promoteGameToRunning(gameId: GameId, startedAt: Instant): F[Either[GameRepositoryError, GameRunning]]
}

sealed trait GameRepositoryError

object GameRepositoryError {
  object GameNotFound        extends GameRepositoryError
  object GameAlreadyRunning  extends GameRepositoryError
  object GameAlreadyFinished extends GameRepositoryError
}
