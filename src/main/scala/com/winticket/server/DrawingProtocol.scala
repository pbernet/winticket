package com.winticket.server

import akka.http.scaladsl.model.DateTime

object DrawingProtocol {

  sealed trait DrawingEvent
  case class DrawingCreated(tennantID: String, tennantYear: Int, tennantEMail: String, drawingEventID: String, drawingEventName: String, drawingEventDate: DateTime, linkToTicket: String, securityCodeForTicket: String) extends DrawingEvent
  case class Subscribed(year: String, eventID: String, email: String, ip: String, date: DateTime) extends DrawingEvent
  case class DrawWinnerExecuted(winnerEMail: String) extends DrawingEvent

}
