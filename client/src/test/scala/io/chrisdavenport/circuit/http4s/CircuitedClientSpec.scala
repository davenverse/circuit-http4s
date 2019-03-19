package io.chrisdavenport.circuit.http4s.client

import io.chrisdavenport.circuit.CircuitBreaker
import cats.effect._
import org.http4s._
import org.http4s.implicits._
import org.http4s.client._
import scala.concurrent.ExecutionContext.global
import scala.concurrent.duration._

import org.specs2.mutable.Specification

class CircuitedClientSpec extends Specification {
  "CircuitedClient" should {
    "Fail Requests on a Failing Service" in {
      implicit val T = IO.timer(global)
      val app: HttpApp[IO] = HttpRoutes.of[IO]{
        case _ => IO.raiseError(new Throwable("Boo!"))
      }.orNotFound

      val iClient = Client.fromHttpApp(app)

      val test = for {
        circuit <- CircuitBreaker.of[IO](0, 20.seconds)
        newClient = CircuitedClient(circuit)(iClient)
        _ <- newClient.expect[String](Request[IO](Method.GET)).attempt
        e <- newClient.expect[String](Request[IO](Method.GET)).attempt
      } yield e

      test.unsafeRunSync must beLeft.like{
        case _: CircuitBreaker.RejectedExecution => ok
      }
    }
  }
}