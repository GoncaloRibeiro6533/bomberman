package com.evolution.game

import cats.data.EitherT
import cats.effect.{Clock, Deferred}
import cats.effect.kernel.Async
import cats.syntax.all.*
import com.evolution.cell.PositiveNumber
import com.evolution.player.Player.IdlePlayer

sealed trait GameServiceError

class GameService[F[_]: Async](
    private val repository: GameRepository[F],
    private val clock: Clock[F]
) {

  def createGame(nPlayers: PositiveNumber, player: IdlePlayer): F[GameWaiting] = {
    val res = for {
      game        <- repository.insertGame(nPlayers, player)
      gameRunning <- Deferred[F, GameRunning]
      _           <- repository.insertFutureGameRunning(game.id, gameRunning)
      _           <- createLoop(gameWaiting = game, gameRunning = gameRunning)
    } yield game
    res
  }

  def getAllGames: F[List[GameWaiting]] = for {
    games <- repository.findAll()
    waitingGames: List[GameWaiting] = games.collect { case game: GameWaiting => game }
  } yield waitingGames

  def joinGame(gameId: GameId, player: IdlePlayer): F[Either[GameRepositoryError, GameLoop[F]]] = {
    val res: EitherT[F, GameRepositoryError, GameLoop[F]] = for {
      gameWaiting: GameWaiting <- EitherT[F, GameRepositoryError, GameWaiting](
        repository.addPlayerToGame(gameId, player)
      )
      _    <- startGame(gameId, gameWaiting)
      loop <- EitherT(repository.getGameLoop(gameId))
    } yield loop
    res.value
  }

  private def startGame(gameId: GameId, gameWaiting: GameWaiting): EitherT[F, GameRepositoryError, Unit] = {
    if (gameWaiting.players.size == gameWaiting.nPlayers.value) {
      for {
        gameRunning <- EitherT[F, GameRepositoryError, GameRunning](promoteToRunning(gameId))
        _           <- EitherT[F, GameRepositoryError, Unit](repository.completeGameRunning(gameRunning))
      } yield ()
    } else EitherT.rightT[F, GameRepositoryError](())
  }

  private def promoteToRunning(gameId: GameId): F[Either[GameRepositoryError, GameRunning]] = {
    for {
      now         <- clock.realTimeInstant
      gameRunning <- repository.promoteGameToRunning(gameId, now)
    } yield gameRunning
  }

  private def createLoop(gameWaiting: GameWaiting, gameRunning: Deferred[F, GameRunning]): F[GameLoop[F]] =
    for {
      gameLoop <- GameLoop
        .make(
          gameWaiting,
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
      _ <- repository.insertGameLoop(gameWaiting, gameLoop)
    } yield gameLoop._1
}
