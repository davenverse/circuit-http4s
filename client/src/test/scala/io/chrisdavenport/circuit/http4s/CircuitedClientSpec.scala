package io.chrisdavenport.circuit.http4s.client

import cats.implicits._
import io.chrisdavenport.circuit.CircuitBreaker
import cats.effect._
import org.http4s._
import org.http4s.implicits._
import org.http4s.client._
import scala.concurrent.duration._
import org.http4s.dsl.io._

import munit.CatsEffectSuite

class CircuitedClientSpec extends CatsEffectSuite {
// "CircuitedClient" should {
  test("Fail Requests on a Failing Service") {
    val app: HttpApp[IO] = HttpRoutes.of[IO]{
      case _ => IO.raiseError(new Throwable("Boo!"))
    }.orNotFound

    val iClient = Client.fromHttpApp(app)

    val test = for {
      circuit <- CircuitBreaker.in[IO, Resource[IO, *]](0, 20.seconds)
      newClient = CircuitedClient(circuit)(iClient)
      _ <- newClient.expect[String](Request[IO](Method.GET)).attempt
      e <- newClient.expect[String](Request[IO](Method.GET)).attempt
    } yield e

    test.map{
      case Left(_: CircuitBreaker.RejectedExecution) => assert(true)
      case _ => assert(false)
    }
  }

  test("Fail a request by key") {
    val app: HttpApp[IO] = HttpRoutes.of[IO]{
      case GET -> Root / "fail" => Response[IO](Status.InternalServerError).pure[IO]
      case GET -> Root / "success" => Response[IO](Status.Ok).pure[IO]
    }.orNotFound

    val iClient = Client.fromHttpApp(app)

    val test = for {

      newClient <- CircuitedClient.byRequestKey(0, 20.seconds)(iClient)
      req = Request[IO](Method.GET, uri"http://www.mycoolsite.com/fail")
      _ <- newClient.expect[String](req).attempt
      e <- newClient.expect[String](req).attempt
    } yield e

    test.map{
      case Left(_: CircuitBreaker.RejectedExecution) => assert(true)
      case _ => assert(false)
    }
  }

  test("byKey not affect other addresses") {
    val app: HttpApp[IO] = HttpRoutes.of[IO]{
      case GET -> Root / "fail" => Response[IO](Status.InternalServerError).pure[IO]
      case GET -> Root / "success" => Response[IO](Status.Ok).withEntity("Hey There").pure[IO]
    }.orNotFound

    val iClient = Client.fromHttpApp(app)

    val test = for {

      newClient <- CircuitedClient.byRequestKey(0, 20.seconds)(iClient)
      req = Request[IO](Method.GET, uri"http://www.mycoolsite.com/fail")
      _ <- newClient.expect[String](req).attempt
      e <- newClient.expect[String](Request[IO](Method.GET, uri"http://www.adifferentsite.com/success")).attempt
    } yield e

    test.map{
      case Right(base) => assertEquals(base, "Hey There")
      case _ => assert(false)
    }
  }
}