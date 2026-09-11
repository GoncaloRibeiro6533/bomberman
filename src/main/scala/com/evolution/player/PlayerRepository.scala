package com.evolution.player

import com.evolution.player.Player.IdlePlayer

import java.time.Instant

trait PlayerRepository[F[_]] {
  def findPlayer(playerId: PlayerId): F[Option[IdlePlayer]]
  def findPlayerByUsername(username: Username): F[Option[IdlePlayer]]
  def findPlayerPassword(playerId: PlayerId): F[Option[PasswordValidationInfo]]
  def insertPlayer(
      username: Username,
      passwordValidationInfo: PasswordValidationInfo
  ): F[Either[PlayerRepositoryError, IdlePlayer]]
  def update(player: IdlePlayer): F[Either[PlayerRepositoryError, IdlePlayer]]
  def createToken(createdAt: Instant, value: TokenInfo, player: IdlePlayer): F[Token]
  def deleteToken(player: IdlePlayer): F[Either[PlayerRepositoryError, Unit]]
  def findToken(value: TokenInfo): F[Option[Token]]
  def findByToken(tokenInfo: TokenInfo): F[Either[PlayerRepositoryError, IdlePlayer]]
}
