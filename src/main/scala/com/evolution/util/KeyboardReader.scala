package com.evolution.util

import cats.Monad
import cats.effect.std.Console
import cats.implicits.*
import com.evolution.game.Command.*
import com.evolution.direction.Direction.*
import com.evolution.game.Command
import com.evolution.player.PlayerId
import com.evolution.websocket.WebsocketMessage

object KeyboardReader {

  private def parseCommand(char: Char, playerId: PlayerId): Option[Command] = char.toUpper match {
    case 'W' => Movement(playerId, Up).some
    case 'A' => Movement(playerId, Left).some
    case 'S' => Movement(playerId, Down).some
    case 'D' => Movement(playerId, Right).some
    case ' ' => PlantBomb(playerId).some
    case _   => none
  }

  private def parseMessage(char: Char): Option[WebsocketMessage] = char.toUpper match {
    case 'W' => WebsocketMessage.Movement(Up).some
    case 'A' => WebsocketMessage.Movement(Left).some
    case 'S' => WebsocketMessage.Movement(Down).some
    case 'D' => WebsocketMessage.Movement(Right).some
    case ' ' => WebsocketMessage.PlantBomb.some
    case _   => none
  }

  def readCommand[F[_]: Monad: Console]: F[WebsocketMessage] = for {
    line <- Console[F].readLine
    cmd <- line.headOption match {
      case Some(value) =>
        parseMessage(value) match {
          case Some(value) => Monad[F].pure(value)
          case None        => readCommand[F]
        }
      case None => readCommand[F]
    }
  } yield cmd

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
