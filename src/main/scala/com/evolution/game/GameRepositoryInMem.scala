package com.evolution.game

import cats.effect.*
import cats.syntax.all.*
import com.evolution.cell.PositiveNumber
import com.evolution.game.GameRepositoryError.*
import com.evolution.util.IdGenerator

class GameRepositoryInMem[F[_]: Async](
    private val games: Ref[F, Map[GameId, Game]],
    private val loops: Ref[F, Map[GameId, (GameActor[F], F[Unit])]]
) extends GameRepository[F] {

  override def findGame(gameId: GameId): F[Option[Game]] =
    games.get.map(_.get(gameId))

  override def findAll(): F[List[Game]] = games.get.map(_.values.toList)

  override def insertGame(
      nPlayers: PositiveNumber
  ): F[GameWaiting] = for {
    uuid <- IdGenerator.generateUUID[F]
    gameId = GameId(uuid)
    game   = GameWaiting(gameId, nPlayers = nPlayers, players = Set.empty)
    _ <- games.update(_.updated(gameId, game))
  } yield game

  override def deleteGame(game: Game): F[Unit] =
    games.update(_.removed(game.id))

  override def update(game: Game): F[Unit] = for {
    _ <- games.update(_.updated(game.id, game))
  } yield ()

  override def insertGameLoop(gameId: GameId, loop: (GameActor[F], F[Unit])): F[GameActor[F]] = loops.modify {
    (previousLoops: Map[GameId, (GameActor[F], F[Unit])]) =>
      {
        val game = previousLoops.get(gameId)
        game match {
          case Some(value) => (previousLoops, value._1)
          case None        => (previousLoops.updated(gameId, loop), loop._1)
        }
      }
  }

  override def stopGameLoop(gameFinished: GameFinished): F[Unit] = for {
    _ <- Async[F].delay(println(s"Stopping game ${gameFinished.id}"))
    release <- loops.modify { currentLoops =>
      val gameId       = gameFinished.id
      val releaseMaybe = currentLoops.get(gameId).map(_._2)
      (currentLoops.removed(gameId), releaseMaybe)
    }
    _ <- release.sequence_
  } yield ()

  override def getGameLoop(gameId: GameId): F[Either[GameRepositoryError, GameActor[F]]] =
    loops.get.map(_.get(gameId) match {
      case Some(value) => value._1.asRight
      case None        => GameNotFound().asLeft
    })
}

object GameRepositoryInMem {
  def make[F[_]: Async]: Resource[F, GameRepositoryInMem[F]] = for {
    loops <-
      Resource.make(
        Ref.of[F, Map[GameId, (GameActor[F], F[Unit])]](Map.empty)
      ) { stateRef =>
        for {
          _     <- Async[F].delay(println("Releasing games"))
          state <- stateRef.get
          _ <- state.toVector.traverse { case (gameId, (_, release)) =>
            for {
              _ <- Async[F].delay(println(s"Stopping game $gameId"))
              _ <- release
            } yield ()
          }
        } yield ()
      }
    games <- Resource.eval(Ref[F].of(Map.empty[GameId, Game]))
  } yield new GameRepositoryInMem[F](games, loops)
}
