package com.evolution.domain.cell

import com.evolution.domain.PositiveNumber

/**
 * Represents a column in a grid
 *
 * @param value A positive number that indicates the index of the column
 */
final case class Column private(value: PositiveNumber)
