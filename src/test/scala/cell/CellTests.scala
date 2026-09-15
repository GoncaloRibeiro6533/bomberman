package cell

import com.evolution.cell.{Cell, Column, Line, PositiveNumber}
import com.evolution.direction.Direction.*
import org.scalatest.OptionValues
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.should.Matchers

class CellTests extends AnyFreeSpec with Matchers with OptionValues {

  "cell creation should succeed with positive coordinates" in {
    val positiveNumber = PositiveNumber(1)
    val cell           = Cell(Column(positiveNumber.value), Line(positiveNumber.value))
    assert(cell.col.value.value == positiveNumber.value.value)
    assert(cell.line.value.value == positiveNumber.value.value)
  }

  "cell plus direction" - {
    "cell plus Up should return an cell with the same Column and the previous line " in {
      val positiveNumber    = PositiveNumber(1)
      val cell              = Cell(Column(positiveNumber.value), Line(positiveNumber.value))
      val sut: Option[Cell] = cell + Up
      sut.value.line.value.value should be(0)
      sut.value.col.value.value should be(1)
    }

    "cell plus Down should return an cell with the same Column and the next line " in {
      val positiveNumber    = PositiveNumber(1)
      val cell              = Cell(Column(positiveNumber.value), Line(positiveNumber.value))
      val sut: Option[Cell] = cell + Down
      sut.value.line.value.value should be(2)
      sut.value.col.value.value should be(1)
    }

    "cell plus Right should return an cell with the next Column and the same line " in {
      val positiveNumber    = PositiveNumber(1)
      val cell              = Cell(Column(positiveNumber.value), Line(positiveNumber.value))
      val sut: Option[Cell] = cell + Right
      sut.value.line.value.value should be(1)
      sut.value.col.value.value should be(2)
    }

    "cell plus Left should return an cell with the previous Column and the next line " in {
      val positiveNumber    = PositiveNumber(1)
      val cell              = Cell(Column(positiveNumber.value), Line(positiveNumber.value))
      val sut: Option[Cell] = cell + Left
      sut.value.line.value.value should be(1)
      sut.value.col.value.value should be(0)
    }

    "should not be possible to get a cell with negative coordinates" in {
      val positiveNumber    = PositiveNumber(0)
      val cell              = Cell(Column(positiveNumber.value), Line(positiveNumber.value))
      val sut: Option[Cell] = cell + Up
      assert(sut.isEmpty)
    }
  }

}
