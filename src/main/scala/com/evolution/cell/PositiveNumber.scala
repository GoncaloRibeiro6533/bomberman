package com.evolution.cell

class PositiveNumber private (val value: Int) extends AnyVal

object PositiveNumber {
  val One: PositiveNumber = new PositiveNumber(1)

  def apply(value: Int): Option[PositiveNumber] = if (value >= 0) Some(new PositiveNumber(value))
  else None

  trait PositiveNumberOp[T] {
    def toPositiveNumber(value: T): Option[PositiveNumber]
  }

  implicit val positiveNumberOpInt: PositiveNumberOp[Int] = (value: Int) =>
    if (value < 0) None
    else
      Some(new PositiveNumber(value))
}
