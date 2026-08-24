package com.evolution.http

import cats.data.Kleisli
import cats.effect.kernel.Async
import cats.effect.std.Queue
import cats.syntax.all.*
import com.evolution.cell.PositiveNumber
import com.evolution.command.Command
import com.evolution.game.{GameId, GameService}
import com.evolution.http.dtos.GameDto.{GameIdDto, GameInDto, GamesOut}
import com.evolution.player.Player.IdlePlayer
import com.evolution.player.{PlayerId, Score, Username}
import io.circe.parser.*
import io.circe.syntax.EncoderOps
import org.http4s.dsl.Http4sDsl
import org.http4s.server.websocket.WebSocketBuilder2
import org.http4s.websocket.WebSocketFrame
import org.http4s.{HttpRoutes, Request, Response}

import java.util.UUID

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
  )(wsb: WebSocketBuilder2[F]): Kleisli[F, Request[F], Response[F]] = {
    val dsl = Http4sDsl[F]
    import dsl.*
    HttpRoutes.of[F] {
      case GET -> Root / "game" / "all" =>
        for {
          games    <- service.getAllGames
          response <- Ok(GamesOut(games.map(game => GameIdDto(game.id))))
        } yield response
      case req @ POST -> Root / "game" =>
        val player = IdlePlayer(
          PlayerId(UUID.fromString("c0707ed0-bffe-4a8b-a155-5c2642b45982")),
          Username("Bob1").get,
          Score.Zero
        )
        for {
          gameIn <- req.as[GameInDto]
          positiveNumber = PositiveNumber.fromInt(gameIn.nPlayers)
          response <- positiveNumber match {
            case Some(value) =>
              for {
                game <- service.createGame(value, player)
                gameResponse = game match {
                  case Left(value)  => BadRequest(value.toString)
                  case Right(value) => Created(value)
                }
              } yield gameResponse
            case None => Async[F].pure(BadRequest("Invalid number of players"))
          }
          res <- response
        } yield res
      case GET -> Root / "game" / UUIDVar(gameId) / "join" =>
        for {
          game <- service.joinGame(GameId(gameId))
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
            case Left(value) => BadRequest(value.toString)
          }
        } yield response
    }
  }.orNotFound
}
