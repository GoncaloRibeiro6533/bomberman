package com.evolution.http

import cats.data.NonEmptyList
import cats.effect.kernel.Async
import cats.syntax.all.*
import com.evolution.http.dtos.PlayerDto.PlayerInDto
import com.evolution.player.*
import org.http4s.dsl.Http4sDsl
import org.http4s.headers.`WWW-Authenticate`
import org.http4s.{Challenge, HttpRoutes, Response}

object PlayerController {
  import org.http4s.circe.CirceEntityCodec.*

  def playerRoute[F[_]: Async](
      service: PlayerService[F]
  ): HttpRoutes[F] = {
    val dsl = Http4sDsl[F]
    import dsl.*
    HttpRoutes.of[F] {
      case req @ POST -> Root / "player" =>
        for {
          playerIn <- req.as[PlayerInDto]
          player   <- service.createPlayer(playerIn.username)
          res <- player match {
            case Left(value)  => value.toResponse
            case Right(value) => Created(value)
          }
        } yield res
      case req @ POST -> Root / "player" / "login" =>
        for {
          playerIn <- req.as[PlayerInDto]
          player   <- service.login(playerIn.username)
          res <- player match {
            case Left(value)  => value.toResponse
            case Right(value) => Created(value)
          }
        } yield res
    }

  }

  trait PlayerServiceErrorsOps {
    def toStatus[F[_]: Async](error: PlayerRepositoryError): F[Response[F]]
  }

  implicit val playerServiceErrors: PlayerServiceErrorsOps = new PlayerServiceErrorsOps {
    override def toStatus[F[_]: Async](error: PlayerRepositoryError): F[Response[F]] = {
      val dsl = Http4sDsl[F]
      import dsl.*
      error match {
        case PlayerNotFound       => NotFound("Player not found")
        case TokenNotFound        => NotFound("Toke not found")
        case UsernameAlreadyTaken => Conflict("Username already taken")
        case com.evolution.player.Unauthorized | NoToken | InvalidUUID =>
          Unauthorized(`WWW-Authenticate`.apply(NonEmptyList.of(Challenge("WWW-Authenticate", ""))))
      }
    }
  }

  implicit class ToResponse(error: PlayerRepositoryError) {
    def toResponse[F[_]: Async](implicit errorsConverter: PlayerServiceErrorsOps): F[Response[F]] =
      errorsConverter.toStatus[F](error)
  }

}
