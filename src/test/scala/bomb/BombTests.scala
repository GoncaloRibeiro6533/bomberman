package bomb

import com.evolution.bomb.{Bomb, BombId}
import com.evolution.cell.{Cell, Column, Line, PositiveNumber}
import com.evolution.player.PlayerId
import org.scalatest.OptionValues
import org.scalatest.freespec.AnyFreeSpec

import java.time.{Duration, Instant}
import java.util.UUID

class BombTests extends AnyFreeSpec with OptionValues {

  "bomb" - {
    "bomb should be expired" in {
      val uuid           = UUID.randomUUID()
      val bombId         = BombId(uuid)
      val positiveNumber = PositiveNumber(1).value
      val cell           = Cell(Column(positiveNumber), Line(positiveNumber))
      val past           = Instant.parse("2026-08-13T14:00:00Z")
      val bomb           = Bomb(bombId, cell, PlayerId(uuid), past)
      val sut            = bomb.isExpired(Instant.parse("2026-08-13T14:05:00Z"), Duration.ofSeconds(30))
      assert(sut)
    }
  }

}
