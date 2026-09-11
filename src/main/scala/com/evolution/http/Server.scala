package com.evolution.http

import cats.data.{Kleisli, OptionT}
import cats.effect.*
import cats.effect.std.Queue
import cats.implicits.toSemigroupKOps
import com.comcast.ip4s.*
import com.evolution.game.{GameRepositoryInMem, GameService, WebsocketService, WebsocketServiceImpl}
import com.evolution.player.Player.IdlePlayer
import com.evolution.player.*
import org.http4s.ember.server.EmberServerBuilder
import org.http4s.server.AuthMiddleware
import org.http4s.server.middleware.ErrorHandling
import org.http4s.server.websocket.WebSocketBuilder2
import org.http4s.websocket.WebSocketFrame
import org.http4s.{AuthedRoutes, HttpApp, HttpRoutes, Response, Status}

object Server extends IOApp {

  private def httpApp(
      gameService: GameService[IO],
      playerService: PlayerService[IO],
      websocketService: WebsocketService[IO]
  ): IO[WebSocketBuilder2[IO] => HttpApp[IO]] =
    IO.pure { wsb =>
      ErrorHandling {
        Seq(
          PlayerController.playerRoute(playerService),
          GameController.gameRoutes[IO](playerService, gameService, websocketService)(wsb),
          authedKleisli(playerService, gameService)
        ).reduce(_ <+> _)
      }.orNotFound
    }

  private val gameRoutes: GameService[IO] => AuthedRoutes[IdlePlayer, IO] = { gameService =>
    GameController.gameRouteWithAuth(gameService)
  }

  private val playerAuthedRoutes: PlayerService[IO] => AuthedRoutes[IdlePlayer, IO] = { playerService =>
    PlayerController.playerRouteAuthenticated(playerService)
  }

  private val onFailure: AuthedRoutes[PlayerRepositoryError, IO] = Kleisli { _ =>
    OptionT.pure[IO](Response[IO](status = Status.Unauthorized))
  }

  private val authMiddleware: PlayerService[IO] => AuthMiddleware[IO, IdlePlayer] = { (service: PlayerService[IO]) =>
    AuthMiddleware(MyAuthMiddleware.authPlayerEither(service), onFailure)
  }

  private val authedKleisli: (PlayerService[IO], GameService[IO]) => HttpRoutes[IO] = {
    (
        playerService: PlayerService[IO],
        gameService: GameService[IO]
    ) =>
      val middleware = authMiddleware(playerService)
      val authRoutes = Seq(gameRoutes(gameService), playerAuthedRoutes(playerService)).reduce(_ <+> _)
      middleware(authRoutes)
  }

  override def run(args: List[String]): IO[ExitCode] = {
    GameRepositoryInMem.make[IO].use { repo =>
      for {
        players     <- Ref[IO].of(Map[PlayerId, IdlePlayer]().empty)
        tokens      <- Ref[IO].of(Map[PlayerId, Token]().empty)
        passwords   <- Ref[IO].of(Map[PlayerId, PasswordValidationInfo]().empty)
        connections <- Ref[IO].of(Map[PlayerId, Queue[IO, WebSocketFrame]]().empty)
        websocketService = new WebsocketServiceImpl[IO](connections)
        playerRepo       = new PlayerRepositoryInMem[IO](players, tokens, passwords)
        playerService    = new PlayerService[IO](playerRepo)
        gameService      = new GameService[IO](repo, websocketService)
        app <- httpApp(gameService, playerService, websocketService)
        exitCode <- EmberServerBuilder
          .default[IO]
          .withHost(ipv4"127.0.0.1")
          .withPort(port"9001")
          .withHttpWebSocketApp(wsb => app(wsb))
          .build
          .use(_ => IO.never)
          .as(ExitCode.Success)
      } yield exitCode
    }
  }
}
