package com.evolution.domain.bomb

import com.evolution.domain.PositiveNumber
import com.evolution.domain.cell.Cell

import java.time.{Duration, Instant}
import java.time.temporal.TemporalAmount

/**
 * Represents a bomb in the game
 * @param id Bomb identifier
 * @param cell Cell where the bomb is placed
 * @param plantedAt instant that server created bomb
 */
final case class Bomb(id: BombId, cell: Cell, plantedAt: Instant, radius: PositiveNumber = PositiveNumber.Two) {

   def isExpired(now: Instant, detonateTime: Duration= Duration.ofSeconds(3)): Boolean = {
      !plantedAt.plus(detonateTime).isAfter(now)
  }
}
