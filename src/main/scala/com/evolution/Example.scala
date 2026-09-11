//package com.evolution
//
//import cats.effect.*
//import cats.effect.implicits.effectResourceOps
//import cats.effect.std.Random
//import cats.syntax.all.*
//
//import java.util.UUID
//import scala.concurrent.duration.DurationInt
//
//object Main extends IOApp {
//
//  override def run(args: List[String]): IO[ExitCode] =
//    for {
//      _ <- GameService.make.use { service =>
//        for {
//          gameId1 <- service.startGame
//          gameId2 <- service.startGame
//
//          _ <- service.handleCommand(gameId1, 1)
//          _ <- service.handleCommand(gameId2, 2)
//
//          _ <- IO.sleep(15.seconds)
//        } yield ()
//      }
//    } yield ExitCode.Success
//}
//
//trait GameLoop {
//  def handleCommand(command: Int): IO[Unit]
//}
//
//private object GameLoop {
//
//  def make(gameId: UUID): Resource[IO, GameLoop] = {
//    for {
//      random <- Random.scalaUtilRandom[IO].toResource
//      loop = new GameLoop {
//        override def handleCommand(command: Int): IO[Unit] = IO.println(s"Game $gameId handled command $command")
//      }
//      sendRandomCommand =
//        for {
//          command <- random.nextInt
//          _       <- IO.println(s"Game $gameId sending random command $command to self")
//          _       <- loop.handleCommand(command)
//        } yield ()
//      _ <- (sendRandomCommand *> IO.sleep(10.seconds)).foreverM.background
//    } yield loop
//  }
//}
//
//trait GameService {
//  def startGame: IO[UUID]
//
//  def handleCommand(gameId: UUID, command: Int): IO[Either[String, Unit]]
//}
//
//object GameService {
//
//  def make: Resource[IO, GameService] =
//    for {
//      // store loop as well as the "release" together
//      stateRef: Ref[IO, Map[UUID, (GameLoop, IO[Unit])]] <-
//        Resource.make(
//          Ref.of[IO, Map[UUID, (GameLoop, IO[Unit])]](Map.empty)
//        ) { stateRef =>
//          for {
//            _                                      <- IO.println("Releasing games")
//            state: Map[UUID, (GameLoop, IO[Unit])] <- stateRef.get
//            _ <- state.toVector.traverse { case (gameId, (_, release)) =>
//              for {
//                _ <- IO.println(s"Stopping game $gameId")
//                _ <- release
//              } yield ()
//            }
//          } yield ()
//        }
//    } yield new GameService {
//
//      override def startGame: IO[UUID] =
//        for {
//          gameId                               <- IO.randomUUID
//          loopAndRelease: (GameLoop, IO[Unit]) <- GameLoop.make(gameId).allocated
//          _                                    <- stateRef.update(_.updated(gameId, loopAndRelease))
//        } yield gameId
//
//      override def handleCommand(gameId: UUID, command: Int): IO[Either[String, Unit]] =
//        for {
//          state <- stateRef.get
//          result <- state.get(gameId) match {
//            case Some((loop, _)) => loop.handleCommand(command) as ().asRight
//            case None            => s"Game $gameId not found".asLeft.pure[IO]
//          }
//        } yield result
//    }
//}
