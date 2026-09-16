package com.evolution.websocket

import cats.effect.kernel.{Async, Ref, Resource}
import cats.effect.std.Queue
import cats.syntax.all.*
import com.evolution.player.PlayerId
import com.evolution.util.IdGenerator
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
  def disconnect(playerId: PlayerId, reason: String, connectionId: Option[ConnectionId] = None): F[Unit]
  def send[A: Encoder](playerId: PlayerId, message: A): F[Unit]
}

class WebsocketServiceImpl[F[_]: Async] private (
    private val connections: Ref[F, Map[PlayerId, (ConnectionId, Queue[F, WebSocketFrame])]]
) extends WebsocketService[F] {

  override def disconnect(playerId: PlayerId, reason: String, connectionId: Option[ConnectionId] = None): F[Unit] = {
    for {
      playerConnection <-
        connections.modify { currentState =>
          val connectionToClose =
            currentState.get(playerId).filter { case (connId, _) => connectionId.forall(_ == connId) }
          connectionToClose match {
            case Some((_, queue)) => (currentState.removed(playerId), Some(queue))
            case None             => (currentState, None)
          }
        }
      _ <- playerConnection match {
        case Some(queue) =>
          for {
            _ <- WebSocketFrame.Close(1000, reason).traverseVoid(frame => queue.offer(frame))
            _ <- Logger[F].info(s"Player with id: ${playerId.value} disconnected")
          } yield ()
        case None => Async[F].unit
      }
    } yield ()
  }

  override def send[A: Encoder](playerId: PlayerId, message: A): F[Unit] = for {
    map <- connections.get
    _ <- map.get(playerId) match {
      case Some((_, value)) =>
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
      uuid <- IdGenerator.generateUUID
      connectionId = ConnectionId(uuid)
      response <- webSocketBuilder2
        .withOnClose(disconnect(playerId, "connection closed abruptly", connectionId.some))
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
      _    <- disconnect(playerId, "establishing new connection")
      _ <- connections.modify(currentConnections =>
        (currentConnections.updated(playerId, (connectionId, queue)), ())
      )
      _ <- Logger[F].info(s"Player with id: ${playerId.value} connected")
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
      connections <- Resource.make(Ref[F].of(Map[PlayerId, (ConnectionId, Queue[F, WebSocketFrame])]().empty)) {
        stateRef =>
          for {
            map <- stateRef.get
            _ <- map.values.toVector.traverseVoid { case (_, queue) =>
              WebSocketFrame.Close(1000, "server shut down").traverseVoid(frame => queue.offer(frame))
            }
          } yield ()
      }
    } yield new WebsocketServiceImpl[F](connections)
}
