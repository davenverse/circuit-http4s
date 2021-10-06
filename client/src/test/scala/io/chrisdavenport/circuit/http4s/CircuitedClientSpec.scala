package io.chrisdavenport.circuit.http4s.client

import cats.implicits._
import io.chrisdavenport.circuit.CircuitBreaker
import cats.effect._
import org.http4s._
import org.http4s.implicits._
import org.http4s.client._
import scala.concurrent.ExecutionContext.global
import scala.concurrent.duration._
import org.http4s.dsl.io._

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

      test.unsafeRunSync() must beLeft.like{
        case _: CircuitBreaker.RejectedExecution => ok
      }
    }

    "Fail a request by key" in {
      implicit val T = IO.timer(global)
      val app: HttpApp[IO] = HttpRoutes.of[IO]{
        case GET -> Root / "fail" => Response[IO](Status.InternalServerError).pure[IO]
        case GET -> Root / "success" => Response[IO](Status.Ok).pure[IO]
      }.orNotFound

      val iClient = Client.fromHttpApp(app)

      val test = for {

        newClient <- CircuitedClient.byKey(0, 20.seconds)(iClient)
        req = Request[IO](Method.GET, uri"http://www.mycoolsite.com/fail")
        _ <- newClient.expect[String](req).attempt
        e <- newClient.expect[String](req).attempt
      } yield e

      test.unsafeRunSync() must beLeft.like{
        case _: CircuitBreaker.RejectedExecution => ok
      }
    }

    "byKey not affect other addresses" in {
      implicit val T = IO.timer(global)
      val app: HttpApp[IO] = HttpRoutes.of[IO]{
        case GET -> Root / "fail" => Response[IO](Status.InternalServerError).pure[IO]
        case GET -> Root / "success" => Response[IO](Status.Ok).withEntity("Hey There").pure[IO]
      }.orNotFound

      val iClient = Client.fromHttpApp(app)

      val test = for {

        newClient <- CircuitedClient.byKey(0, 20.seconds)(iClient)
        req = Request[IO](Method.GET, uri"http://www.mycoolsite.com/fail")
        _ <- newClient.expect[String](req).attempt
        e <- newClient.expect[String](Request[IO](Method.GET, uri"http://www.adifferentsite.com/success")).attempt
      } yield e

      test.unsafeRunSync() must beRight.like{
        case base => base must_=== "Hey There"
      }
    }
  }
}