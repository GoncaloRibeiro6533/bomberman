package com.evolution.http

import cats.data.{Kleisli, OptionT}
import cats.effect.*
import cats.implicits.toSemigroupKOps
import com.comcast.ip4s.*
import com.evolution.game.{GameRepositoryInMem, GameService}
import com.evolution.player.Player.IdlePlayer
import com.evolution.player.{PlayerId, PlayerRepositoryError, PlayerRepositoryInMem, PlayerService, Token}
import org.http4s.{AuthedRoutes, HttpApp, HttpRoutes, Response, Status}
import org.http4s.ember.server.EmberServerBuilder
import org.http4s.server.AuthMiddleware
import org.http4s.server.middleware.ErrorHandling
import org.http4s.server.websocket.WebSocketBuilder2

object Server extends IOApp {

  private def httpApp(
      gameService: GameService[IO],
      playerService: PlayerService[IO]
  ): IO[WebSocketBuilder2[IO] => HttpApp[IO]] =
    IO.pure { wsb =>
      ErrorHandling {
        Seq(
          PlayerController.playerRoute(playerService),
          authedKleisli(playerService, gameService, wsb)
        ).reduce(_ <+> _)
      }.orNotFound
    }

  private val gameRoutes: (GameService[IO], WebSocketBuilder2[IO]) => AuthedRoutes[IdlePlayer, IO] = {
    (gameService, wsb) =>
      GameController.gameRoute(gameService)(wsb)
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

  private val authedKleisli: (PlayerService[IO], GameService[IO], WebSocketBuilder2[IO]) => HttpRoutes[IO] = {
    (
        playerService: PlayerService[IO],
        gameService: GameService[IO],
        wsb: WebSocketBuilder2[IO]
    ) =>
      val middleware = authMiddleware(playerService)
      val authRoutes = Seq(gameRoutes(gameService, wsb), playerAuthedRoutes(playerService)).reduce(_ <+> _)
      middleware(authRoutes)
  }

  override def run(args: List[String]): IO[ExitCode] = {
    GameRepositoryInMem.make[IO].use { repo =>
      for {
        players <- Ref[IO].of(Map[PlayerId, IdlePlayer]())
        tokens  <- Ref[IO].of(Map[PlayerId, Token]())
        clock         = Clock[IO]
        playerRepo    = new PlayerRepositoryInMem[IO](players, tokens)
        playerService = new PlayerService[IO](playerRepo, clock)
        gameService   = new GameService[IO](repo, clock)
        app <- httpApp(gameService, playerService)
        exitCode <- EmberServerBuilder
          .default[IO]
          .withHost(ipv4"127.0.0.1")
          .withPort(port"9001")
          .withHttpWebSocketApp(wsb => app(wsb))
          .build
          .useForever
      } yield exitCode
    }
  }
}
