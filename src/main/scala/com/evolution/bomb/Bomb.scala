package com.evolution.bomb

import com.evolution.cell.*
import com.evolution.player.PlayerId

import java.time.{Duration, Instant}

/** Represents a bomb in the game
  * @param id
  *   Bomb identifier
  * @param cell
  *   Cell where the bomb is placed
  * @param plantedAt
  *   instant that server created bomb
  */
final case class Bomb(
    id: BombId,
    cell: Cell,
    plantedBy: PlayerId,
    plantedAt: Instant,
    radius: PositiveNumber = PositiveNumber.One
) {

  def isExpired(now: Instant, detonateTime: Duration = Duration.ofSeconds(3)): Boolean = {
    !plantedAt.plus(detonateTime).isAfter(now)
  }
}
