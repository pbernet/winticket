package com.winticket.server

import akka.event.{Logging, LoggingAdapter}
import akka.http.scaladsl.Http
import akka.http.scaladsl.model.DateTime
import akka.stream.ActorMaterializer
import com.github.marklister.collections.io.{CsvParser, GeneralConverter}
import com.winticket.core.Config
import com.winticket.server.DrawingActor.CreateDrawing

import scala.concurrent.ExecutionContext
import scala.util.{Failure, Success, Try}

/**
 * Load the event data from the resource file and start the http service
 *
 * To clean db execute from terminal: rm -rf target/winticket/journal
 *
 * After a re-start the commands CreateDrawing are re-sent to the DrawingActor,
 * but since the DrawingActor is in the state "recieveCommands" the commands are not effective
 */

object WinticketMicroserviceMain extends App with Config with WinticketService {

  override protected implicit val executor: ExecutionContext = system.dispatcher
  override protected val log: LoggingAdapter = Logging(system, getClass)
  override protected implicit val materializer: ActorMaterializer = ActorMaterializer()

  //Convert directly to akka DataTime. The failure case looks like this: Failure(java.lang.IllegalArgumentException: None.get at line x)
  implicit val DateConverter: GeneralConverter[DateTime] = new GeneralConverter(DateTime.fromIsoDateTimeString(_).get)

  class TryIterator[T](it: Iterator[T]) extends Iterator[Try[T]] {
    def next = Try(it.next)

    def hasNext = it.hasNext
  }

  val aListOfDrawingEventsTry = new TryIterator(CsvParser(CreateDrawing).iterator(new java.io.FileReader(eventsFilePath), hasHeader = true)).toList
  aListOfDrawingEventsTry.foreach {
    case Success(content) => createDrawing(content)
    case Failure(f) => log.error(f.getMessage)
  }

  //to see the amount of data on startup
  logSubscriptions()
  logWinners()

  Http().bindAndHandle(routes, httpInterface, httpPort)
}