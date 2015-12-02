package com.winticket.server

import akka.event.{Logging, LoggingAdapter}
import akka.http.scaladsl.Http
import akka.stream.ActorMaterializer

import scala.concurrent.duration.DurationInt
import scala.concurrent.{Await, ExecutionContext}

/**
  * Start the http service and bootstrap the actor system (in WinticketService)
  *
  * To clean db execute from terminal: rm -rf target/winticket/journal
  *
  **/

object WinticketMicroserviceMain extends WinticketService {

  override protected implicit val executor: ExecutionContext = system.dispatcher
  override protected val log: LoggingAdapter = Logging(system, getClass)
  override protected implicit val materializer: ActorMaterializer = ActorMaterializer()

  def main(args: Array[String]): Unit = {
    //Unfortunately the System Property -Dconfig.resource=/production.conf can not be initialized via JVM Arg, because the Config trait is initialized before this line...
    val jvmArg =
      """-D(\S+)=(\S+)""".r
    for (jvmArg(name, value) <- args) System.setProperty(name, value)

    logSubscriptions()
    logWinners()

    log.info(s"Bind to: $httpInterface and: $httpPort")
    Http().bindAndHandle(routes, httpInterface, httpPort)

    scala.sys.addShutdownHook {
      log.info("Terminating...")
      system.terminate()
      Await.result(system.whenTerminated, 30.seconds)
      log.info("Terminated... Bye")
    }
  }
}