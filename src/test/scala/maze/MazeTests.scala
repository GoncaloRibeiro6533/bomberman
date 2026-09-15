package maze

import com.evolution.cell.CellType.PlayerPosition
import com.evolution.game.Maze
import com.evolution.player.*
import com.evolution.player.Player.*
import org.scalatest.OptionValues
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.must.Matchers.be
import org.scalatest.matchers.should.Matchers.convertToAnyShouldWrapper

import java.util.UUID

class MazeTests extends AnyFreeSpec with OptionValues {

  private val map = List(
    "#############",
    "#P % % % %  #",
    "# ## # # ## #",
    "# # ##### # #",
    "#  % % % % P#",
    "#############"
  )

  "maze" - {
    "printable map should equal original map" in {
      Maze(map).toPrintable should be(map)
    }

    "insert players in map should insert all players" in {
      val maze        = Maze(map)
      val uuidPlayer1 = UUID.randomUUID()
      val uuidPlayer2 = UUID.randomUUID()
      val players = Set(
        JoiningPlayer(PlayerId(uuidPlayer1), Username("Bob1").value),
        JoiningPlayer(PlayerId(uuidPlayer2), Username("Alice").value)
      )
      val sut =
        maze.insertPlayers(players)
      assert(sut.size == players.size)
      assert(sut.exists(_.id == PlayerId(uuidPlayer1)))
      assert(sut.exists(_.id == PlayerId(uuidPlayer2)))
      assert(maze.cells.count(_.cellType == PlayerPosition) == players.size)
    }

    "insert players in map should insert same number of players as available positions" in {
      val maze = Maze(map)
      val players = Set(
        JoiningPlayer(PlayerId(UUID.randomUUID()), Username("Bob1").value),
        JoiningPlayer(PlayerId(UUID.randomUUID()), Username("Alice").value),
        JoiningPlayer(PlayerId(UUID.randomUUID()), Username("John").value)
      )
      val sut =
        maze.insertPlayers(players)
      assert(sut.size == 2)
      assert(maze.cells.count(_.cellType == PlayerPosition) == 2)
    }

    "insert players in map should insert players on available positions" in {
      val maze = Maze(map)
      val players = Set(
        JoiningPlayer(PlayerId(UUID.randomUUID()), Username("Bob1").value),
        JoiningPlayer(PlayerId(UUID.randomUUID()), Username("Alice").value)
      )
      val sut =
        maze.insertPlayers(players)
      assert(sut.size == players.size)
      assert(maze.cells.count(_.cellType == PlayerPosition) == players.size)
      assert(
        maze.cells
          .filter(_.cellType == PlayerPosition)
          .map(_.cell)
          .exists(cell =>
            (cell.col.value.value == 1 && cell.line.value.value == 1)
              || (cell.col.value.value == 11 && cell.line.value.value == 4)
          )
      )
    }

  }

}
