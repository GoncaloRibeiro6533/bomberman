package com.evolution.util

import cats.effect.kernel.Async
import cats.syntax.all.*
import fs2.{Chunk, Pipe}
import fs2.hashing.{Hash, HashAlgorithm, Hashing}

object HashUtils {

  def hash[F[_]: Async](value: String): F[Option[String]] = {
    val pipe: Pipe[F, Byte, Hash]       = Hashing.forSync[F].hash(HashAlgorithm.SHA256)
    val stream: fs2.Stream[F, Byte]     = fs2.Stream.chunk(Chunk.array(value.getBytes))
    val hashedPassword: F[Option[Hash]] = stream.through(pipe).compile.last
    val res: F[Option[String]] = hashedPassword.map {
      case Some(value) => Some(value.toString())
      case None        => None
    }
    res
  }
}
