package com.evolution.cell

import io.circe.generic.JsonCodec

/** Represents a column in a grid
  *
  * @param value
  *   A positive number that indicates the index of the column
  */
@JsonCodec
final case class Column(value: PositiveNumber)
