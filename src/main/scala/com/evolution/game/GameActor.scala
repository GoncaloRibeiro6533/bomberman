package com.evolution.game

import cats.effect.implicits.{effectResourceOps, genSpawnOps}
import cats.effect.{Clock, Ref, Resource}
import cats.effect.kernel.Async
import cats.effect.std.Queue
import cats.implicits.*
import com.evolution.command.*
import com.evolution.command.Command.Tick
import com.evolution.game.GameActorMessage.AddPlayer
import com.evolution.player.Player.IdlePlayer
import com.evolution.player.PlayerId
import fs2.concurrent.Topic

import java.time.Instant
import scala.concurrent.duration.{DurationInt, FiniteDuration}

sealed trait GameActorMessage {
}

object GameActorMessage {
  case class AddPlayer(player: IdlePlayer) extends GameActorMessage
  case class RemovePlayer(playerId: PlayerId) extends GameActorMessage
  case class State()
}

class GameActor[F[_]: Async] private(
                                      private val game: Ref[F, Game],
                                      private val clock: Clock[F],
                                      private val queue: Queue[F, Command],
                                      private val topic: Topic[F, Game],
                                      private val onComplete: GameFinished => F[Unit],
                                    ) {

  def addPlayer(message: AddPlayer, now: Instant): F[Either[GameRepositoryError, (Topic[F, Game], Queue[F,Command])]] = {
    game.modify {
      (gameState: Game) => gameState match {
        case gameWaiting: GameWaiting => if (gameWaiting.players.size == gameWaiting.nPlayers.value)
          (gameState, GameRepositoryError.GameAlreadyFull.asLeft)
        else {
          if (gameWaiting.players.exists( _.id == message.player.id)) (gameState, GameRepositoryError.PlayerAlreadyInGame.asLeft)
          else {
            val newGameState = gameWaiting.copy(players = message.player.toJoiningPlayer +: gameWaiting.players)
            if (newGameState.players. size == newGameState.nPlayers.value) {
              val gameStarted = newGameState.start(now)
              (gameStarted, (topic, queue).asRight)
            } else (newGameState, (topic, queue).asRight)
          }
        }
        case _: GameRunning => (gameState, GameRepositoryError.GameAlreadyRunning.asLeft)
        case _: GameFinished => (gameState, GameRepositoryError.GameAlreadyFinished.asLeft)
      }
    }
  }

  def loop: F[Unit] = for {
    command <- queue.take
    instant <- clock.realTimeInstant
    gameState <- game.get
    _ <- gameState match {
      case gameRunning: GameRunning =>
        val newGameState = processCommand(gameRunning, command, instant)
        newGameState match {
          case Left(value) => for {
            _ <- game.set(value)
            _ <- topic.publish1(value)
            _ <- onComplete(value)
          } yield ()
          case Right(value) => for {
            _ <- game.set(value)
            _ <- topic.publish1(value)
            _ <- loop
          } yield ()
        }
      case _ => loop
    }
  } yield ()

  private def processCommand(game: GameRunning, command: Command, instant: Instant): Either[GameFinished, GameRunning] = {
    command match {
      case Command.Tick =>
        game.triggerBombs(instant)
      case Command.Movement(playerId, direction) =>
        Right(game.processMovement(playerId, direction))
      case Command.PlantBomb(playerId) =>
        Right(game.processBombPlanting(playerId, instant))
    }
  }

  def tickProducer(command: Command, delay: FiniteDuration = 200.milliseconds): F[Unit] =
    for {
      _ <- queue.offer(command)
      _ <- Async[F].sleep(delay)
      _ <- tickProducer(command, delay)
    } yield ()
}

object GameActor {

  def make[F[_]: Async](
                         game: GameWaiting,
                         clock: Clock[F],
                         markAsFinished: GameFinished => F[Unit]
                       ): Resource[F, GameActor[F]] = {
    for {
      queue <- Queue.unbounded[F, Command].toResource
      topic <- Resource.eval(Topic[F, Game])
      gameRef <- Resource.eval(Ref.of[F,Game](game))
      gameLoop = new GameActor(gameRef, clock, queue, topic, markAsFinished)
      _ <- gameLoop.loop.background.void
      _ <- gameLoop.tickProducer(Tick).background.void
    } yield gameLoop
  }
}