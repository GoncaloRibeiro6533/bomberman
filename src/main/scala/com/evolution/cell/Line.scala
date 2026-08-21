package com.evolution.cell

import io.circe.generic.JsonCodec

/** Represents a line in a grid
  *
  * @param value
  *   A positive number that indicates the index of the line
  */
@JsonCodec
final case class Line(value: PositiveNumber)
