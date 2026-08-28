package com.evolution.http

import cats.Show
import cats.effect.*
import cats.implicits.*
import com.evolution.game.{Game, GameFinished, GameRunning, GameWaiting}
import com.evolution.http.dtos.GameDto.*
import com.evolution.http.dtos.PlayerDto.PlayerInDto
import com.evolution.player.{AuthPlayer, PlayerId, Username}
import com.evolution.util.KeyboardReader
import io.circe.parser.*
import io.circe.syntax.*
import org.http4s.*
import org.http4s.Credentials.Token
import org.http4s.Method.GET
import org.http4s.circe.CirceEntityCodec.*
import org.http4s.client.Client
import org.http4s.client.dsl.io.*
import org.http4s.client.websocket.{WSConnectionHighLevel, WSFrame, WSRequest}
import org.http4s.ember.client.*
import org.http4s.headers.Authorization
import org.http4s.implicits.*
import org.http4s.jdkhttpclient.JdkWSClient

import java.net.http.{HttpClient, WebSocketHandshakeException}
import java.util.UUID
import scala.util.Try

object ClientApp extends IOApp {
  import org.http4s.circe.CirceEntityCodec.circeEntityEncoder

  private val uri = uri"ws://localhost:9001"

  private def menu: IO[Int] = for {
    _   <- IO.println("")
    _   <- IO.println("")
    _   <- IO.println("       MENU")
    _   <- IO.println(" 1 - Log in")
    _   <- IO.println(" 2 - Create user")
    _   <- IO.println(" 99 - Exit")
    _   <- IO.println("")
    _   <- IO.print("Choose an option: ")
    res <- IO.readLine
    num <- res.trim.toIntOption match {
      case Some(value) if (1 to 2).contains(value) || value == 99 => IO.pure(value)
      case _                                                      => menu
    }
  } yield num

  private def menuLogged: IO[Int] = for {
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
      case _                                                      => menuLogged
    }
  } yield num

  private def sendCommand(client: WSConnectionHighLevel[IO], playerId: PlayerId): IO[Unit] =
    for {
      cmd <- KeyboardReader.readCommand[IO](playerId)
      _ = playerId
      _ <- IO.println(cmd)
      _ <- client.send(WSFrame.Text(cmd.asJson.noSpaces))
      _ <- sendCommand(client, playerId)
    } yield ()

  private def printBoard(game: GameRunning): IO[Unit] = for {
    _ <- IO.println(f"${game.remainingTime.toMinutesPart}%02d:${game.remainingTime.toSecondsPart}%02d")
    _ <- game.getMaze.toPrintable.traverse_(IO.println)
  } yield ()

  private def printGame(game: Game): IO[Unit] = {
    game match {
      case GameWaiting(_, _, _) =>
        IO.println("Waiting for players...")
      case running: GameRunning =>
        printBoard(running)
      case gameFinished: GameFinished =>
        IO.println(s"Game over: Winner is ${gameFinished.winner.username.value}")
    }
  }

  private implicit val showGameId: Show[GameIdDto] = Show.show { gameDto =>
    s"Game id: ${gameDto.id.id}"
  }

  private implicit val showGamesList: Show[GamesOut] = Show.show { gamesOut =>
    if (gamesOut.games.nonEmpty) gamesOut.games.map(game => game.show).mkString("\n")
    else "No games available. Please create a new one."
  }

  private def selector(playerRef: Ref[IO, Option[AuthPlayer]], client: Client[IO]): IO[Unit] =
    for {
      player <- playerRef.get
      _ <- player match {
        case Some(player) => printMenuLogged(player, playerRef, client)
        case None         => printMenu(playerRef, client)
      }
    } yield ()

  private def printMenuLogged(
      player: AuthPlayer,
      ref: Ref[IO, Option[AuthPlayer]],
      client: Client[IO]
  ) = {
    for {
      option <- menuLogged
      _ <- option match {
        case 1  => listGames(player, client)
        case 2  => createGame(player, client)
        case 3  => joinGame(player)
        case 99 => IO.unit
        case _  => IO.println("Invalid option")
      }
      _ <- if (option == 99) IO.unit else selector(ref, client)
    } yield ()
  }

  private def printMenu(playerRef: Ref[IO, Option[AuthPlayer]], client: Client[IO]) = {
    for {
      option <- menu
      _ <- option match {
        case 1  => login(playerRef, client)
        case 2  => createPlayer(playerRef, client)
        case 99 => IO.unit
        case _  => IO.println("Invalid option")
      }
      _ <- if (option == 99) IO.unit else selector(playerRef, client)
    } yield ()
  }

  private def createPlayer(playerRef: Ref[IO, Option[AuthPlayer]], client: Client[IO]): IO[Unit] =
    for {
      _    <- IO.print("Username: ")
      line <- IO.readLine
      _ <- Username(line) match {
        case Some(value) =>
          for {
            player <- client.expect[AuthPlayer](Method.POST.apply(body = PlayerInDto(value), uri = uri / "player"))
            _      <- playerRef.set(Some(player))
            _      <- IO.println(s"Logged as ${player.player.username.value}")
          } yield ()
        case None => createPlayer(playerRef, client)
      }
    } yield ()

  private def login(playerRef: Ref[IO, Option[AuthPlayer]], client: Client[IO]): IO[Unit] =
    for {
      _    <- IO.print("Username: ")
      line <- IO.readLine
      _ <- Username(line) match {
        case Some(value) =>
          for {
            player <- client.expect[AuthPlayer](
              Method.POST.apply(body = PlayerInDto(value), uri = uri / "player" / "login")
            )
            _ <- playerRef.set(Some(player))
            _ <- IO.println(s"Logged as ${player.player.username.value}")
          } yield ()
        case None => login(playerRef, client)
      }
    } yield ()

  private def createGame(player: AuthPlayer, client: Client[IO]): IO[Unit] =
    for {
      _ <- IO.println("Creating game")
      res <- client.expect[GameIdDto](
        Method.POST.apply(body = GameInDto(2), uri = uri / "game", headers = generateAuthHeader(player))
      )
      _ <- IO.println(res.show)
      _ <- joinGameRequest(player, res.id.id)
    } yield ()

  private def listGames(player: AuthPlayer, client: Client[IO]): IO[Unit] =
    for {
      _ <- IO.println("Getting games")
      res <- client.expect[GamesOut](
        Method.GET.apply(uri = uri / "game" / "all", headers = generateAuthHeader(player))
      )
      _ <- IO.println(res.show)
    } yield ()

  private def generateAuthHeader(player: AuthPlayer) = {
    Headers(
      Authorization(
        Token(AuthScheme.Bearer, player.token.token.value.toString)
      )
    )
  }

  private def joinGame(player: AuthPlayer): IO[Unit] =
    for {
      _    <- IO.print("Game identifier: ")
      line <- IO.readLine
      _ <- Try(UUID.fromString(line.trim)).toOption match {
        case Some(uuid) => joinGameRequest(player, uuid)
        case None       => joinGame(player)
      }
    } yield ()

  private def joinGameRequest(player: AuthPlayer, gameId: UUID): IO[Unit] =
    for {
      _ <- IO.println(s"Joining Game ${gameId.show} ")
      joinUri = uri / "game" / gameId / "join"
      headers = generateAuthHeader(player)
      clientResource: Resource[IO, WSConnectionHighLevel[IO]] =
        Resource
          .eval(IO(HttpClient.newHttpClient()))
          .flatMap(JdkWSClient[IO](_).connectHighLevel(WSRequest(uri = joinUri, headers = headers, method = GET)))
      _ <- clientResource.use { client =>
        for {
          cmdReader <- sendCommand(client, player.player.id).start
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

  override def run(args: List[String]): IO[ExitCode] = {
    EmberClientBuilder
      .default[IO]
      .build
      .use { (client: Client[IO]) =>
        for {
          playerId <- Ref[IO].of[Option[AuthPlayer]](None)
          _        <- selector(playerId, client)
          _        <- IO.println("Terminated")
        } yield ExitCode.Success
      }
  }
}
