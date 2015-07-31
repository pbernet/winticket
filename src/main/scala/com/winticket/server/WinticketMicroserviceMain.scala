package com.winticket.server

import akka.event.{ Logging, LoggingAdapter }
import akka.http.scaladsl.Http
import akka.http.scaladsl.model.DateTime
import akka.stream.ActorMaterializer
import com.winticket.core.Config
import com.winticket.server.DrawingActor.CreateDrawing

import scala.concurrent.ExecutionContext

object WinticketMicroserviceMain extends App with Config with WinticketService {

  override protected implicit val executor: ExecutionContext = system.dispatcher
  override protected val log: LoggingAdapter = Logging(system, getClass)
  override protected implicit val materializer: ActorMaterializer = ActorMaterializer()

  //to clean db execute from terminal: rm -rf target/winticket/journal
  //after a re-start these commands are re-sent, but since the actor is in the recieveCommands state are not effective
  //TODO Create Admin-Rest Interface for creation
  val drawingEventDateGMT49 = DateTime(2015, 9, 12, 20, 0, 0)
  val linkToTicket49 = "http://tkpk.ch/twc/ZmN2AakhLajkZQN4BGy8"
  val securityCodeForTicket49 = "8ded"
  val create49 = CreateDrawing("gruenfels", 2015, "info@gruenfels.ch", "49", "Schertenleib & Jegerlehner", drawingEventDateGMT49, linkToTicket49, securityCodeForTicket49)
  createDrawingGruenfels(create49)

  val drawingEventDateGMT50 = DateTime(2015, 10, 17, 20, 0, 0)
  val linkToTicket50 = "http://tkpk.ch/twc/ZmN2AakhLajkZQN4BGy8"
  val securityCodeForTicket50 = "8ded"
  val create50 = CreateDrawing("gruenfels", 2015, "info@gruenfels.ch", "50", "Christopf Stiefel‘s Inner Language Trio", drawingEventDateGMT50, linkToTicket50, securityCodeForTicket50)
  createDrawingGruenfels(create50)

  //to see the amount of data on startup
  logSubscriptions
  logWinners

  Http().bindAndHandle(routes, httpInterface, httpPort)
}