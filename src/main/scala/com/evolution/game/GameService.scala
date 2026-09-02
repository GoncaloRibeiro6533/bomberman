package com.evolution.game

import cats.data.EitherT
import cats.effect.Clock
import cats.effect.kernel.Async
import cats.effect.std.Queue
import cats.syntax.all.*
import com.evolution.cell.PositiveNumber
import com.evolution.command.Command
import com.evolution.game.GameActorMessage.AddPlayer
import com.evolution.player.Player.IdlePlayer
import fs2.concurrent.Topic

class GameService[F[_]: Async](
    private val repository: GameRepository[F],
    private val clock: Clock[F]
) {

  def createGame(nPlayers: PositiveNumber): F[GameWaiting] = {
    val res = for {
      game <- repository.insertGame(nPlayers)
      loop <- createLoop(gameWaiting = game)
      _    <- repository.insertGameLoop(game.id, loop)
    } yield game
    res
  }

  def getAllGames: F[List[GameWaiting]] = for {
    games <- repository.findAll()
    waitingGames: List[GameWaiting] = games.collect { case game: GameWaiting => game }
  } yield waitingGames

  def joinGame(
      gameId: GameId,
      player: IdlePlayer
  ): F[Either[GameRepositoryError, (Topic[F, Game], Queue[F, Command])]] = {
    val res: EitherT[F, GameRepositoryError, (Topic[F, Game], Queue[F, Command])] = for {
      actor         <- EitherT(repository.getGameLoop(gameId))
      now           <- EitherT.liftF(clock.realTimeInstant)
      queueAndTopic <- EitherT(actor.addPlayer(AddPlayer(player), now))
    } yield queueAndTopic
    res.value
  }

  private def createLoop(gameWaiting: GameWaiting): F[(GameActor[F], F[Unit])] =
    for {
      gameLoop <- GameActor
        .make(
          gameWaiting,
          clock,
          (game: GameFinished) => {
            for {
              _ <- repository.update(game)
              _ <- repository.stopGameLoop(game)
            } yield ()
          }
        )
        .allocated
    } yield gameLoop
}
