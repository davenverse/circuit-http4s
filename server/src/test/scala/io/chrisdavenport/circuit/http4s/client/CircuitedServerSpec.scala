package io.chrisdavenport.circuit.http4s.client

import io.chrisdavenport.circuit.CircuitBreaker
import cats.effect._
import org.http4s._
import org.http4s.implicits._
import scala.concurrent.duration._
import cats.effect.unsafe.implicits.global

import org.specs2.mutable.Specification

class CircuitedServerSpec extends Specification {
  "CircuitedServer" should {
    "Fail Requests on a Failing Service" in {
      val app: HttpApp[IO] = HttpRoutes.of[IO]{
        case _ => IO.raiseError(new Throwable("Boo!"))
      }.orNotFound

      val test = for {
        circuit <- CircuitBreaker.of[IO](0, 20.seconds)
        newApp = CircuitedServer(circuit)(app)
        _ <- newApp(Request[IO](Method.GET)).attempt
        e <- newApp(Request[IO](Method.GET)).attempt
      } yield e

      test.unsafeRunSync() must beLeft.like{
        case _: CircuitBreaker.RejectedExecution => ok
      }
    }
  }
}