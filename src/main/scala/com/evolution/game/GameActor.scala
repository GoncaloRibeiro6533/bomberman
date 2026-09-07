package com.evolution.game

import cats.effect.Resource
import cats.effect.implicits.{effectResourceOps, genSpawnOps}
import cats.effect.kernel.{Async, Outcome}
import cats.effect.std.Queue
import cats.implicits.*
import com.evolution.game.Command.*
import com.evolution.player.PlayerId

import java.time.Instant
import scala.concurrent.duration.DurationInt

class GameActor[F[_]: Async](
    private val queue: Queue[F, Command],
    private val websocketService: WebsocketService[F],
    private val onComplete: GameFinished => F[Unit]
) {

  private def broadcastState(game: Game): F[Unit] =
    game.allPlayers.toVector.traverseVoid(playerId => websocketService.send(playerId, game))

  def loop(initialGame: GameWaiting): Resource[F, F[Outcome[F, Throwable, Unit]]] =
    fs2.Stream
      .fromQueueUnterminated(queue)
      .evalFold(initialGame: Game) { case (gameState, command) =>
        Async[F].realTimeInstant.flatMap { instant =>
          processCommand(gameState, command, instant)
        }
      }
      .compile
      .drain
      .background

  private def processCommand(
      game: Game,
      command: Command,
      instant: Instant
  ): F[Game] = {
    game match {
      case gameWaiting: GameWaiting =>
        command match {
          case Command.Join(player) =>
            gameWaiting.join(player, instant) match {
              case Left(error) =>
                websocketService.disconnect(player.id, error) as game
              case Right(newGameState) => broadcastState(newGameState) as newGameState
            }
          case Command.Tick => Async[F].pure(gameWaiting)
          case Command.Movement(playerId, _) =>
            websocketService.send(playerId, "Game not running") as game
          case Command.PlantBomb(playerId) => websocketService.send(playerId, "Game not started") as game
        }
      case gameRunning: GameRunning =>
        command match {
          case Command.Tick =>
            val newGameState = gameRunning.triggerBombs(instant)
            broadcastState(newGameState) as newGameState
          case Command.Movement(playerId, direction) =>
            val newGameState = gameRunning.processMovement(playerId, direction)
            broadcastState(newGameState) as newGameState
          case Command.PlantBomb(playerId) =>
            val newGameState = gameRunning.processBombPlanting(playerId, instant)
            broadcastState(newGameState) as newGameState
          case Command.Join(player) =>
            websocketService.disconnect(player.id, "Game is already running") as gameRunning
        }
      case gameFinished: GameFinished =>
        broadcastState(game)
        val allPlayers: Set[PlayerId] = gameFinished.survivors.map(_.id) ++ gameFinished.killed.map(_.id)
        allPlayers.toVector.traverseVoid(websocketService.disconnect(_, "Game Finished")) *>
          onComplete(gameFinished) as gameFinished
    }
    // TODO do not ignore the commands, it must have a response
  }

  def publishCommand(cmd: Command): F[Unit] = queue.offer(cmd)
}

object GameActor {

  def make[F[_]: Async](
      game: GameWaiting,
      websocketService: WebsocketService[F],
      markAsFinished: GameFinished => F[Unit]
  ): Resource[F, GameActor[F]] = {
    for {
      queue <- Queue.unbounded[F, Command].toResource
      _     <- (queue.offer(Tick) *> Async[F].sleep(200.milliseconds)).foreverM.background.void
      gameLoop = new GameActor(queue, websocketService, markAsFinished)
      _ <- gameLoop.loop(game)
    } yield gameLoop
  }
}
