package com.evolution.http

import cats.Show
import cats.data.{NonEmptyList, OptionT}
import cats.effect.*
import cats.implicits.*
import com.evolution.game.*
import com.evolution.game.Game.*
import com.evolution.player.*
import com.evolution.player.Player.IdlePlayer
import com.evolution.util.KeyboardReader
import io.circe.parser.*
import io.circe.syntax.*
import io.circe.{Decoder, Encoder}
import org.http4s.*
import org.http4s.Credentials.Token
import org.http4s.Method.{DELETE, GET, POST}
import org.http4s.client.Client
import org.http4s.client.dsl.io.*
import org.http4s.client.websocket.{WSConnectionHighLevel, WSFrame, WSRequest}
import org.http4s.ember.client.*
import org.http4s.headers.{Authorization, `WWW-Authenticate`}
import org.http4s.implicits.*
import org.http4s.jdkhttpclient.JdkWSClient

import java.net.http.HttpClient
import java.util.UUID
import scala.util.Try

object ClientApp extends IOApp {
  import org.http4s.circe.CirceEntityCodec.circeEntityEncoder

  private val uri = uri"ws://localhost:9001"
  //  private val wsUri =
  //    uri"wss://a01e-185-103-30-77.ngrok-free.app"

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
    _   <- IO.println(" 99 - Log out")
    _   <- IO.println("")
    _   <- IO.print("Choose an option: ")
    res <- IO.readLine
    num <- res.trim.toIntOption match {
      case Some(value) if (1 to 3).contains(value) || value == 99 => IO.pure(value)
      case _                                                      => menuLogged
    }
  } yield num

  private def sendCommand(connection: WSConnectionHighLevel[IO], playerId: PlayerId): IO[Unit] =
    for {
      cmd <- KeyboardReader.readCommand[IO]
      _   <- connection.send(WSFrame.Text(cmd.asJson.noSpaces))
      _   <- sendCommand(connection, playerId)
    } yield ()

  private def printBoardRunning(game: GameRunning, playerId: PlayerId): IO[Unit] = for {
    _ <- IO.println(f"${game.remainingTime.toMinutesPart}%02d:${game.remainingTime.toSecondsPart}%02d")
    player = game.activePlayers.find(_.id == playerId)
    _ <- game.getMaze.toPrintable(player).traverse_(IO.println)
  } yield ()

  private def printBoardFinished(game: GameFinished, playerId: PlayerId): IO[Unit] = for {
    _ <- IO.println(f"${game.remainingTime.toMinutesPart}%02d:${game.remainingTime.toSecondsPart}%02d")
    player = game.activePlayers.find(_.id == playerId)
    _ <- game.getMaze.toPrintable(player).traverse_(IO.println)
    _ <- IO.println(s"Game over: Winner is ${game.winner.username.value}")
  } yield ()

  private def printGame(game: Game, playerId: PlayerId): IO[Unit] = {
    game match {
      case GameWaiting(_, _, _) =>
        IO.println("Waiting for players...")
      case running: GameRunning =>
        printBoardRunning(running, playerId)
      case gameFinished: GameFinished =>
        printBoardFinished(gameFinished, playerId)
    }
  }

  private implicit val showGameId: Show[GameResponse] = { gameDto =>
    s"Game id: ${gameDto.id.id}"
  }

  private implicit val showGamesList: Show[GamesResponse] = { GamesResponse =>
    if (GamesResponse.games.nonEmpty) GamesResponse.games.map(game => game.show).mkString("\n")
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
        case 1  => listGames(player, client, ref)
        case 2  => createGame(player, client, ref)
        case 3  => joinGame(player)
        case 99 => logout(client, player, ref)
        case _  => IO.println("Invalid option")
      }
      _ <- selector(ref, client)
    } yield ()
  }

  private def printMenu(playerRef: Ref[IO, Option[AuthPlayer]], client: Client[IO]): IO[Unit] = {
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

  private def makeRequest[I: Encoder, O: Decoder](
      player: Ref[IO, Option[AuthPlayer]],
      client: Client[IO],
      uri: Uri,
      method: Method,
      body: Option[I] = None,
      headers: Option[Headers] = None
  ): IO[Option[O]] = method match {
    case GET    => handleResponse[O](player, client, addHeadersAndBody[I](GET, uri, body, headers))
    case POST   => handleResponse[O](player, client, addHeadersAndBody[I](POST, uri, body, headers))
    case DELETE => handleResponse[O](player, client, addHeadersAndBody[I](DELETE, uri, body, headers))
    case _      => IO.pure(None)
  }

  private def handleResponse[O: Decoder](
      player: Ref[IO, Option[AuthPlayer]],
      client: Client[IO],
      request: Request[IO]
  ): IO[Option[O]] = {
    val res =
      client.run(request).use { response =>
        response.bodyText.compile.string.flatMap { bodyString =>
          if (response.status.isSuccess) {
            decode[O](bodyString) match {
              case Left(_)      => IO.pure("".asLeft[O])
              case Right(value) => IO.pure(value.asRight[String])
            }
          } else {
            for {
              _ <- if (bodyString.nonEmpty) IO.println(bodyString.filter { c => c != '"' }) else IO.unit
              result <-
                if (
                  response.status == Status.Unauthorized &&
                  response.headers
                    .get[`WWW-Authenticate`]
                    .contains(
                      `WWW-Authenticate`(
                        NonEmptyList.of(Challenge("Bearer", ""))
                      )
                    )
                ) {
                  player.set(None).as("Unauthorized".asLeft[O])
                } else {
                  IO.pure(bodyString.asLeft[O])
                }
            } yield result
          }
        }
      }
    res.map(_.toOption)
  }

  private def addHeadersAndBody[I](method: Method, uri: Uri, body: Option[I], headers: Option[Headers])(implicit
      entityEncoder: EntityEncoder[IO, I]
  ): Request[IO] = {
    (body, headers) match {
      case (Some(body), Some(headers)) => method.apply(body = body, uri = uri, headers = headers)
      case (Some(body), None)          => method.apply(body = body, uri = uri)
      case (None, Some(headers))       => method.apply(uri = uri, headers = headers)
      case (None, None)                => method.apply(uri)
    }
  }

  private def createPlayer(playerRef: Ref[IO, Option[AuthPlayer]], client: Client[IO]): IO[Unit] = {
    val res = for {
      _        <- OptionT.liftF(IO.print("Username (must have at least 4 characters): "))
      line     <- OptionT.liftF(IO.readLine)
      username <- OptionT.fromOption[IO](Username(line))
      _        <- OptionT.liftF(IO.print("Password (must have at least 12 characters): "))
      line2    <- OptionT.liftF(IO.readLine)
      password <- OptionT.fromOption[IO](PasswordIn(line2))
      _ <- OptionT(
        makeRequest[PlayerCredentials, IdlePlayer](
          playerRef,
          client,
          uri = uri / "player",
          POST,
          PlayerCredentials(username, password).some
        )
      )
      _ <- OptionT.liftF(loginRequest(playerRef, username, password, client))
    } yield ()
    res.value.void
  }

  private def login(playerRef: Ref[IO, Option[AuthPlayer]], client: Client[IO]): IO[Unit] = {
    val res: OptionT[IO, Unit] = for {
      _        <- OptionT.liftF(IO.print("Username: "))
      line     <- OptionT.liftF(IO.readLine)
      username <- OptionT.fromOption[IO](Username(line))
      _        <- OptionT.liftF(IO.print("Password: "))
      line2    <- OptionT.liftF(IO.readLine)
      password <- OptionT.fromOption[IO](PasswordIn(line2))
      _        <- OptionT.liftF(loginRequest(playerRef, username, password, client))
    } yield ()
    res.value.void
  }

  private def loginRequest(
      playerRef: Ref[IO, Option[AuthPlayer]],
      username: Username,
      password: PasswordIn,
      client: Client[IO]
  ): IO[Unit] = {
    val res = for {
      player <- OptionT(
        makeRequest[PlayerCredentials, AuthPlayer](
          playerRef,
          client,
          uri / "player" / "login",
          POST,
          PlayerCredentials(username, password).some
        )
      )
      _ <- OptionT.liftF(playerRef.set(Some(player)))
      _ <- OptionT.liftF(IO.println(s"Logged as ${player.player.username.value}"))
    } yield ()
    res.value.void
  }

  private def logout(client: Client[IO], player: AuthPlayer, ref: Ref[IO, Option[AuthPlayer]]): IO[Unit] = for {
    _ <- makeRequest[Unit, Unit](
      ref,
      client,
      uri / "player" / "logout",
      POST,
      headers = generateAuthHeader(player).some
    )
    _ <- ref.set(None)
  } yield ()

  private def createGame(player: AuthPlayer, client: Client[IO], ref: Ref[IO, Option[AuthPlayer]]): IO[Unit] = {
    val res = for {
      _ <- OptionT.liftF(IO.println("Creating game"))
      res <- OptionT(
        makeRequest[GameRequest, GameResponse](
          ref,
          client,
          uri / "game",
          POST,
          GameRequest(1).some,
          generateAuthHeader(player).some
        )
      )
      _ <- OptionT.liftF(IO.println(res.show))
      _ <- OptionT.liftF(joinGameRequest(player, res.id.id))
    } yield ()
    res.value.void
  }

  private def listGames(player: AuthPlayer, client: Client[IO], ref: Ref[IO, Option[AuthPlayer]]): IO[Unit] =
    for {
      _ <- IO.println("Getting games")
      res <- makeRequest[Unit, GamesResponse](
        ref,
        client,
        uri / "game" / "all",
        GET,
        headers = generateAuthHeader(player).some
      )
      _ <- res match {
        case Some(value) => IO.println(value.show)
        case None        => IO.unit
      }
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
        case None       => IO.unit
      }
    } yield ()

  private def decodeMessage(msg: String): Either[String, Game] =
    decode[Game](msg) match {
      case Left(error) =>
        decode[String](msg) match {
          case Left(_)      => Left(error.getMessage)
          case Right(value) => Left(value)
        }
      case Right(value) => Right(value)
    }

  private def joinGameRequest(player: AuthPlayer, gameId: UUID): IO[Unit] =
    for {
      _ <- IO.println(s"Joining Game ${gameId.show} ")
      joinUri = uri / "game" / gameId / "join" / player.player.id.value
      headers = generateAuthHeader(player)
      clientResource: Resource[IO, WSConnectionHighLevel[IO]] =
        Resource
          .eval(IO(HttpClient.newHttpClient()))
          .flatMap(
            JdkWSClient[IO](_).connectHighLevel(WSRequest(uri = joinUri, headers = headers, method = GET))
          )
      _ <- clientResource.use { connection =>
        for {
          cmdReader <- sendCommand(connection, player.player.id).start
          _ <- connection.receiveStream
            .collect { case WSFrame.Text(json, _) =>
              decodeMessage(json)
            }
            .evalMap {
              case Left(message) => IO.println(message)
              case Right(game)   => printGame(game, player.player.id)
            }
            .compile
            .drain
            .guarantee(cmdReader.cancel)
          closeFrame <- connection.closeFrame.get
          _          <- IO.println(closeFrame.reason)
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
