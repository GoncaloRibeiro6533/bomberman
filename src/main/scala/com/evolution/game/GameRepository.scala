package com.evolution.game

import com.evolution.cell.PositiveNumber

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
  def getGameLoop(gameId: GameId): F[Either[GameRepositoryError, GameActor[F]]]
}

sealed trait GameRepositoryError {
  def message: String
}

object GameRepositoryError {
  case class GameNotFound(message: String = "Game not found")                extends GameRepositoryError
  case class GameAlreadyRunning(message: String = "Game already Running")    extends GameRepositoryError
  case class GameAlreadyFinished(message: String = "Game already finished")  extends GameRepositoryError
  case class GameAlreadyFull(message: String = "Game already full")          extends GameRepositoryError
  case class PlayerAlreadyInGame(message: String = "Player already in game") extends GameRepositoryError
}
