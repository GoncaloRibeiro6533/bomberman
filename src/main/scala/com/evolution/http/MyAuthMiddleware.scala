package com.evolution.http

import cats.data.{EitherT, Kleisli}
import cats.effect.kernel.Async
import com.evolution.player.Player.IdlePlayer
import com.evolution.player.{InvalidUUID, NoToken, PlayerRepositoryError, PlayerService, TokenInfo, Unauthorized}
import org.http4s.Credentials.Token
import org.http4s.headers.Authorization
import org.http4s.{AuthScheme, Request}

import java.util.UUID
import scala.util.Try

/** Middleware to force authentication via Bearer Token Based on Rock the JVM examples
  * [https://rockthejvm.com/articles/authentication-with-scala-and-http4s#basic-authentication:~:text=Authentication-,Basic%20Authentication,-Digest%20Authentication]
  */

object MyAuthMiddleware {
// TODO change To Sattus
  def authPlayerEither[F[_]: Async](
      service: PlayerService[F]
  ): Kleisli[F, Request[F], Either[PlayerRepositoryError, IdlePlayer]] = Kleisli { req =>
    val authHeader: Option[Authorization] = req.headers.get[Authorization]
    authHeader match {
      case Some(Authorization(Token(AuthScheme.Bearer, token))) =>
        val res = for {
          uuid <- EitherT.fromOption(Try(UUID.fromString(token)).toOption, InvalidUUID)
          user <- EitherT(service.getPlayer(TokenInfo(uuid)))
        } yield user
        res.value
      case Some(_) => Async[F].pure(Left(NoToken))
      case None    => Async[F].pure(Left(Unauthorized))
    }
  }
}
