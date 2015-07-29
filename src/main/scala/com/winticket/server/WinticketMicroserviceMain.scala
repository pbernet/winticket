package com.winticket.server

import akka.event.{ Logging, LoggingAdapter }
import akka.http.scaladsl.Http
import akka.stream.ActorMaterializer
import com.winticket.core.Config

import scala.concurrent.ExecutionContext

object WinticketMicroserviceMain extends App with Config with WinticketService {

  override protected implicit val executor: ExecutionContext = system.dispatcher
  override protected val log: LoggingAdapter = Logging(system, getClass)
  override protected implicit val materializer: ActorMaterializer = ActorMaterializer()

  //to clean execute from terminal: rm -rf target/winticket/journal
  addStammdata
  printSubscriptions

  Http().bindAndHandle(routes, httpInterface, httpPort)
}