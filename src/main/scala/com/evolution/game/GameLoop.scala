package com.evolution.game

import cats.effect.implicits.{effectResourceOps, genSpawnOps}
import cats.effect.{Clock, Resource}
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
    onComplete: GameFinished => F[Unit]
) {

  def loop(game: GameRunning): F[Unit] = for {
    command <- queue.take
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

  def tickProducer(command: Command, delay: FiniteDuration = 200.milliseconds): F[Unit] =
    for {
      _ <- queue.offer(command)
      _ <- Async[F].sleep(delay)
      _ <- tickProducer(command, delay)
    } yield ()
}

object GameLoop {

  def make[F[_]: Async](
      game: GameRunning,
      clock: Clock[F],
      markAsFinished: GameFinished => F[Unit]
  ): Resource[F, GameLoop[F]] = {
    for {
      queue <- Queue.unbounded[F, Command].toResource
      topic <- Resource.eval(Topic[F, Game])
      gameLoop = GameLoop(clock, queue, topic, markAsFinished)
      _ <- gameLoop.loop(game).background
      _ <- gameLoop.tickProducer(Tick).background
    } yield gameLoop
  }
}
