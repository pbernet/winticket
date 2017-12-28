package com.winticket.server

import akka.event.Logging
import akka.http.scaladsl.Http
import akka.http.scaladsl.Http.ServerBinding
import akka.stream.ActorMaterializer

import scala.concurrent.duration.DurationInt
import scala.concurrent.{Await, ExecutionContext, Future}
import scala.util.Failure

/**
 * Start the http service and bootstrap the actor system (in WinticketService)
 *
 * To clean db execute from terminal: rm -rf target/winticket/journal
 *
 */

object WinticketMicroserviceMain extends WinticketService {

  override implicit def executor: ExecutionContext = system.dispatcher
  override protected val log = Logging(system.eventStream, "winticket-main")
  override protected implicit val materializer: ActorMaterializer = ActorMaterializer()

  def main(args: Array[String]): Unit = {
    //Unfortunately the System Property -Dconfig.resource=/production.conf can not be initialized via JVM Arg, because the Config trait is initialized before this line...
    val jvmArg =
      """-D(\S+)=(\S+)""".r
    for (jvmArg(name, value) <- args) System.setProperty(name, value)

    log.info(s"About ot bind to: $httpInterface and: $httpPort")
    val bindingFuture: Future[ServerBinding] = Http().bindAndHandle(routes, httpInterface, httpPort)

    bindingFuture.onComplete {
      case Failure(err) =>
        log.error(err, s"Failed to bind to: $httpInterface:$httpPort")
        Http().shutdownAllConnectionPools()
        system.terminate()
      case _ => log.info(s"Bound to: $httpInterface:$httpPort")

    }

    scala.sys.addShutdownHook {
      log.info("Terminating...")
      Http().shutdownAllConnectionPools()
      system.terminate()
      Await.result(system.whenTerminated, 30.seconds)
      log.info("Terminated... Bye")
    }
  }
}