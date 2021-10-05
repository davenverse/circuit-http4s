package io.chrisdavenport.circuit.http4s.client

import io.chrisdavenport.circuit.CircuitBreaker
import cats.syntax.all._
import cats.effect._
import cats.effect.concurrent._
import org.http4s._
import org.http4s.client._
import cats._
import scala.concurrent.duration._
object CircuitedClient {

  def apply[F[_]: Bracket[*[_], Throwable]](cr: CircuitBreaker[F])(c: Client[F]): Client[F] = 
    generic(Function.const[CircuitBreaker[F], Request[F]](cr))(c)


  def byKey[F[_]](
    maxFailures: Int,
    resetTimeout: FiniteDuration,
    exponentialBackoffFactor: Double = 1,
    maxResetTimeout: Duration = Duration.Inf,
    modifications: CircuitBreaker[F] => CircuitBreaker[F] = {x: CircuitBreaker[F] => x},
    shouldFail: (Request[F], Response[F]) => Boolean = defaultShouldFail[F](_, _)
  )(client: Client[F])(implicit F: Sync[F], C: Clock[F]): F[Client[F]] = {
    io.chrisdavenport.mapref.MapRef.ofSingleImmutableMap[F, RequestKey, CircuitBreaker.State](Map.empty[RequestKey, CircuitBreaker.State]).map{
      mapref => 
        val f : Request[F] => CircuitBreaker[F] = {req: Request[F] => 
          val key = RequestKey.fromRequest(req)
          val optRef = mapref(key)
          val ref = new LiftedRefDefaultStorage(optRef, CircuitBreaker.Closed(0))
          val cbInit = CircuitBreaker.unsafe(ref, maxFailures, resetTimeout, exponentialBackoffFactor, maxResetTimeout, F.unit, F.unit, F.unit, F.unit)
          modifications(cbInit)
        }
        generic(f, shouldFail)(client)
    }
  }

  def generic[F[_], A](
    cbf: Request[F] => CircuitBreaker[F],
    shouldFail: (Request[F], Response[F]) => Boolean = defaultShouldFail[F](_, _)
  )(client: Client[F])(implicit F: Bracket[F, Throwable]): Client[F] = {
    Client[F]{req: Request[F] => 
      val cb = cbf(req)
      Resource(
        cb.protect(
          client.run(req).allocated.flatMap{
            case (resp, shutdown) => 
              if (shouldFail(req, resp)) F.raiseError[(Response[F], F[Unit])](CircuitedClientThrowable(resp, shutdown))
              else (resp, shutdown).pure[F]
          }
        ).handleErrorWith{
          // The F here is not checked.
          case e: CircuitedClientThrowable[F] @unchecked => (e.resp, e.shutdown).pure[F]
          case e => F.raiseError(e)
        }
      )
      
    }
  }

  def defaultShouldFail[F[_]](req: Request[F], resp: Response[F]): Boolean = {
    val _ = req
    resp.status.responseClass == Status.ServerError
  }

  private case class CircuitedClientThrowable[F[_]](resp: Response[F], shutdown: F[Unit]) 
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


  /**
   * Operates with default and anytime default is present instead information is removed from underlying ref.
   **/
  private class LiftedRefDefaultStorage[F[_]: Functor, A: Eq](
    val ref: Ref[F, Option[A]],
    val default: A
  ) extends Ref[F, A]{
    def get: F[A] = ref.get.map(_.getOrElse(default))
    
    def set(a: A): F[Unit] = {
      if (a =!= default) ref.set(a.some)
      else ref.set(None)
    }
    
    def access: F[(A, A => F[Boolean])] = ref.access.map{
      case (opt, cb) => 
        (opt.getOrElse(default), {s: A => 
          if (s =!= default) cb(s.some)
          else cb(None)
        })
    }
    
    def tryUpdate(f: A => A): F[Boolean] = 
      tryModify{s: A => (f(s), ())}.map(_.isDefined)
    
    def tryModify[B](f: A => (A, B)): F[Option[B]] =
      ref.tryModify{opt => 
        val s = opt.getOrElse(default)
        val (after, out) = f(s)
        if (after =!= default) (after.some, out)
        else (None, out)
      }
    
    def update(f: A => A): F[Unit] = 
      modify((s: A) => (f(s), ()))
    
    def modify[B](f: A => (A, B)): F[B] = 
      ref.modify{opt => 
        val a = opt.getOrElse(default)
        val (out, b) = f(a)
        if (out =!= default) (out.some, b)
        else (None, b)
      }
    
    def tryModifyState[B](state: cats.data.State[A,B]): F[Option[B]] = 
      tryModify{s => state.run(s).value}
    
    def modifyState[B](state: cats.data.State[A,B]): F[B] = 
      modify{s => state.run(s).value}
    
  }


}