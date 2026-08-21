package com.evolution.util

import cats.effect.kernel.Sync

import java.util.UUID

object IdGenerator {

  def generateUUID[F[_]: Sync]: F[UUID] = Sync[F].delay(UUID.randomUUID())
}
