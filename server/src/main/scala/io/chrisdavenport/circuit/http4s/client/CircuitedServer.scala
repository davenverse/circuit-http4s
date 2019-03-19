package io.chrisdavenport.circuit.http4s.client

import io.chrisdavenport.circuit.CircuitBreaker
import cats.data._

object CircuitedServer {
  def apply[F[_], A, B](circuit: CircuitBreaker[F])(http: Kleisli[F, A, B]): Kleisli[F, A, B] = 
    Kleisli[F, A, B](a => circuit.protect(http.run(a)))

  def httpApp[F[_], A, B](circuit: CircuitBreaker[F])(h: Kleisli[F, A, B]): Kleisli[F, A, B] =
    apply(circuit)(h)

  def httpRoutes[F[_], A, B](
    circuit: CircuitBreaker[F]
  )(h: Kleisli[OptionT[F, ?], A, B]): Kleisli[OptionT[F, ?], A, B] =
    Kleisli[OptionT[F, ?], A, B](a => OptionT(circuit.protect(h.run(a).value)))
}