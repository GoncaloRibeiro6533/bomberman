package com.evolution.player

import cats.data.EitherT
import cats.effect.kernel.Async
import com.evolution.player.Player.IdlePlayer
import com.evolution.util.{HashUtils, IdGenerator}
import org.typelevel.log4cats.Logger
import org.typelevel.log4cats.slf4j.Slf4jLogger

class PlayerService[F[_]: Async](playerRepository: PlayerRepository[F]) {
  implicit def logger: Logger[F] = Slf4jLogger.getLogger[F]

  def createPlayer(username: Username, password: PasswordIn): F[Either[PlayerRepositoryError, IdlePlayer]] = {
    val res = for {
      hash         <- EitherT.fromOptionF(HashUtils.hash(password.value), InvalidPassword)
      passwordInfo <- EitherT.fromOption(PasswordValidationInfo(hash), InvalidPassword)
      player       <- EitherT(playerRepository.insertPlayer(username, passwordInfo))
      _ <- EitherT.liftF[F, PlayerRepositoryError, Unit](
        Logger[F].info(s"Created player: ${username.value} with id: ${player.id.value}")
      )
    } yield player
    res.value
  }

  def login(username: Username, password: String): F[Either[PlayerRepositoryError, AuthPlayer]] = {
    val res: EitherT[F, PlayerRepositoryError, AuthPlayer] = for {
      player         <- EitherT.fromOptionF(playerRepository.findPlayerByUsername(username), PlayerNotFound)
      hash           <- EitherT.fromOptionF(HashUtils.hash(password), InvalidPassword)
      storedPassword <- EitherT.fromOptionF(playerRepository.findPlayerPassword(player.id), PlayerNotFound)
      _              <- EitherT.cond[F](hash == storedPassword.value, (), WrongPassword)
      now            <- EitherT.liftF(Async[F].realTimeInstant)
      uuid           <- EitherT.liftF(IdGenerator.generateUUID)
      token          <- EitherT.liftF(playerRepository.createToken(now, TokenInfo(uuid), player))
      _ <- EitherT.liftF(Logger[F].info(s"Created token for: ${username.value} with id: ${player.id.value}"))
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
