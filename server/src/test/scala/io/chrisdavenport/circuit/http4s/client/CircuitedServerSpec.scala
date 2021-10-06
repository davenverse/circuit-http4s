package io.chrisdavenport.circuit.http4s.client

import io.chrisdavenport.circuit.CircuitBreaker
import cats.effect._
import org.http4s._
import org.http4s.implicits._
import scala.concurrent.duration._
import munit.CatsEffectSuite

class CircuitedServerSpec extends CatsEffectSuite {
// "CircuitedServer" should {
  test("Fail Requests on a Failing Service") {
    val app: HttpApp[IO] = HttpRoutes.of[IO]{
      case _ => IO.raiseError(new Throwable("Boo!"))
    }.orNotFound

    val test = for {
      circuit <- CircuitBreaker.of[IO](0, 20.seconds)
      newApp = CircuitedServer(circuit)(app)
      _ <- newApp(Request[IO](Method.GET)).attempt
      e <- newApp(Request[IO](Method.GET)).attempt
    } yield e

    test.map{
      case Left(_: CircuitBreaker.RejectedExecution) => assert(true)
      case _ => assert(false)
    }
  }
// }
}