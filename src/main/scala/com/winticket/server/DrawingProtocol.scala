package com.winticket.server

import akka.http.scaladsl.model.DateTime

object DrawingProtocol {

  sealed trait DrawingEvent
  case class DrawingActorCreated(actorPath: String) extends DrawingEvent
  case class DrawingCreated(tennantID: String, tennantYear: Int, tennantEMail: String, drawingEventID: String, drawingEventName: String, drawingEventDate: DateTime, drawingLinkToTicket: String, drawingSecurityCodeForTicket: String) extends DrawingEvent
  case class Subscribed(tennantID: String, year: String, eventID: String, email: String, ip: String, date: DateTime) extends DrawingEvent
  case class SubscriptionRemoved(tennantID: String = "", tennantYear: Int = 0, drawingEventID: Int = 0, clientIP: Option[String]) extends DrawingEvent
  case class DrawWinnerExecuted(winnerEMail: String) extends DrawingEvent

  sealed trait IPCheckEvent
  case class IPCheckRecordAdded(tennantID: String, tennantYear: Int, drawingEventID: Int, clientIP: Option[String]) extends IPCheckEvent
  case class IPCheckRecordRemoved(clientIP: Option[String]) extends IPCheckEvent
}
