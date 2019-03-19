package io.chrisdavenport.circuit.http4s.client

import io.chrisdavenport.circuit.CircuitBreaker
import cats.data._
import org.http4s._

object CircuitedServer {
  def apply[F[_], A, B](circuit: CircuitBreaker[F])(http: Kleisli[F, A, B]): Kleisli[F, A, B] = 
    Kleisli[F, A, B](a => circuit.protect(http.run(a)))

  def httpApp[F[_]](circuit: CircuitBreaker[F])(h: HttpApp[F]): HttpApp[F] =
    apply(circuit)(h)

  def httpRoutes[F[_]](circuit: CircuitBreaker[F])(h: HttpRoutes[F]): HttpRoutes[F] =
    Kleisli[OptionT[F, ?], Request[F], Response[F]](a => OptionT(circuit.protect(h.run(a).value)))
}