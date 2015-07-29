package com.winticket.server

import akka.http.scaladsl.model.DateTime

object DrawingProtocol {

  sealed trait DrawingEvent
  case class DrawingCreated(tennantID: String, tennantYear: Int, tennantEMail: String, drawingEventID: String, drawingEventName: String, drawingEventDate: DateTime, linkToTicket: String, securityCodeForTicket: String) extends DrawingEvent
  case class Subscribed(year: Int, eventID: Int, email: String, ip: String, date: DateTime) extends DrawingEvent

  //  case class SoldOut(user: String) extends DrawingEvent
  //  case class TicketsBought(user: String, quantity: Int) extends DrawingEvent
  //  case class PriceChanged(newPrice: Int) extends DrawingEvent
  //  case class CapacityIncreased(toBeAdded: Int) extends DrawingEvent

}
