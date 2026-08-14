package com.evolution.cell

/** Represents a cell in a grid composed of multiple cells.
  * @param col
  *   The column index of the cell.
  * @param line
  *   The line index of the cell.
  */
final case class Cell(col: Column, line: Line) {

  def toPosition(cellType: CellType): Position = {
    Position(cell = this, cellType)
  }
}
