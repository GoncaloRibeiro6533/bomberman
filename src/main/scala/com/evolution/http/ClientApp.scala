package com.evolution.http

import cats.Show
import cats.data.OptionT
import cats.effect.*
import cats.implicits.*
import com.evolution.game.{Game, GameFinished, GameRequest, GameResponse, GameRunning, GameWaiting, GamesResponse}
import com.evolution.player.{AuthPlayer, PlayerId, PlayerRequest, Username}
import com.evolution.util.KeyboardReader
import io.circe.Decoder
import io.circe.parser.*
import io.circe.syntax.*
import org.http4s.*
import org.http4s.Credentials.Token
import org.http4s.Method.{DELETE, GET, POST}
import org.http4s.client.Client
import org.http4s.client.dsl.io.*
import org.http4s.client.websocket.{WSConnectionHighLevel, WSFrame, WSRequest}
import org.http4s.ember.client.*
import org.http4s.headers.Authorization
import org.http4s.implicits.*
import org.http4s.jdkhttpclient.JdkWSClient

import java.net.http.HttpClient
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
    _   <- IO.println(" 99 - Log out")
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

  private implicit val showGameId: Show[GameResponse] = Show.show { gameDto =>
    s"Game id: ${gameDto.id.id}"
  }

  private implicit val showGamesList: Show[GamesResponse] = Show.show { GamesResponse =>
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
        case 1  => listGames(player, client)
        case 2  => createGame(player, client)
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

  private def makeRequest[I, O](
      client: Client[IO],
      uri: Uri,
      method: Method,
      body: Option[I] = None,
      headers: Option[Headers] = None,
  )(implicit entityEncoder: EntityEncoder[IO, I], entityDecoder: Decoder[O]): IO[Option[O]] = method match {
    case GET    => handleResponse[O](client, addHeadersAndBody[I](GET, uri, body, headers))
    case POST   => handleResponse[O](client, addHeadersAndBody[I](POST, uri, body, headers))
    case DELETE => handleResponse[O](client, addHeadersAndBody[I](DELETE, uri, body, headers))
    case _      => IO.pure(None)
  }

  private def handleResponse[O](client: Client[IO], request: Request[IO])(implicit
      entityDecoder: Decoder[O]
  ): IO[Option[O]] = {
    val res: IO[Either[String, O]] = client.run(request).use { (response: Response[IO]) =>
      response.bodyText.compile.string.map { bodyString =>
        if (response.status.isSuccess) {
          decode[O](bodyString) match {
            case Left(_)      => "".asLeft
            case Right(value) => value.asRight
          }
        } else bodyString.asLeft
      }
    }
    for {
      content <- res
      result <- content match {
        case Left(value) =>
          for {
            _ <- IO.println(value.filterNot(_ == '"'))
          } yield None
        case Right(value) => IO.pure(Some(value))
      }
    } yield result
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
    val res: OptionT[IO, Unit] = for {
      _        <- OptionT.liftF(IO.print("Username: "))
      line     <- OptionT.liftF(IO.readLine)
      username <- OptionT.fromOption[IO](Username(line))
      player <- OptionT(
        makeRequest[PlayerRequest, AuthPlayer](
          client,
          uri = uri / "player",
          POST,
          PlayerRequest(username).some,
        )
      )
      _ <- OptionT.liftF(playerRef.set(Some(player)))
      _ <- OptionT.liftF(IO.println(s"Logged as ${player.player.username.value}"))
    } yield ()
    res.value.void
  }

  private def login(playerRef: Ref[IO, Option[AuthPlayer]], client: Client[IO]): IO[Unit] = {
    val res: OptionT[IO, Unit] = for {
      _        <- OptionT.liftF(IO.print("Username: "))
      line     <- OptionT.liftF(IO.readLine)
      username <- OptionT.fromOption[IO](Username(line))
      player <- OptionT(
        makeRequest[PlayerRequest, AuthPlayer](
          client,
          uri / "player" / "login",
          POST,
          PlayerRequest(username).some,
        )
      )

      _ <- OptionT.liftF(playerRef.set(Some(player)))
      _ <- OptionT.liftF(IO.println(s"Logged as ${player.player.username.value}"))
    } yield ()
    res.value.void
  }

  private def logout(client: Client[IO], player: AuthPlayer, ref: Ref[IO, Option[AuthPlayer]]): IO[Unit] = for {
    _ <- makeRequest[Unit, Unit](client, uri / "player" / "logout", POST, headers = generateAuthHeader(player).some)
    _ <- ref.set(None)
  } yield ()

  private def createGame(player: AuthPlayer, client: Client[IO]): IO[Unit] = {
    val res = for {
      _ <- OptionT.liftF(IO.println("Creating game"))
      res <- OptionT(
        makeRequest[GameRequest, GameResponse](
          client,
          uri / "game",
          POST,
          GameRequest(2).some,
          generateAuthHeader(player).some
        )
      )
      _ <- OptionT.liftF(IO.println(res.show))
      _ <- OptionT.liftF(joinGameRequest(player, res.id.id))
    } yield ()
    res.value.void
  }

  private def listGames(player: AuthPlayer, client: Client[IO]): IO[Unit] =
    for {
      _   <- IO.println("Getting games")
      res <- makeRequest[Unit, GamesResponse](client, uri / "game" / "all", GET, headers = generateAuthHeader(player).some)
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
