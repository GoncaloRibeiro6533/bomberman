package com.evolution.game

import com.evolution.cell.PositiveNumber
import com.evolution.player.Player.IdlePlayer

import java.time.Instant

trait GameRepository[F[_]] {
  def findGame(gameId: GameId): F[Option[Game]]
  def findAll(): F[List[Game]]
  def insertGame(
      nPlayers: PositiveNumber
  ): F[GameWaiting]
  def deleteGame(game: Game): F[Unit]
  def update(game: Game): F[Unit]
  def insertGameLoop(gameId: GameId, loop: (GameActor[F], F[Unit])): F[GameActor[F]]
  def stopGameLoop(gameFinished: GameFinished): F[Unit]
  def promoteGameToRunning(gameId: GameId, startedAt: Instant): F[Either[GameRepositoryError, GameRunning]]
  def addPlayerToGame(gameId: GameId, player: IdlePlayer): F[Either[GameRepositoryError, GameWaiting]]
  def getGameLoop(gameId: GameId): F[Either[GameRepositoryError, GameActor[F]]]
}

sealed trait GameRepositoryError

object GameRepositoryError {
  object GameNotFound        extends GameRepositoryError
  object GameAlreadyRunning  extends GameRepositoryError
  object GameAlreadyFinished extends GameRepositoryError
  object GameAlreadyFull     extends GameRepositoryError
  object PlayerAlreadyInGame extends GameRepositoryError
}
