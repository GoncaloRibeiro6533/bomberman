package com.evolution.game

import com.evolution.cell.{Column, Line}
import com.evolution.cell.*
import com.evolution.cell.CellType.*
import com.evolution.player.Player.*

final case class Maze(width: Int, height: Int, cells: List[Position]) {

  def insertPlayers(players: List[JoiningPlayer]): List[ActivePlayer] = {
    val startCells = this.cells.filter(_.cellType == PlayerPosition)
    players.zip(startCells).map { case (player, position) =>
      player.toActivePlayer(position.cell)
    }
  }

  def toPrintable: List[String] =
    cells
      .groupBy(pos => pos.cell.line)
      .toList
      .sortBy { case (line, _) => line.value.value }
      .map { case (_, positions) =>
        val sorted = positions.sortBy(_.cell.col.value.value)
        (0 until width)
          .map(idx =>
            getCell(sorted, idx) match {
              case Some(value) => value.toChar
              case None        => ' '
            }
          )
          .mkString
      }

  private def getCell(positions: List[Position], idx: Int): Option[Position] =
    positions
      .filterNot(position =>
        position.cellType == BombPlacement &&
          positions.exists(innerPosition =>
            position.cellType == PlayerPosition &&
              position.cell == innerPosition.cell
          )
      )
      .find(_.cell.col.value.value == idx)

  def walls: List[Cell]           = cells.filter(_.cellType == Wall).map(_.cell)
  def bombs: List[Cell]           = cells.filter(_.cellType == BombPlacement).map(_.cell)
  def blocks: List[Cell]          = cells.filter(_.cellType == DestructibleBlock).map(_.cell)
  def playerPositions: List[Cell] = cells.filter(_.cellType == PlayerPosition).map(_.cell)

}

object Maze {

  /** Maze map textually described List of string were each string is a map line.
    *
    * '#' -> Wall; '%' -> Destructible block;'*' -> Bomb; 'P' -> PlayerPosition;
    */

  private val map: List[String] = List(
    "#############",
    "#P % % % %  #",
    "# ## # # ## #",
    "#% % % % % %#",
    "# # ##### # #",
    "#% %  %  % %#",
    "# ## # # ## #",
    "#% % % % % %#",
    "# # ##### # #",
    "#  % % % % P#",
    "#############"
  )

  private def loadMap(map: List[String]) = {
    val cells: List[Position] = for {
      (line, lineIdx)  <- map.zipWithIndex
      (letter, colIdx) <- line.zipWithIndex
      colNum           <- PositiveNumber(colIdx)
      lineNum          <- PositiveNumber(lineIdx)
      cellType         <- CellType.fromChar(letter)
    } yield Position(
      Cell(Column(colNum), Line(lineNum)),
      cellType
    )
    cells
  }

  def apply(map: List[String]): Maze = {
    val cells: List[Position] = loadMap(map)
    Maze(map.map(_.length).max, map.size, cells)
  }

  def apply(): Maze = {
    val cells = loadMap(map)
    Maze(map.map(_.length).max, map.size, cells)
  }

}
