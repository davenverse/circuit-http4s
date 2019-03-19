package io.chrisdavenport.circuit.http4s.client

import io.chrisdavenport.circuit.CircuitBreaker
import cats.effect._
import org.http4s._
import org.http4s.client._

object CircuitedClient {

  def apply[F[_]: Bracket[?[_], Throwable]](cr: CircuitBreaker[F])(c: Client[F]): Client[F] = 
    Client{req: Request[F] => Resource(cr.protect(c.run(req).allocated))}

}