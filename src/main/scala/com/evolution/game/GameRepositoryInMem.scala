package com.evolution.game

import cats.effect.*
import cats.syntax.all.*
import com.evolution.cell.PositiveNumber
import com.evolution.game.GameRepositoryError.*
import com.evolution.player.Player.IdlePlayer
import com.evolution.util.IdGenerator

import java.time.Instant

class GameRepositoryInMem[F[_]: Async](
    private val games: Ref[F, Map[GameId, Game]],
    private val loops: Ref[F, Map[GameId, (GameLoop[F], F[Unit])]],
    private val matches: Ref[F,Map[GameId, Deferred[F,GameRunning]]],
) extends GameRepository[F] {

  override def findGame(gameId: GameId): F[Option[Game]] =
    games.get.map(_.get(gameId))

  override def findAll(): F[List[Game]] = games.get.map(_.values.toList)

  override def insertGame(
      nPlayers: PositiveNumber,
      player: IdlePlayer
  ): F[GameWaiting] = for {
    uuid <- IdGenerator.generateUUID[F]
    gameId = GameId(uuid)
    game   = GameWaiting(gameId, nPlayers = nPlayers, players = List(player.toJoiningPlayer))
    _ <- games.update(_.updated(gameId, game))
  } yield game

  override def deleteGame(game: Game): F[Unit] =
    games.update(_.removed(game.id))

  override def update(game: Game): F[Unit] = for {
    _ <- games.update(_.updated(game.id, game))
  } yield ()

  override def insertGameLoop(gameRunning: GameRunning, loop: (GameLoop[F], F[Unit])): F[GameLoop[F]] = loops.modify {
    (previousLoops: Map[GameId, (GameLoop[F], F[Unit])]) =>
      {
        val game = previousLoops.get(gameRunning.id)
        game match {
          case Some(value) => (previousLoops, value._1)
          case None        => (previousLoops.updated(gameRunning.id, loop), loop._1)
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

  override def promoteGameToRunning(gameId: GameId, startedAt: Instant): F[Either[GameRepositoryError, GameRunning]] =
    games.modify { gamesMap =>
      val game: Option[Game] = gamesMap.get(gameId)
      game match {
        case Some(value) =>
          value match {
            case game: GameWaiting =>
              val gameRunning = game.start(startedAt)
              (gamesMap.updated(game.id, gameRunning), gameRunning.asRight)
            case _: GameRunning  => (gamesMap, GameAlreadyRunning.asLeft)
            case _: GameFinished => (gamesMap, GameAlreadyFinished.asLeft)
          }
        case None => (gamesMap, GameRepositoryError.GameNotFound.asLeft)
      }
    }
}

object GameRepositoryInMem {
  def make[F[_]: Async]: Resource[F, GameRepositoryInMem[F]] = for {
    loops: Ref[F, Map[GameId, (GameLoop[F], F[Unit])]] <-
      Resource.make(
        Ref.of[F, Map[GameId, (GameLoop[F], F[Unit])]](Map.empty)
      ) { stateRef =>
        for {
          _                                          <- Async[F].delay(println("Releasing games"))
          state: Map[GameId, (GameLoop[F], F[Unit])] <- stateRef.get
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
