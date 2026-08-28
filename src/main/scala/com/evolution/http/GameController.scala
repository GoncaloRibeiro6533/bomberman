package com.evolution.http

import cats.effect.kernel.Async
import cats.effect.std.Queue
import cats.syntax.all.*
import com.evolution.cell.PositiveNumber
import com.evolution.command.Command
import com.evolution.game.{GameId, GameService, GameRepositoryError}
import com.evolution.http.dtos.GameDto.{GameIdDto, GameInDto, GamesOut}
import com.evolution.player.Player.IdlePlayer
import io.circe.parser.*
import io.circe.syntax.EncoderOps
import org.http4s.dsl.Http4sDsl
import org.http4s.server.websocket.WebSocketBuilder2
import org.http4s.websocket.WebSocketFrame
import org.http4s.{AuthedRoutes, Response}

object GameController {
  import org.http4s.circe.CirceEntityCodec.*

  private def parseCommand(json: String): Option[Command] = {
    decode[Command](json) match {
      case Left(_)      => None
      case Right(value) => value.some
    }
  }

  private def handleFrame[F[_]: Async](frame: WebSocketFrame, queue: Queue[F, Command]): F[Unit] =
    frame match {
      case WebSocketFrame.Text(text, _) =>
        parseCommand(text) match {
          case Some(value) => queue.offer(value)
          case None        => Async[F].unit
        }
      case WebSocketFrame.Close(_) => Async[F].unit
      case _                       => Async[F].unit
    }

  def gameRoute[F[_]: Async](
      service: GameService[F]
  )(wsb: WebSocketBuilder2[F]): AuthedRoutes[IdlePlayer, F] = {
    val dsl = Http4sDsl[F]
    import dsl.*
    AuthedRoutes.of[IdlePlayer, F] {
      case GET -> Root / "game" / "all" as _ =>
        for {
          games    <- service.getAllGames
          response <- Ok(GamesOut(games.map(game => GameIdDto(game.id))))
        } yield response
      case req @ POST -> Root / "game" as player =>
        for {
          gameIn <- req.req.as[GameInDto]
          positiveNumber = PositiveNumber.fromInt(gameIn.nPlayers)
          response <- positiveNumber match {
            case Some(value) =>
              for {
                game <- service.createGame(value, player)
                gameResponse = Created(game)
              } yield gameResponse
            case None => Async[F].pure(BadRequest("Invalid number of players"))
          }
          res <- response
        } yield res
      case GET -> Root / "game" / UUIDVar(gameId) / "join" as player =>
        for {
          game <- service.joinGame(GameId(gameId), player)
          response <- game match {
            case Right(value) =>
              wsb.build(
                receive = _.evalMap { frame =>
                  handleFrame[F](frame, value.queue)
                },
                send = value.topic
                  .subscribe(maxQueued = 10)
                  .map(game => WebSocketFrame.Text(game.asJson.noSpaces))
              )
            case Left(value) => value.toResponse
          }
        } yield response
    }
  }

  trait GameServiceErrorsOps {
    def toStatus[F[_]: Async](error: GameRepositoryError): F[Response[F]]
  }

  implicit val gameServiceErrors: GameServiceErrorsOps = new GameServiceErrorsOps {
    override def toStatus[F[_]: Async](error: GameRepositoryError): F[Response[F]] = {
      val dsl = Http4sDsl[F]
      import dsl.*
      error match {
        case GameRepositoryError.GameNotFound        => NotFound("Game not found")
        case GameRepositoryError.GameAlreadyFinished => Conflict("Game already finished")
        case GameRepositoryError.GameAlreadyRunning  => Conflict("Game already started")
      }
    }
  }

  implicit class ToResponse(error: GameRepositoryError) {
    def toResponse[F[_]: Async](implicit errorsConverter: GameServiceErrorsOps): F[Response[F]] =
      errorsConverter.toStatus[F](error)
  }
}
