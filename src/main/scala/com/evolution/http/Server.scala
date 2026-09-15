package com.evolution.http

import cats.data.{Kleisli, NonEmptyList, OptionT}
import cats.effect.*
import cats.implicits.toSemigroupKOps
import com.comcast.ip4s.*
import com.evolution.game.{GameRepositoryInMem, GameService, WebsocketService, WebsocketServiceImpl}
import com.evolution.player.*
import com.evolution.player.Player.IdlePlayer
import org.http4s.dsl.Http4sDsl
import org.http4s.ember.server.EmberServerBuilder
import org.http4s.headers.`WWW-Authenticate`
import org.http4s.server.AuthMiddleware
import org.http4s.server.middleware.ErrorHandling
import org.http4s.server.websocket.WebSocketBuilder2
import org.http4s.{AuthedRoutes, Challenge, HttpApp, HttpRoutes}

object Server extends ResourceApp.Forever {

  private def httpApp(
      gameService: GameService[IO],
      playerService: PlayerService[IO],
      websocketService: WebsocketService[IO]
  ): IO[WebSocketBuilder2[IO] => HttpApp[IO]] =
    IO.pure { wsb =>
      ErrorHandling {
        Seq(
          PlayerRoutes.playerRoute(playerService),
          GameRoutes.gameRoutes[IO](playerService, gameService, websocketService)(wsb),
          authedKleisli(playerService, gameService)
        ).reduce(_ <+> _)
      }.orNotFound
    }

  private val gameRoutes: GameService[IO] => AuthedRoutes[IdlePlayer, IO] = { gameService =>
    GameRoutes.gameRouteWithAuth(gameService)
  }

  private val playerAuthedRoutes: PlayerService[IO] => AuthedRoutes[IdlePlayer, IO] = { playerService =>
    PlayerRoutes.playerRouteAuthenticated(playerService)
  }

  private val onFailure: AuthedRoutes[PlayerRepositoryError, IO] = Kleisli { _ =>
    val dsl = Http4sDsl[IO]
    import dsl.*
    OptionT.liftF(
      Unauthorized(`WWW-Authenticate`(NonEmptyList.of(Challenge("Bearer", ""))))
    )
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

  override def run(args: List[String]): Resource[IO, Unit] = {
    for {
      websocketService <- WebsocketServiceImpl.make[IO]
      playerRepo       <- PlayerRepositoryInMem.make[IO]
      gameRepo         <- GameRepositoryInMem.make[IO]
      playerService = new PlayerService[IO](playerRepo)
      gameService   = new GameService[IO](gameRepo, websocketService)
      app <- Resource.eval(httpApp(gameService, playerService, websocketService))
      _ <- EmberServerBuilder
        .default[IO]
        .withHost(host"127.0.0.1")
        .withPort(port"9001")
        .withHttpWebSocketApp(wsb => app(wsb))
        .build
    } yield ()
  }
}
