package com.evolution.http

import cats.effect.kernel.Async
import cats.syntax.all.*
import com.evolution.cell.PositiveNumber
import com.evolution.game.GameRepositoryError.{GameAlreadyFull, PlayerAlreadyInGame}
import com.evolution.game.*
import com.evolution.player.Player.IdlePlayer
import com.evolution.player.{PlayerId, PlayerService}
import org.http4s.dsl.Http4sDsl
import org.http4s.server.websocket.WebSocketBuilder2
import org.http4s.{AuthedRoutes, HttpRoutes, Response}

object GameController {
  import org.http4s.circe.CirceEntityCodec.*

  def gameRouteWithAuth[F[_]: Async](
      service: GameService[F]
  ): AuthedRoutes[IdlePlayer, F] = {
    val dsl = Http4sDsl[F]
    import dsl.*
    AuthedRoutes.of[IdlePlayer, F] {
      case GET -> Root / "game" / "all" as _ =>
        for {
          games    <- service.getAllGames
          response <- Ok(GamesResponse(games.map(game => GameResponse(game.id))))
        } yield response
      case req @ POST -> Root / "game" as _ =>
        for {
          gameIn <- req.req.as[GameRequest]
          positiveNumber = PositiveNumber.fromInt(gameIn.nPlayers)
          response <- positiveNumber match {
            case Some(value) =>
              for {
                game <- service.createGame(value)
                gameResponse = Created(game)
              } yield gameResponse
            case None => Async[F].pure(BadRequest("Invalid number of players"))
          }
          res <- response
        } yield res
    }
  }

  def gameRoutes[F[_]: Async](
      playerService: PlayerService[F],
      gameService: GameService[F],
      websocketService: WebsocketService[F]
  )(wsb: WebSocketBuilder2[F]): HttpRoutes[F] = {
    val dsl = Http4sDsl[F]
    import dsl.*
    HttpRoutes.of[F] { case GET -> Root / "game" / UUIDVar(gameId) / "join" / UUIDVar(playerId) =>
      for {
        response <- websocketService.connect[Command](
          PlayerId(playerId),
          wsb,
          onMessage = { cmd =>
            for {
              commandRes <- gameService.sendCommand(GameId(gameId), cmd)
              _ <- commandRes match {
                case Left(value) => websocketService.disconnect(PlayerId(playerId), value.message)
                case Right(_)    => Async[F].unit
              }
            } yield ()
          }
        )
        player <- playerService.getPlayer(PlayerId(playerId))
        _ <- player match {
          case Some(value) =>
            for {
              joinRes <- gameService.sendCommand(GameId(gameId), Command.Join(value))
              _ <- joinRes match {
                case Left(_)  => websocketService.disconnect(value.id, "Game not found")
                case Right(_) => Async[F].unit
              }
            } yield ()
          case None => websocketService.disconnect(PlayerId(playerId), "Player not found")
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
        case GameRepositoryError.GameNotFound(message)        => NotFound(message)
        case GameRepositoryError.GameAlreadyFinished(message) => Conflict(message)
        case GameRepositoryError.GameAlreadyRunning(message)  => Conflict(message)
        case PlayerAlreadyInGame(message)                     => Conflict(message)
        case GameAlreadyFull(message)                         => Conflict(message)
      }
    }
  }

  implicit class ToResponse(error: GameRepositoryError) {
    def toResponse[F[_]: Async](implicit errorsConverter: GameServiceErrorsOps): F[Response[F]] =
      errorsConverter.toStatus[F](error)
  }
}
