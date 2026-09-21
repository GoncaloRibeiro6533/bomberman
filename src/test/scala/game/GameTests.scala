package game

import com.evolution.cell.PositiveNumber
import com.evolution.direction.Direction.*
import com.evolution.game.Game.{GameFinished, GameRunning, GameWaiting}
import com.evolution.game.GameId
import com.evolution.player.Player.IdlePlayer
import com.evolution.player.{PlayerId, Username}
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.must.Matchers.be
import org.scalatest.matchers.should.Matchers.convertToAnyShouldWrapper
import org.scalatest.{EitherValues, OptionValues}
import org.scalatestplus.scalacheck.ScalaCheckDrivenPropertyChecks

import java.time.Instant
import java.util.UUID

class GameTests extends AnyFlatSpec with ScalaCheckDrivenPropertyChecks with OptionValues with EitherValues {

  "GameWaiting" should "should add user if its not already added" in {
    val game = GameWaiting(
      id = GameId(UUID.randomUUID()),
      players = Set.empty,
      nPlayers = PositiveNumber.Two
    )
    val player   = IdlePlayer(PlayerId(UUID.randomUUID()), Username("John").value)
    val now      = Instant.parse("2026-09-21T10:00:00Z")
    val sut      = game.join(player, now)
    val expected = game.copy(players = game.players + player.toJoiningPlayer)
    sut.isRight shouldBe true
    sut.value shouldBe expected
  }

  "GameWaiting" should "should start game if is full" in {
    val game = GameWaiting(
      id = GameId(UUID.randomUUID()),
      players = Set.empty,
      nPlayers = PositiveNumber.One
    )
    val playerToAdd = IdlePlayer(PlayerId(UUID.randomUUID()), Username("John").value)
    val now         = Instant.parse("2026-09-21T10:00:00Z")
    val sut         = game.join(playerToAdd, now)
    sut.isRight shouldBe true
    sut.value shouldBe (_: GameRunning)
  }

  "GameWaiting" should "should return game if user was already added" in {
    val player = IdlePlayer(PlayerId(UUID.randomUUID()), Username("John").value)
    val game = GameWaiting(
      id = GameId(UUID.randomUUID()),
      players = Set(player.toJoiningPlayer),
      nPlayers = PositiveNumber.Two
    )
    val now = Instant.parse("2026-09-21T10:00:00Z")
    val sut = game.join(player, now)
    sut.isRight shouldBe true
  }

  "GameWaiting" should "should return error if game is full when join is called" in {
    val playerAdded = IdlePlayer(PlayerId(UUID.randomUUID()), Username("John").value)
    val game = GameWaiting(
      id = GameId(UUID.randomUUID()),
      players = Set(playerAdded.toJoiningPlayer),
      nPlayers = PositiveNumber.One
    )
    val playerToAdd = IdlePlayer(PlayerId(UUID.randomUUID()), Username("John1").value)
    val now         = Instant.parse("2026-09-21T10:00:00Z")
    val sut         = game.join(playerToAdd, now)
    sut.isLeft shouldBe true
  }

  "GameRunning" should "should change player position in valid movement" in {
    val gameRunning      = createGameRunning()
    val player           = gameRunning.activePlayers.head
    val sut              = gameRunning.processMovement(player.id, Down)
    val expectedPosition = player.cell + Down
    sut.isRight shouldBe true
    sut.value.activePlayers.head.cell == expectedPosition.value
  }

  "GameRunning" should "should plant bomb in player position if player has bombs left and decrement bombs of user" in {
    val gameRunning = createGameRunning()
    val player      = gameRunning.activePlayers.head
    player.bombs.value > 0 shouldBe true
    val now              = Instant.parse("2026-09-21T10:01:00Z")
    val sut              = gameRunning.processBombPlanting(player.id, now)
    val expectedPosition = player.cell
    sut.isRight shouldBe true
    sut.value.bombs.filter(_.plantedBy == player.id).head.cell shouldBe  expectedPosition.value
    sut.value.activePlayers.filter(_.id == player.id).head.bombs.value == player.bombs.value - 1 shouldBe true
  }

  "GameRunning" should "bomb should detonate after 3 seconds and kill players in radius" in {
    val gameRunning = createGameRunning()
    val player      = gameRunning.activePlayers.head
    player.bombs.value should be > 0
    val plantedAt = Instant.parse("2026-09-21T10:01:00Z")
    val afterPlanting = gameRunning
        .processBombPlanting(player.id, plantedAt)
        .value
    val sut = afterPlanting.triggerBombs(
        Instant.parse("2026-09-21T10:01:04Z")
      )
    sut.value match {
      case game: GameFinished =>
        game.deadPlayers.exists(_.id == player.id) shouldBe true
      case error => fail(s"should received GameFinished but received $error")
    }
  }

  "GameRunning" should "plant bomb return error if player has no bombs left" in {
    val gameRunning = createGameRunning()
    val player      = gameRunning.activePlayers.head
    player.bombs.value should be > 0
    val plantedAt = Instant.parse("2026-09-21T10:01:00Z")
    val afterPlanting =
      gameRunning
        .processBombPlanting(player.id, plantedAt)
        .value
    val sut = afterPlanting.processBombPlanting(
        player.id,
        Instant.parse("2026-09-21T10:01:04Z")
      )
    sut.isLeft shouldBe true
  }

  "GameRunning" should "plant bomb return error if player is dead" in {
    val gameRunning = createGameRunning()
    val player      = gameRunning.activePlayers.head
    player.bombs.value should be > 0
    val afterKillingPlayer =
      gameRunning.copy(activePlayers = Set.empty, deadPlayers = Set(player.toDeadPlayer))
    val sut = afterKillingPlayer.processBombPlanting(
      player.id,
      Instant.parse("2026-09-21T10:01:04Z")
    )
    sut.isLeft shouldBe true
  }

  "GameRunning" should "process movement return error if player is dead" in {
    val gameRunning = createGameRunning()
    val player      = gameRunning.activePlayers.head
    val afterKillingPlayer =
      gameRunning.copy(activePlayers = Set.empty, deadPlayers = Set(player.toDeadPlayer))
    val sut = afterKillingPlayer.processMovement(
      player.id,
      Down
    )
    sut.isLeft shouldBe true
  }

  "GameRunning" should "finish after time elapsed" in {
    val start       = Instant.parse("2026-09-21T10:00:00Z")
    val gameRunning = createGameRunning(start)
    val sut = {
      gameRunning.triggerBombs(
        start.plus(gameRunning.duration)
      )
    }
    sut.value shouldBe (_: GameFinished)
  }

  "GameFinished" should "have the correct winner" in {
    val start       = Instant.parse("2026-09-21T10:00:00Z")
    val gameRunning = createGameRunningWithOpponent(start)
    val opponent    = gameRunning.activePlayers.head
    val afterKillingPlayer = gameRunning.copy(
      activePlayers = gameRunning.activePlayers.filterNot(_ == opponent),
      deadPlayers = Set(opponent.toDeadPlayer)
    )
    val sut = {
      afterKillingPlayer.triggerBombs(
        start.plus(gameRunning.duration.dividedBy(10))
      )
    }
    sut match {
      case game: GameFinished =>
        game.winner == game.activePlayers.head shouldBe true
      case error => fail(s"expected GameFinished got $error")
    }

  }

  private def createGameRunning(now: Instant = Instant.parse("2026-09-21T10:00:00Z")): GameRunning = {
    val playerToAdd = IdlePlayer(PlayerId(UUID.randomUUID()), Username("John1").value)
    val game = GameWaiting(
      id = GameId(UUID.randomUUID()),
      players = Set.empty,
      nPlayers = PositiveNumber.One
    )
    game.join(playerToAdd, now) match {
      case scala.Right(value: GameRunning) => value
      case error                           => fail(s"expected GameRunning got $error")
    }
  }

  private def createGameRunningWithOpponent(now: Instant): GameRunning = {
    val playerToAdd = IdlePlayer(PlayerId(UUID.randomUUID()), Username("John1").value)
    val opponent    = IdlePlayer(PlayerId(UUID.randomUUID()), Username("Alice").value)
    val game = GameWaiting(
      id = GameId(UUID.randomUUID()),
      players = Set(opponent.toJoiningPlayer),
      nPlayers = PositiveNumber.Two
    )
    game.join(playerToAdd, now) match {
      case scala.Right(value: GameRunning) => value
      case error                           => fail(s"expected GameRunning got $error")
    }
  }
}
