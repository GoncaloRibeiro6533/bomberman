package com.evolution.http

import cats.data.NonEmptyList
import cats.effect.kernel.Async
import cats.syntax.all.*
import com.evolution.player.*
import com.evolution.player.Player.IdlePlayer
import fs2.hashing.{Hash, HashAlgorithm, Hashing}
import fs2.{Chunk, Pipe}
import org.http4s.dsl.Http4sDsl
import org.http4s.headers.`WWW-Authenticate`
import org.http4s.{AuthedRoutes, Challenge, HttpRoutes, Response}

object HashUtils {

  def hash[F[_]: Async](value: String): F[Option[String]] = {
    val pipe: Pipe[F, Byte, Hash]       = Hashing.forSync[F].hash(HashAlgorithm.SHA256)
    val stream: fs2.Stream[F, Byte]     = fs2.Stream.chunk(Chunk.array(value.getBytes))
    val hashedPassword: F[Option[Hash]] = stream.through(pipe).compile.last
    val res: F[Option[String]] = hashedPassword.map {
      case Some(value) => Some(value.toString())
      case None        => None
    }
    res
  }
}

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
          playerIn <- req.as[PlayerCredentials]
          player   <- service.createPlayer(playerIn.username, playerIn.password)
          res <- player match {
            case Left(value)  => value.toResponse
            case Right(value) => Created(value)
          }
        } yield res
      case req @ POST -> Root / "player" / "login" =>
        for {
          playerIn <- req.as[PlayerCredentials]
          player   <- service.login(playerIn.username, playerIn.password.value)
          res <- player match {
            case Left(value)  => value.toResponse
            case Right(value) => Created(value)
          }
        } yield res
    }

  }

  def playerRouteAuthenticated[F[_]: Async](
      service: PlayerService[F]
  ): AuthedRoutes[IdlePlayer, F] = {
    val dsl = Http4sDsl[F]
    import dsl.*
    AuthedRoutes.of[IdlePlayer, F] { case POST -> Root / "player" / "logout" as player =>
      for {
        _   <- service.logOut(player)
        res <- Ok("Player Logged out")
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
      val authChallenge = `WWW-Authenticate`(NonEmptyList.of(Challenge("Bearer", "")))
      error match {
        case PlayerNotFound       => NotFound("Player not found")
        case TokenNotFound        => NotFound("Token not found")
        case UsernameAlreadyTaken => Conflict("Username already taken")
        case InvalidPassword      => BadRequest("Password must have at least 12 characters")
        case WrongPassword =>
          Unauthorized(authChallenge, "Invalid username or password")
        case com.evolution.player.Unauthorized | NoToken | InvalidUUID =>
          Unauthorized(authChallenge)
      }
    }
  }

  implicit class ToResponse(error: PlayerRepositoryError) {
    def toResponse[F[_]: Async](implicit errorsConverter: PlayerServiceErrorsOps): F[Response[F]] =
      errorsConverter.toStatus[F](error)
  }

}
