package com.evolution.game

import cats.effect.kernel.{Async, Ref, Resource}
import cats.effect.std.Queue
import cats.syntax.all.*
import com.evolution.player.PlayerId
import io.circe.parser.decode
import io.circe.syntax.EncoderOps
import io.circe.{Decoder, Encoder}
import org.http4s.Response
import org.http4s.server.websocket.WebSocketBuilder2
import org.http4s.websocket.WebSocketFrame
import org.typelevel.log4cats.Logger
import org.typelevel.log4cats.slf4j.Slf4jLogger
import scodec.bits.ByteVector

import scala.concurrent.duration.DurationInt

trait WebsocketService[F[_]] {
  def connect[A: Decoder](
      playerId: PlayerId,
      webSocketBuilder2: WebSocketBuilder2[F],
      onMessage: A => F[Unit]
  ): F[Response[F]]
  def disconnect(playerId: PlayerId, reason: String): F[Unit]
  def send[A: Encoder](playerId: PlayerId, message: A): F[Unit]
}

class WebsocketServiceImpl[F[_]: Async] private (
    private val connections: Ref[F, Map[PlayerId, Queue[F, WebSocketFrame]]]
) extends WebsocketService[F] {

  override def disconnect(playerId: PlayerId, reason: String): F[Unit] = {
    for {
      playerConnections <-
        connections.modify { currentState =>
          val connectionsToClose = currentState.get(playerId)
          connectionsToClose match {
            case Some(conn) => (currentState.removed(playerId), Some(conn))
            case None       => (currentState, None)
          }
        }
      _ <- Logger[F].info(s"Player with id: ${playerId.value} disconnected")
      _ <- playerConnections match {
        case Some(queue) => WebSocketFrame.Close(1000, reason).traverseVoid(frame => queue.offer(frame))
        case None        => Async[F].unit
      }
    } yield ()
  }

  override def send[A: Encoder](playerId: PlayerId, message: A): F[Unit] = for {
    map <- connections.get
    _ <- map.get(playerId) match {
      case Some(value) =>
        value.offer(WebSocketFrame.Text(message.asJson.noSpaces))
      case None => Async[F].unit
    }
  } yield ()

  implicit def logger: Logger[F] = Slf4jLogger.getLogger[F]

  override def connect[A: Decoder](
      playerId: PlayerId,
      webSocketBuilder2: WebSocketBuilder2[F],
      onMessage: A => F[Unit]
  ): F[Response[F]] =
    for {
      queue <- Queue.bounded[F, WebSocketFrame](10)
      _     <- connections.modify(currentConnections => (currentConnections.updated(playerId, queue), ()))
      _     <- Logger[F].info(s"Player with id: ${playerId.value} connected")
      response <- webSocketBuilder2
        .withOnClose(disconnect(playerId, "connection closed abruptly"))
        .build(
          send = fs2.Stream
            .fromQueueUnterminated(queue = queue)
            .merge(
              fs2.Stream.awakeEvery(5.seconds).map(_ => WebSocketFrame.Ping(ByteVector.fromUUID(playerId.value)))
            ),
          receive = _.evalMap { frame =>
            handleFrame(
              frame = frame,
              onError = { () =>
                for {
                  _ <- Logger[F].info(s"Failed to parse message: $frame from player with id: ${playerId.value}")
                  _ <- queue.offer(WebSocketFrame.Text("could not parse message"))
                } yield ()
              },
              onMessage = onMessage,
              onPing = { uuid =>
                queue.offer(WebSocketFrame.Pong(uuid))
              }
            )
          }
        )
    } yield response

  private def parseCommand[A: Decoder](json: String): Option[A] =
    decode[A](json).toOption

  private def handleFrame[A: Decoder](
      frame: WebSocketFrame,
      onError: () => F[Unit],
      onMessage: A => F[Unit],
      onPing: ByteVector => F[Unit]
  ): F[Unit] =
    frame match {
      case WebSocketFrame.Text(text, _) =>
        parseCommand[A](text) match {
          case Some(value) => onMessage(value)
          case None        => onError()
        }
      case WebSocketFrame.Close(_)    => Async[F].unit
      case WebSocketFrame.Ping(value) => onPing(value)
      case _                          => Async[F].unit
    }
}

object WebsocketServiceImpl {

  def make[F[_]: Async]: Resource[F, WebsocketServiceImpl[F]] =
    for {
      connections <- Resource.make(Ref[F].of(Map[PlayerId, Queue[F, WebSocketFrame]]().empty)) { stateRef =>
        for {
          map <- stateRef.get
          _ <- map.values.toVector.traverseVoid(queue =>
            WebSocketFrame.Close(1000, "server shut down").traverseVoid(frame => queue.offer(frame))
          )
        } yield ()
      }
    } yield new WebsocketServiceImpl[F](connections)
}
