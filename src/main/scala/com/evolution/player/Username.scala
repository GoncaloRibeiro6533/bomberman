package com.evolution.player

final class Username private (val value: String) extends AnyVal

object Username {
  def apply(username: String): Option[Username] = if (username.length > 3) Some(new Username(username))
  else None
}
