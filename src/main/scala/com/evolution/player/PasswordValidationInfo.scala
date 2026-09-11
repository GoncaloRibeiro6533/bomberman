package com.evolution.player

final class PasswordValidationInfo private (val value: String)

object PasswordValidationInfo {
  def apply(value: String): Option[PasswordValidationInfo] = if (value.length < 12) None
  else
    Some(new PasswordValidationInfo(value))
}
