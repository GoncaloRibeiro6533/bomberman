package com.evolution.domain

case class PositiveNumber private(value: Int) extends AnyVal

object PositiveNumber {
  val One = new PositiveNumber(1)
  val Two = new PositiveNumber(2)



  def apply(value: Int): Option[PositiveNumber] = if (value >= 0) Some(new PositiveNumber(value))
  else None

  trait PositiveNumberOp[T] {
    def toPositiveNumber(value: T): Option[PositiveNumber]
  }

  implicit val positiveNumberOpInt: PositiveNumberOp[Int] = new PositiveNumberOp[Int] {
    override def toPositiveNumber(value: Int): Option[PositiveNumber] = if (value < 0) None else
      Some(new PositiveNumber(value))
  }
}