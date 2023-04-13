package io.chrisdavenport.circuit.http4s.client

import cats.effect.std.MapRef
import io.chrisdavenport.circuit.CircuitBreaker
import cats.syntax.all._
import cats.effect._
import org.http4s._
import org.http4s.client._
import cats._
import scala.concurrent.duration._
import io.chrisdavenport.circuit.CircuitBreaker.RejectedExecution
import java.util.concurrent.ConcurrentHashMap

object CircuitedClient {

  /** Configure and Create a Client which CircuitBreaks on RequestKeys consistently failing.
    * A solid in-memory default. A safe baseline might be maxFailures = 50, and resetTimeout = 1.second but
    * is highly influenced by your load and failure tolerance.
    * 
    * @param maxFailures The number of consecutive failures required to trip the circuit breaker.
    * @param resetTimeout The period of time to open on an initial switch from Closed to Open
    * @param backoff The algorithm to determine how long to stay open after each failure. Default is exponential, doubling the period.
    * @param maxResetTimeout The maximum open period before retrying. The prevent increasing timeouts ever increasing too greatly. The default is 1 minute.
    * @param modifications The modifications to the created circuit breaker. This is useful for adding your own triggers, and metrics on circuit changes.
    * @param translatedError This function allows you to translate the Request, Circuit RejectedExecution, and RequestKey and build a Throwable of your own. Default is to build a RejectedExecutionHttp4sClient.
    * @param shouldFail This function allows you to determine what counts as a failure when a Response is succesfully retrieved. Default is to see any 5xx Response as a failure.
    **/
  def byRequestKey[F[_]](
    maxFailures: Int,
    resetTimeout: FiniteDuration,
    backoff: FiniteDuration => FiniteDuration = io.chrisdavenport.circuit.Backoff.exponential,
    maxResetTimeout: Duration = 1.minute,
    modifications: CircuitBreaker[Resource[F, *]] => CircuitBreaker[Resource[F, *]] = {(x: CircuitBreaker[Resource[F, *]]) => x},
    translatedError: (Request[F], RejectedExecution, RequestKey) => Option[Throwable] = defaultTranslatedError[F, RequestKey](_, _, _),
    shouldFail: (Request[F], Response[F]) => ShouldCircuitBreakerSeeAsFailure = defaultShouldFail[F](_, _)
  )(client: Client[F])(implicit F: Temporal[F]): F[Client[F]] = {
    MapRef.ofSingleImmutableMap[F, RequestKey, CircuitBreaker.State](Map.empty[RequestKey, CircuitBreaker.State]).map{
      mapref => 
        byMapRefAndKeyed[F, RequestKey](mapref, RequestKey.fromRequest(_), maxFailures, resetTimeout, backoff, maxResetTimeout, modifications, translatedError, shouldFail)(client)
    }
  }

  /** Configure and Create a Client which CircuitBreaks on a generic key consistently failing.
    * 
    A safe baseline might be maxFailures = 50, and resetTimeout = 1.second but
    * is highly influenced by your load and failure tolerance.
    * 
    * @param mapRef The storage mechanism for the CircuitBreaker State, can be either in memory or remote.
    * @param keyFunction The classification mechanism sorting requests into unqiquely keyed circuit breakers.
    * @param maxFailures The number of consecutive failures required to trip the circuit breaker.
    * @param resetTimeout The period of time to open on an initial switch from Closed to Open
    * @param backoff The algorithm to determine how long to stay open after each failure. Default is exponential, doubling the period.
    * @param maxResetTimeout The maximum open period before retrying. The prevent increasing timeouts ever increasing too greatly. The default is 1 minute.
    * @param modifications The modifications to the created circuit breaker. This is useful for adding your own triggers, and metrics on circuit changes.
    * @param translatedError This function allows you to translate the Request, Circuit RejectedExecution, and RequestKey and build a Throwable of your own. Default is to build a RejectedExecutionHttp4sClient.
    * @param shouldFail This function allows you to determine what counts as a failure when a Response is succesfully retrieved. Default is to see any 5xx Response as a failure.
    **/
  def byMapRefAndKeyed[F[_], K](
    mapRef: MapRef[F, K, Option[CircuitBreaker.State]],
    keyFunction: Request[F] => K,
    maxFailures: Int,
    resetTimeout: FiniteDuration,
    backoff: FiniteDuration => FiniteDuration = io.chrisdavenport.circuit.Backoff.exponential,
    maxResetTimeout: Duration = 1.minute,
    modifications: CircuitBreaker[Resource[F, *]] => CircuitBreaker[Resource[F, *]] = {(x: CircuitBreaker[Resource[F, *]]) => x},
    translatedError: (Request[F], RejectedExecution, K) => Option[Throwable] = defaultTranslatedError[F, K](_, _, _),
    shouldFail: (Request[F], Response[F]) => ShouldCircuitBreakerSeeAsFailure = defaultShouldFail[F](_, _)
  )(client: Client[F])(implicit F: Temporal[F]): Client[F] = {
    def newTranslate(req: Request[F], re: RejectedExecution): Option[Throwable] = {
      val k = keyFunction(req)
      translatedError(req, re, k)
    }
    val f : Request[F] => CircuitBreaker[Resource[F, *]] = {(req: Request[F]) => 
      val optRef = mapRef(keyFunction(req))
      val ref = MapRef.defaultedRef(optRef, CircuitBreaker.Closed(0)).mapK(Resource.liftK)
      val cbInit = CircuitBreaker.unsafe(ref, maxFailures, resetTimeout, backoff, maxResetTimeout, Resource.eval(F.unit), Resource.eval(F.unit), Resource.eval(F.unit), Resource.eval(F.unit))
      modifications(cbInit)
    }
    generic(f, shouldFail, newTranslate)(client)
  }

  /** Generic CircuitedClient
    * 
    * @param cbf How to generate a CircuitBreaker from a Request
    * @param translatedError This function allows you to translate the Request, Circuit RejectedExecution, and RequestKey and build a Throwable of your own. Default is to build a RejectedExecutionHttp4sClient.
    * @param shouldFail This function allows you to determine what counts as a failure when a Response is succesfully retrieved. Default is to see any 5xx Response as a failure.
    */ 
  def generic[F[_]](
    cbf: Request[F] => CircuitBreaker[Resource[F, *]],
    shouldFail: (Request[F], Response[F]) => ShouldCircuitBreakerSeeAsFailure = defaultShouldFail[F](_, _),
    translatedError: (Request[F], RejectedExecution) => Option[Throwable] = defaultTranslatedErrorSimple[F](_, _)
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
        case re: RejectedExecution => 
          val e = translatedError(req, re).getOrElse(re)
          Resource.eval(F.raiseError(e))
        case e => Resource.eval(F.raiseError(e))
      }
    }
  }

  object Global {
    private val state = new ConcurrentHashMap[RequestKey, CircuitBreaker.State]()

    def byRequestKey[F[_]](
      maxFailures: Int,
      resetTimeout: FiniteDuration,
      backoff: FiniteDuration => FiniteDuration = io.chrisdavenport.circuit.Backoff.exponential,
      maxResetTimeout: Duration = 1.minute,
      modifications: CircuitBreaker[Resource[F, *]] => CircuitBreaker[Resource[F, *]] = {(x: CircuitBreaker[Resource[F, *]]) => x},
      translatedError: (Request[F], RejectedExecution, RequestKey) => Option[Throwable] = defaultTranslatedError[F, RequestKey](_, _, _),
      shouldFail: (Request[F], Response[F]) => ShouldCircuitBreakerSeeAsFailure = defaultShouldFail[F](_, _)
    )(client: Client[F])(implicit F: Temporal[F]): Client[F] = {
      val mapref = MapRef.fromConcurrentHashMap[F, RequestKey, CircuitBreaker.State](state)
      byMapRefAndKeyed[F, RequestKey](mapref, RequestKey.fromRequest(_), maxFailures, resetTimeout, backoff, maxResetTimeout, modifications, translatedError, shouldFail)(client)
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

  sealed abstract case class RejectedExecutionHttp4sClient private[CircuitedClient](
    prelude: RequestPrelude,
    rejectedExecution: RejectedExecution
  ) extends RuntimeException{
    override final val getMessage = s"Execution Rejection: $prelude, ${rejectedExecution.reason}"
    override final def getCause = rejectedExecution
  }

  def defaultTranslatedError[F[_], K](request: Request[F], re: RejectedExecution, k: K): Option[Throwable] = {
    val _ = k
    defaultTranslatedErrorSimple(request, re)
  }

  def defaultTranslatedErrorSimple[F[_]](request: Request[F], re: RejectedExecution): Option[Throwable] = {
    new RejectedExecutionHttp4sClient(request.requestPrelude, re){}.some
  }

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