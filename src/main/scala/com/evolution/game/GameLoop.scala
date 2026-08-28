package com.evolution.game

import cats.effect.implicits.{effectResourceOps, genSpawnOps}
import cats.effect.{Clock, Deferred, Resource}
import cats.effect.kernel.Async
import cats.effect.std.Queue
import cats.implicits.*
import com.evolution.command.*
import com.evolution.command.Command.Tick
import fs2.concurrent.Topic

import scala.concurrent.duration.{DurationInt, FiniteDuration}

case class GameLoop[F[_]: Async] private (
    clock: Clock[F],
    queue: Queue[F, Command],
    topic: Topic[F, Game],
    onComplete: GameFinished => F[Unit],
    gameRunning: Deferred[F, GameRunning] // could be replaced with Ref[F,Game] on loop as parameter??
) {

  def loop(game: Game): F[Unit] = for {
    command <- queue.take
    _ <- game match {
      case gameWaiting: GameWaiting =>
        for {
          gameState <- gameRunning.tryGet
          _ <- gameState match {
            case Some(value) => loop(value)
            case None        => loop(gameWaiting)
          }
        } yield ()
      case gameRunning: GameRunning => onGameRunning(gameRunning, command)
      case _: GameFinished          => Async[F].unit
    }
  } yield ()

  private def onGameRunning(game: GameRunning, command: Command): F[Unit] = for {
    instant <- clock.realTimeInstant
    newGameState = command match {
      case Command.Tick =>
        game.triggerBombs(instant)
      case Command.Movement(playerId, direction) =>
        Right(game.processMovement(playerId, direction))
      case Command.PlantBomb(playerId) =>
        Right(game.processBombPlanting(playerId, instant))
    }
    _ <- newGameState match {
      case Left(value) =>
        for {
          _ <- topic.publish1(value)
          _ <- onComplete(value)
        } yield ()
      case Right(value) =>
        for {
          _ <- topic.publish1(value)
          _ <- loop(value)
        } yield ()
    }
  } yield ()
  def tickProducer(command: Command, delay: FiniteDuration = 100.milliseconds): F[Unit] =
    for {
      _ <- queue.offer(command)
      _ <- Async[F].sleep(delay)
      _ <- tickProducer(command, delay)
    } yield ()
}

object GameLoop {

  def make[F[_]: Async](
      gameWaiting: GameWaiting,
      gameRunning: Deferred[F, GameRunning],
      clock: Clock[F],
      markAsFinished: GameFinished => F[Unit]
  ): Resource[F, GameLoop[F]] = {
    for {
      queue <- Queue.unbounded[F, Command].toResource
      topic <- Resource.eval(Topic[F, Game])
      gameLoop = GameLoop(clock, queue, topic, markAsFinished, gameRunning)
      _ <- gameLoop.loop(gameWaiting).background.void
      _ <- gameLoop.tickProducer(Tick).background.void
    } yield gameLoop
  }
}
