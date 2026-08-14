package com.evolution.util

import scala.annotation.tailrec
import scala.util.Random

object IdGenerator {
  private val idGenerator: Random = Random

  @tailrec
  def generateId(): Long = {
    val id = idGenerator.nextLong()
    if (id < 0) generateId()
    else id
  }
}
