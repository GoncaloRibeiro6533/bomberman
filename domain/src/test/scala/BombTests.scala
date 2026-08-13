import com.evolution.domain.PositiveNumber
import com.evolution.domain.bomb.{Bomb, BombId}
import com.evolution.domain.cell.{Cell, Column, Line}
import org.scalatest.OptionValues
import org.scalatest.freespec.AnyFreeSpec

import java.time.{Duration, Instant}

class BombTests  extends AnyFreeSpec with OptionValues {

  "bomb" - {
    "bomb should be expired" in {
      val bombId = BombId(1).value
      val positiveNumber = PositiveNumber(1).value
      val cell = Cell(Column(positiveNumber), Line(positiveNumber))
      val past = Instant.parse("2026-08-13T14:00:00Z")
      val bomb = Bomb(bombId,  cell, past)
      val sut = bomb.isExpired(Instant.parse("2026-08-13T14:05:00Z"), Duration.ofSeconds(30))
      assert(sut)
    }
  }

}
