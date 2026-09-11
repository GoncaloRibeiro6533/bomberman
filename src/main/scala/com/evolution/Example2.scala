//package com.evolution
//
//import cats.effect.*
//import cats.effect.implicits.implicitseffectResourceOps
//import cats.effect.std.Random
//import cats.syntax.all.*
//
//import java.util.UUID
//import scala.concurrent.duration.DurationInt
//
//object Main1 extends IOApp {
//
//  override def run(args: List[String]): IO[ExitCode] =
//    GameService.make
//      .use { service =>
//        for {
//          _ <- service.startGame
//          _ <- service.startGame
//          _ <- IO.sleep(10.seconds)
//        } yield ()
//      }
//      .as(ExitCode.Success)
//
//  class GameService(
//      stateRef: Ref[IO, Map[UUID, IO[Unit]]],
//      random: Random[IO]
//  ) {
//
//    def startGame: IO[UUID] =
//      for {
//        gameId       <- IO.randomUUID
//        secretNumber <- random.nextIntBounded(10)
//        release <- GuessingGame
//          .make(
//            gameId = gameId,
//            secretNumber = secretNumber,
//            random = random,
//            onGameEnded = stopGame(gameId)
//          )
//          .allocated
//          .map { case (_, release) => release }
//        _ <- stateRef.update(_.updated(gameId, release))
//      } yield gameId
//
//    private def stopGame(gameId: UUID): IO[Unit] =
//      for {
//        _ <- IO.println(s"Stopping game $gameId")
//        release <- stateRef.modify { state =>
//          (state.removed(gameId), state.get(gameId))
//        }
//        _ <- release.sequence_
//      } yield ()
//  }
//
//  object GameService {
//
//    def make: Resource[IO, GameService] =
//      for {
//        stateRef <- Resource.make(Ref.of[IO, Map[UUID, IO[Unit]]](Map.empty)) { stateRef =>
//          stateRef.get.flatMap { games =>
//            games.toVector.traverse_ { case (gameId, release) =>
//              IO.println(s"Releasing game $gameId") *> release
//            }
//          }
//        }
//        random <- Random.scalaUtilRandom[IO].toResource
//      } yield new GameService(random = random, stateRef = stateRef)
//  }
//
//  object GuessingGame {
//
//    def make(
//        gameId: UUID,
//        secretNumber: Int,
//        random: Random[IO],
//        onGameEnded: IO[Unit]
//    ): Resource[IO, Unit] = {
//
//      def guess: IO[Unit] =
//        for {
//          nextGuess <- random.nextIntBounded(10)
//          _         <- IO.println(s"Game $gameId guessed $nextGuess")
//          _ <-
//            if (nextGuess == secretNumber) onGameEnded
//            else IO.sleep(1.second) >> guess
//        } yield ()
//
//      guess.background.void
//    }
//  }
//}
