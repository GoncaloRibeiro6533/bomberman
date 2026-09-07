package com.evolution.player

import cats.data.EitherT
import cats.effect.Ref
import cats.effect.kernel.Async
import cats.syntax.all.*
import com.evolution.player.Player.IdlePlayer
import com.evolution.util.IdGenerator
import io.circe.generic.JsonCodec

import java.time.Instant
import java.util.UUID

@JsonCodec
case class TokenInfo(value: UUID)
@JsonCodec
case class Token(token: TokenInfo, playerId: PlayerId, createdAt: Instant)

sealed trait PlayerRepositoryError

case object PlayerNotFound       extends PlayerRepositoryError
case object TokenNotFound        extends PlayerRepositoryError
case object UsernameAlreadyTaken extends PlayerRepositoryError
case object InvalidUUID          extends PlayerRepositoryError
case object NoToken              extends PlayerRepositoryError
case object Unauthorized         extends PlayerRepositoryError

class PlayerRepositoryInMem[F[_]: Async](
    private val players: Ref[F, Map[PlayerId, IdlePlayer]],
    private val tokens: Ref[F, Map[PlayerId, Token]]
) extends PlayerRepository[F] {

  override def findPlayer(playerId: PlayerId): F[Option[IdlePlayer]] =
    players.get.map(_.get(playerId))

  override def findPlayerByUsername(username: Username): F[Option[IdlePlayer]] =
    players.get.map(_.find(_._2.username == username).map(_._2))

  override def insertPlayer(username: Username): F[Either[PlayerRepositoryError, IdlePlayer]] = {
    for {
      uuid <- IdGenerator.generateUUID[F]
      playerId = PlayerId(uuid)
      player   = IdlePlayer(playerId, username)
      res <- players.modify { oldPlayers =>
        oldPlayers.values.find(player => player.username == username) match {
          case Some(_) => (oldPlayers, UsernameAlreadyTaken.asLeft)
          case None    => (oldPlayers.updated(player.id, player), player.asRight)
        }
      }
    } yield res
  }

  override def update(player: IdlePlayer): F[Either[PlayerRepositoryError, IdlePlayer]] = players.modify { oldPlayers =>
    oldPlayers.get(player.id) match {
      case Some(_) => (oldPlayers.updated(player.id, player), player.asRight)
      case None    => (oldPlayers, PlayerNotFound.asLeft)
    }
  }

  override def createToken(createdAt: Instant, value: TokenInfo, player: IdlePlayer): F[Token] = tokens.modify {
    oldTokens =>
      val token = Token(token = value, playerId = player.id, createdAt = createdAt)
      (oldTokens.removed(player.id).updated(player.id, token), token)
  }

  override def deleteToken(player: IdlePlayer): F[Either[PlayerRepositoryError, Unit]] = tokens.modify { oldTokens =>
    oldTokens.get(player.id) match {
      case Some(value) => (oldTokens.removed(player.id), ().asRight)
      case None        => (oldTokens, TokenNotFound.asLeft)
    }
  }

  override def findToken(value: TokenInfo): F[Option[Token]] =
    tokens.get.map(
      _.find { case (_, token) =>
        token.token == value
      }.map(_._2)
    )

  override def findByToken(value: TokenInfo): F[Either[PlayerRepositoryError, IdlePlayer]] = {
    val res = for {
      token  <- EitherT.fromOptionF[F, PlayerRepositoryError, Token](findToken(value), TokenNotFound)
      player <- EitherT.fromOptionF[F, PlayerRepositoryError, IdlePlayer](findPlayer(token.playerId), PlayerNotFound)
    } yield player
    res.value
  }
}
