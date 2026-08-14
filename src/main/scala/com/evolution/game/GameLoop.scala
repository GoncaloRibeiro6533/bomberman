package com.evolution.game

import cats.effect.std.Queue
import cats.effect.{Clock, IO, IOApp}
import cats.implicits.*
import com.evolution.bomb.*
import com.evolution.cell.Cell
import com.evolution.command.*
import com.evolution.direction.*
import com.evolution.player.*
import com.evolution.player.Player.*
import com.evolution.direction.Direction.*
import com.evolution.util.*

import java.time.Instant
import scala.annotation.tailrec
import scala.concurrent.duration.{DurationInt, FiniteDuration}

case class GameLoop(clock: Clock[IO]) {

  def loop(game: Game, queue: Queue[IO, Command]): IO[Unit] = for {
    _       <- game.getMaze.toPrintable.traverse(IO.println)
    command <- queue.take
    instant <- clock.realTimeInstant
    newGameState = command match {
      case Command.Tick =>
        triggerBombs(game, instant)
      case Command.Movement(playerId, direction) =>
        processMovement(game, playerId, direction)
      case Command.PlantBomb(playerId) =>
        processBombPlanting(game, playerId, instant)
    }
    _ <- loop(newGameState, queue)
  } yield ()

  private def processMovement(game: Game, playerId: PlayerId, direction: Direction): Game = {
    val updatedGame = for {
      player  <- game.activePlayers.find(_.id == playerId)
      newCell <- player.cell + direction
      if isWalkableCell(game, newCell)
      playersUpdated = updatePlayerPosition(game, player, newCell)
    } yield game.copy(activePlayers = playersUpdated)
    updatedGame match {
      case Some(value) => value
      case None        => game
    }
  }

  private def processBombPlanting(game: Game, id: PlayerId, instant: Instant): Game = {
    val updatedGame: Option[Game] =
      for {
        player <- game.activePlayers.find(_.id == id)
        if canPlantBomb(game, player)
        bombId <- BombId(IdGenerator.generateId())
        updatedPlayerBombs = updatePlayerBombs(game, player, bombCount = BombCount.Zero)
        updatedGameBombs   = Bomb(bombId, player.cell, id, instant) +: game.bombs
      } yield game.copy(
        bombs = updatedGameBombs,
        activePlayers = updatedPlayerBombs
      )
    updatedGame match {
      case Some(value) => value
      case None        => game
    }
  }

  private def triggerBombs(game: Game, now: Instant): Game = {
    val bombsToDetonate   = game.bombs.filter { bomb => bomb.isExpired(now) }
    val cells: List[Cell] = bombsToDetonate.flatMap(bomb => cellsInRadius(bomb.cell, bomb.radius.value)).distinct
    val blocks            = game.blocks.filterNot(block => cells.contains(block))
    val deadPlayers       = game.activePlayers.filter(player => cells.contains(player.cell)).map(_.toDeadPlayer)
    val deadPlayerIds     = deadPlayers.map(_.id).toSet
    val players: List[ActivePlayer] = game.activePlayers
      .filterNot(p => deadPlayerIds.contains(p.id))
      .map(player =>
        if (bombsToDetonate.exists(_.plantedBy == player.id)) player.copy(bombs = BombCount.One) else player
      )
    val bombs = game.bombs.filterNot(bombsToDetonate.contains(_))
    game.copy(activePlayers = players, deadPlayers = deadPlayers, blocks = blocks, bombs = bombs)

  }

  private def cellsInRadius(center: Cell, radius: Int): List[Cell] = {
    @tailrec
    def getCells(cells: List[Cell], center: Cell, radius: Int, direction: Direction): List[Cell] = {
      if (radius == 0) cells
      else {
        center + direction match {
          case Some(value) =>
            getCells(value +: cells, value, radius - 1, direction)
          case None =>
            cells
        }
      }
    }
    List(Up, Down, Left, Right).flatMap(getCells(List(center), center, radius, _))
  }

  private def canPlantBomb(game: Game, player: ActivePlayer): Boolean =
    player.bombs.value > 0 && !game.bombs.exists(_.cell == player.cell)

  private def updatePlayerPosition(game: Game, player: ActivePlayer, value: Cell): List[ActivePlayer] = {
    val others     = game.activePlayers.filter(_.id != player.id)
    val newPlayers = player.copy(cell = value) +: others
    newPlayers
  }

  private def updatePlayerBombs(game: Game, player: ActivePlayer, bombCount: BombCount): List[ActivePlayer] = {
    val others     = game.activePlayers.filter(_.id != player.id)
    val newPlayers = player.copy(bombs = bombCount) +: others
    newPlayers
  }

  private def isWalkableCell(game: Game, value: Cell) = {
    !game.walls.contains(value) && !game.blocks.contains(value) && !game.bombs.exists(_.cell == value)
  }

  def tickProducer(queue: Queue[IO, Command], command: Command, delay: FiniteDuration = 200.milliseconds): IO[Unit] =
    for {
      _ <- queue.offer(command)
      _ <- IO.sleep(delay)
      _ <- tickProducer(queue, command, delay)
    } yield ()
}

object App extends IOApp.Simple {

  private def sendCommand(queue: Queue[IO, Command], playerId: PlayerId): IO[Unit] =
    for {
      cmd <- KeyboardReader.readInput[IO](playerId)
      _   <- IO.println(cmd)
      _   <- queue.offer(cmd)
      _   <- sendCommand(queue, playerId)
    } yield ()

  private val map = List(
    "#############",
    "#P% %  % % %#",
    "# # # #  # #",
    "# # # # # # #",
    "#  % % % % P#",
    "#############"
  )

  override def run: IO[Unit] =
    for {
      clock    <- IO(Clock[IO])
      gameLoop <- IO.pure(GameLoop(clock))
      queue    <- Queue.unbounded[IO, Command]
      maze                  = Maze(map)
      player: JoiningPlayer = JoiningPlayer(PlayerId(123).get, Username("Bob1").get)
      players               = maze.insertPlayers(List(player))
      startTime <- clock.realTimeInstant
      game = Game(
        id = GameId(123).get,
        activePlayers = players,
        bombs = Nil,
        walls = maze.walls,
        blocks = maze.blocks,
        width = maze.width,
        height = maze.height,
        startedAt = startTime
      )
      _ <- sendCommand(queue, player.id).start
      _ <- gameLoop.tickProducer(queue, Command.Tick, delay = 4.seconds).start
      _ <- gameLoop.loop(game, queue)
    } yield ()
}
