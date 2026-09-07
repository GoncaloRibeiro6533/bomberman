package com.evolution.util

import cats.Monad
import cats.effect.std.Console
import cats.implicits.*
import com.evolution.game.Command.*
import com.evolution.direction.Direction.*
import com.evolution.game.Command
import com.evolution.player.PlayerId

object KeyboardReader {

  private def parseCommand(char: Char, playerId: PlayerId): Option[Command] = char.toUpper match {
    case 'W' => Movement(playerId, Up).some
    case 'A' => Movement(playerId, Left).some
    case 'S' => Movement(playerId, Down).some
    case 'D' => Movement(playerId, Right).some
    case ' ' => PlantBomb(playerId).some
    case _   => none
  }

  def readCommand[F[_]: Monad: Console](playerId: PlayerId): F[Command] = for {
    line <- Console[F].readLine
    cmd <- line.headOption match {
      case Some(value) =>
        parseCommand(value, playerId = playerId) match {
          case Some(value) => Monad[F].pure(value)
          case None        => readCommand[F](playerId)
        }
      case None => readCommand[F](playerId)
    }
  } yield cmd

}
