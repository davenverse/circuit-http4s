package io.chrisdavenport.circuit.http4s.client

import io.chrisdavenport.mapref.MapRef
import io.chrisdavenport.circuit.CircuitBreaker
import cats.syntax.all._
import cats.effect._
import org.http4s._
import org.http4s.client._
import cats._
import scala.concurrent.duration._

object CircuitedClient {

  def apply[F[_]: Concurrent](cr: CircuitBreaker[Resource[F, *]])(c: Client[F]): Client[F] = 
    generic(Function.const[CircuitBreaker[Resource[F, *]], Request[F]](cr))(c)

  def byKey[F[_]](
    maxFailures: Int,
    resetTimeout: FiniteDuration,
    exponentialBackoffFactor: Double = 1,
    maxResetTimeout: Duration = Duration.Inf,
    modifications: CircuitBreaker[Resource[F, *]] => CircuitBreaker[Resource[F, *]] = {(x: CircuitBreaker[Resource[F, *]]) => x},
    shouldFail: (Request[F], Response[F]) => ShouldCircuitBreakerSeeAsFailure = defaultShouldFail[F](_, _)
  )(client: Client[F])(implicit F: Async[F]): F[Client[F]] = {
    MapRef.inSingleImmutableMap[F, Resource[F, *], RequestKey, CircuitBreaker.State](Map.empty[RequestKey, CircuitBreaker.State]).map{
      mapref => 
        val f : Request[F] => CircuitBreaker[Resource[F, *]] = {(req: Request[F]) => 
          val key = RequestKey.fromRequest(req)
          val optRef = mapref(key)
          val ref = MapRef.defaultedRef(optRef, CircuitBreaker.Closed(0))
          val cbInit = CircuitBreaker.unsafe(ref, maxFailures, resetTimeout, exponentialBackoffFactor, maxResetTimeout, Resource.eval(F.unit), Resource.eval(F.unit), Resource.eval(F.unit), Resource.eval(F.unit))
          modifications(cbInit)
        }
        generic(f, shouldFail)(client)
    }
  }

  def generic[F[_], A](
    cbf: Request[F] => CircuitBreaker[Resource[F, *]],
    shouldFail: (Request[F], Response[F]) => ShouldCircuitBreakerSeeAsFailure = defaultShouldFail[F](_, _)
  )(client: Client[F])(implicit F: Concurrent[F]): Client[F] = {
    Client[F]{ (req: Request[F]) => 
      val circuit = cbf(req)
      val action = client.run(req).flatMap(resp => 
        shouldFail(req, resp) match {
          case CountAsFailure => Resource.eval(Concurrent[F].raiseError(CircuitedClientResourceThrowable(resp)))
          case CountAsSuccess => Resource.pure[F, Response[F]](resp)
        }  
      )
      
      circuit.protect(action).handleErrorWith[Response[F], Throwable]{
        case e: CircuitedClientResourceThrowable[F] @unchecked => Resource.pure[F, Response[F]](e.resp)
        case e => Resource.eval(F.raiseError(e))
      }
    }
  }

  sealed trait ShouldCircuitBreakerSeeAsFailure
  case object CountAsFailure extends ShouldCircuitBreakerSeeAsFailure
  case object CountAsSuccess extends ShouldCircuitBreakerSeeAsFailure

  def defaultShouldFail[F[_]](req: Request[F], resp: Response[F]): ShouldCircuitBreakerSeeAsFailure = {
    val _ = req
    if (resp.status.responseClass == Status.ServerError) CountAsFailure
    else CountAsSuccess
  }

  private case class CircuitedClientThrowable[F[_]](resp: Response[F], shutdown: F[Unit]) 
    extends Throwable 
    with scala.util.control.NoStackTrace

  private case class CircuitedClientResourceThrowable[F[_]](resp: Response[F]) 
    extends Throwable 
    with scala.util.control.NoStackTrace
  
  implicit private val eqState: Eq[CircuitBreaker.State] = Eq.instance{
    case (CircuitBreaker.HalfOpen, CircuitBreaker.HalfOpen) => true
    case (CircuitBreaker.Closed(i), CircuitBreaker.Closed(i2)) => i === i2
    case (CircuitBreaker.Open(started1, reset1), CircuitBreaker.Open(started2, reset2)) => 
      started1 === started2 &&
        reset1 === reset2
    case (_, _) => false
  }

}