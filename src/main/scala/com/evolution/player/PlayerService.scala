package com.evolution.player

import cats.data.EitherT
import cats.effect.kernel.{Async}
import com.evolution.player.Player.IdlePlayer
import com.evolution.util.IdGenerator
import io.circe.generic.JsonCodec

@JsonCodec
final case class AuthPlayer(player: IdlePlayer, token: Token)

class PlayerService[F[_]: Async](private val playerRepository: PlayerRepository[F]) {

  def createPlayer(username: Username): F[Either[PlayerRepositoryError, AuthPlayer]] = {
    val res = for {
      player <- EitherT(playerRepository.insertPlayer(username))
      now    <- EitherT.right(Async[F].realTimeInstant)
      uuid   <- EitherT.right(IdGenerator.generateUUID[F])
      token <- EitherT.liftF[F, PlayerRepositoryError, Token](
        playerRepository.createToken(now, TokenInfo(uuid), player)
      )
    } yield AuthPlayer(player = player, token = token)
    res.value
  }

  def login(username: Username): F[Either[PlayerRepositoryError, AuthPlayer]] = {
    val res: EitherT[F, PlayerRepositoryError, AuthPlayer] = for {
      player <- EitherT.fromOptionF(playerRepository.findPlayerByUsername(username), PlayerNotFound)
      now    <- EitherT.liftF(Async[F].realTimeInstant)
      uuid   <- EitherT.liftF(IdGenerator.generateUUID)
      token  <- EitherT.liftF(playerRepository.createToken(now, TokenInfo(uuid), player))
    } yield AuthPlayer(player = player, token = token)
    res.value
  }

  def logOut(player: IdlePlayer): F[Either[PlayerRepositoryError, Unit]] =
    playerRepository.deleteToken(player)

  def getPlayer(tokenInfo: TokenInfo): F[Either[PlayerRepositoryError, IdlePlayer]] =
    playerRepository.findByToken(tokenInfo)

  def getPlayer(playerId: PlayerId): F[Option[IdlePlayer]] =
    playerRepository.findPlayer(playerId)

}
