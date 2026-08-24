package com.evolution.http

import cats.effect.{Clock, ExitCode, IO, IOApp}
import com.comcast.ip4s.*
import com.evolution.game.{GameRepositoryInMem, GameService}
import org.http4s.HttpApp
import org.http4s.ember.server.EmberServerBuilder
import org.http4s.server.middleware.ErrorHandling
import org.http4s.server.websocket.WebSocketBuilder2

object Server extends IOApp {

  private def httpApp(gameService: GameService[IO]): IO[WebSocketBuilder2[IO] => HttpApp[IO]] =
    IO.pure { wsb =>
      ErrorHandling {
        GameController.gameRoute(gameService)(wsb)
      }
    }

  override def run(args: List[String]): IO[ExitCode] = {
    GameRepositoryInMem.make[IO].use { repo =>
      httpApp(new GameService[IO](repo, Clock[IO])).flatMap {
        EmberServerBuilder
          .default[IO]
          .withHost(ipv4"127.0.0.1")
          .withPort(port"9001")
          .withHttpWebSocketApp(_)
          .build
          .useForever
      }
    }
  }
}
