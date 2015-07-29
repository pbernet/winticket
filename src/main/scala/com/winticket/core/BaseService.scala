package com.winticket.core

import akka.actor.ActorSystem
import akka.event.LoggingAdapter
import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport
import akka.stream.{ ActorMaterializer }
import scala.concurrent.ExecutionContext

trait BaseService extends Protocol with SprayJsonSupport with Config {
  protected implicit def system: ActorSystem
  protected implicit def executor: ExecutionContext
  protected implicit def materializer: ActorMaterializer
  protected def log: LoggingAdapter
}
