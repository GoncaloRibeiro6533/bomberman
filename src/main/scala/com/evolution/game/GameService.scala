package com.evolution.game

import cats.data.EitherT
import cats.effect.Clock
import cats.effect.kernel.Async
import cats.syntax.all.*
import com.evolution.cell.PositiveNumber
import com.evolution.player.Player.IdlePlayer

sealed trait GameServiceError

object GameServiceError {

  object GameNotFound        extends GameServiceError
  object GameAlreadyFinished extends GameServiceError
  object GameAlreadyRunning  extends GameServiceError

}

class GameService[F[_]: Async](
    private val repository: GameRepository[F],
    private val clock: Clock[F]
) {

  def createGame(nPlayers: PositiveNumber, player: IdlePlayer): F[Either[GameServiceError, GameWaiting]] =
    repository.insertGame(nPlayers, player).map(_.asRight)

  def getAllGames: F[List[GameWaiting]] = for {
    games <- repository.findAll()
    waitingGames: List[GameWaiting] = games.collect { case game: GameWaiting => game }
  } yield waitingGames

  def joinGame(gameId: GameId): F[Either[GameServiceError, GameLoop[F]]] = {
    val res: EitherT[F, GameServiceError, GameLoop[F]] = for {
      gameRunning: GameRunning <- EitherT(promoteToRunning(gameId))
      loop                     <- EitherT.right(createLoop(gameRunning))
    } yield loop
    res.value
  }

  private def promoteToRunning(gameId: GameId): F[Either[GameServiceError, GameRunning]] = {
    for {
      now         <- clock.realTimeInstant
      gameRunning <- repository.promoteGameToRunning(gameId, now)
    } yield gameRunning match {
      case Left(value)  => toGameServiceError(value).asLeft
      case Right(value) => value.asRight
    }
  }

  private def toGameServiceError(value: GameRepositoryError) = {
    value match {
      case GameRepositoryError.GameNotFound        => GameServiceError.GameNotFound
      case GameRepositoryError.GameAlreadyRunning  => GameServiceError.GameAlreadyRunning
      case GameRepositoryError.GameAlreadyFinished => GameServiceError.GameAlreadyFinished
    }
  }

  private def createLoop(gameRunning: GameRunning): F[GameLoop[F]] =
    for {
      gameLoop <- GameLoop
        .make(
          gameRunning,
          clock,
          (game: GameFinished) => {
            for {
              _ <- repository.update(game)
              _ <- repository.stopGameLoop(game)
            } yield ()
          }
        )
        .allocated
      _ <- repository.insertGameLoop(gameRunning, gameLoop)
    } yield gameLoop._1
}
