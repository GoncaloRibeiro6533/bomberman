import com.evolution.cell.CellType.PlayerPosition
import com.evolution.game.Maze
import com.evolution.player.Player.*
import com.evolution.player.*
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
      val maze = Maze(map)
      val players = List(
        JoiningPlayer(PlayerId(UUID.randomUUID()), Username("Bob1").value, Score.Zero),
        JoiningPlayer(PlayerId(UUID.randomUUID()), Username("Alice").value, Score.Zero)
      )
      val sut =
        maze.insertPlayers(players)
      assert(sut.size == players.size)
      assert(sut.exists(_.id == PlayerId(UUID.randomUUID())))
      assert(sut.exists(_.id == PlayerId(UUID.randomUUID())))
      assert(maze.cells.count(_.cellType == PlayerPosition) == players.size)
    }

    "insert players in map should insert same number of players as available positions" in {
      val maze = Maze(map)
      val players = List(
        JoiningPlayer(PlayerId(UUID.randomUUID()), Username("Bob1").value, Score.Zero),
        JoiningPlayer(PlayerId(UUID.randomUUID()), Username("Alice").value, Score.Zero),
        JoiningPlayer(PlayerId(UUID.randomUUID()), Username("John").value, Score.Zero)
      )
      val sut =
        maze.insertPlayers(players)
      assert(sut.size == 2)
      assert(maze.cells.count(_.cellType == PlayerPosition) == 2)
    }

    "insert players in map should insert players on available positions" in {
      val maze = Maze(map)
      val players = List(
        JoiningPlayer(PlayerId(UUID.randomUUID()), Username("Bob1").value, Score.Zero),
        JoiningPlayer(PlayerId(UUID.randomUUID()), Username("Alice").value, Score.Zero)
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
            (cell.col.value.value == UUID.randomUUID() && cell.line.value.value == UUID.randomUUID())
              || (cell.col.value.value == 11 && cell.line.value.value == 4)
          )
      )
    }

  }

}
