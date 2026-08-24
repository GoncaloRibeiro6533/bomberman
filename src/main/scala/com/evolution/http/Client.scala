package com.evolution.http

import cats.Show
import cats.effect.{ExitCode, IO, IOApp, Resource}
import cats.implicits.*
import com.evolution.game.{Game, GameFinished, GameRunning, GameWaiting}
import com.evolution.http.dtos.GameDto.*
import com.evolution.player.PlayerId
import com.evolution.util.KeyboardReader
import io.circe.parser.*
import io.circe.syntax.*
import org.http4s.*
import org.http4s.ember.client.*
import org.http4s.client.dsl.io.*
import org.http4s.client.websocket.{WSConnectionHighLevel, WSFrame, WSRequest}
import org.http4s.implicits.*
import org.http4s.jdkhttpclient.JdkWSClient
import org.http4s.circe.CirceEntityCodec.*

import java.net.http.HttpClient
import java.util.UUID

object Client extends IOApp {
  import org.http4s.circe.CirceEntityCodec.circeEntityEncoder

  private val uri                = uri"ws://localhost:9001"
  private val playerId: PlayerId = PlayerId(UUID.fromString("c0707ed0-bffe-4a8b-a155-5c2642b45982"))

  private def menu: IO[Int] = for {
    _   <- IO.println("")
    _   <- IO.println("")
    _   <- IO.println("       MENU")
    _   <- IO.println(" 1 - List games")
    _   <- IO.println(" 2 - Create game")
    _   <- IO.println(" 3 - Join game")
    _   <- IO.println(" 99 - Exit")
    _   <- IO.println("")
    _   <- IO.print("Choose an option: ")
    res <- IO.readLine
    num <- res.trim.toIntOption match {
      case Some(value) if (1 to 3).contains(value) || value == 99 => IO.pure(value)
      case _                                                      => menu
    }
  } yield num

  private def sendCommand(client: WSConnectionHighLevel[IO], playerId: PlayerId): IO[Unit] =
    for {
      cmd <- KeyboardReader.readCommand[IO](playerId)
      _   <- client.send(WSFrame.Text(cmd.asJson.noSpaces))
      _   <- sendCommand(client, playerId)
    } yield ()

  private def printBoard(game: GameRunning): IO[Unit] =
    game.getMaze.toPrintable.traverse_(IO.println)

  private def printGame(game: Game): IO[Unit] = {
    game match {
      case GameWaiting(_, _, _) =>
        IO.println("Waiting for players...")
      case running: GameRunning =>
        printBoard(running)
      case GameFinished(_, _, _, _, _) =>
        IO.println("Game over")
    }
  }

  private implicit val showGameId: Show[GameIdDto] = Show.show { gameDto =>
    s"Game id: ${gameDto.id.id}"
  }

  private implicit val showGamesList: Show[GamesOut] = Show.show { gamesOut =>
    gamesOut.games.map(game => game.show).mkString("\n")
  }

  override def run(args: List[String]): IO[ExitCode] = {
    for {
      _ <- selector
      _ <- IO.println("Terminated")
    } yield ExitCode.Success
  }

  private def selector: IO[Unit] =
    for {
      option <- menu
      _ <- option match {
        case 1  => listGames
        case 2  => createGame
        case 3  => joinGame
        case 99 => IO.unit
        case _  => IO.println("Invalid option")
      }
      _ <- if (option == 99) IO.unit else selector
    } yield ()

  private def createGame: IO[Unit] = {
    EmberClientBuilder
      .default[IO]
      .build
      .use { client =>
        for {
          _   <- IO.println("Creating game")
          res <- client.expect[GameIdDto](Method.POST.apply(body = GameInDto(2), uri = uri / "game"))
          _   <- IO.println(res.show)
        } yield ()
      }
  }

  private def listGames: IO[Unit] = {
    EmberClientBuilder
      .default[IO]
      .build
      .use { client =>
        for {
          _   <- IO.println("Getting games")
          res <- client.expect[GamesOut](Method.GET.apply(uri = uri / "game" / "all"))
          _   <- IO.println(res.show)
        } yield ()
      }
  }

  private def joinGame: IO[Unit] = {
    for {
      _    <- IO.println("Game identifier:")
      line <- IO.readLine
      joinUri = uri / "game" / line.trim / "join"
      clientResource: Resource[IO, WSConnectionHighLevel[IO]] =
        Resource
          .eval(IO(HttpClient.newHttpClient()))
          .flatMap(JdkWSClient[IO](_).connectHighLevel(WSRequest(joinUri)))
      _ <- clientResource.use { client =>
        for {
          cmdReader <- sendCommand(client, playerId).start
          _ <- client.receiveStream
            .collect { case WSFrame.Text(json, _) => decode[Game](json) }
            .evalTap {
              case Right(game) => printGame(game)
              case Left(error) => IO.println(s"Failed to decode game: $error")
            }
            .takeWhile {
              case Left(_) => false
              case Right(value) =>
                value match {
                  case _: GameWaiting  => true
                  case _: GameRunning  => true
                  case _: GameFinished => false
                }
            }
            .compile
            .drain
          _ <- cmdReader.cancel
        } yield ()
      }
    } yield ()
  }
}
