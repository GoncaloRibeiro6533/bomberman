package com.evolution.game

import cats.data.EitherT
import cats.effect.kernel.Async
import cats.syntax.all.*
import com.evolution.cell.PositiveNumber
import com.evolution.game.Game.*
import com.evolution.websocket.WebsocketService
import org.typelevel.log4cats.Logger
import org.typelevel.log4cats.slf4j.Slf4jLogger

class GameService[F[_]: Async](
    repository: GameRepository[F],
    websocketService: WebsocketService[F]
) {

  implicit def logger: Logger[F] = Slf4jLogger.getLogger[F]

  def createGame(nPlayers: PositiveNumber): F[GameWaiting] = {
    val res = for {
      game <- repository.insertGame(nPlayers)
      loop <- createLoop(gameWaiting = game)
      _    <- repository.insertGameLoop(game.id, loop)
      _    <- Logger[F].info(s"Game created with id: ${game.id.id}")
    } yield game
    res
  }

  def getAllGames: F[List[GameWaiting]] = for {
    games <- repository.findAll()
    waitingGames: List[GameWaiting] = games.collect { case game: GameWaiting => game }
  } yield waitingGames

  def sendCommand(
      gameId: GameId,
      command: Command
  ): F[Either[GameRepositoryError, Unit]] = {
    val res: EitherT[F, GameRepositoryError, Unit] = for {
      actor <- EitherT(repository.getGameLoop(gameId))
      _     <- EitherT.right(actor.publishCommand(command))
    } yield ()
    res.value
  }

  private def createLoop(gameWaiting: GameWaiting): F[(GameActor[F], F[Unit])] =
    for {
      gameLoop <- GameActor
        .make(
          gameWaiting,
          websocketService,
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
