package com.winticket.server

import akka.http.scaladsl.model.DateTime
import scala.concurrent.duration.DurationInt
import akka.actor.{ Props, ActorLogging }
import akka.persistence.{ SnapshotOffer, PersistentActor }
import com.winticket.server.DrawingActor._
import com.winticket.server.DrawingProtocol.{ Subscribed, DrawingCreated, DrawingEvent }

object DrawingActor {

  import DrawingProtocol._

  sealed trait Command
  //TODO Make a separate CreateTennant cmd
  case class CreateDrawing(tennantID: String, tennantYear: Int, tennantEMail: String, drawingEventID: String, drawingEventName: String, drawingEventDate: DateTime, linkToTicket: String, securityCodeForTicket: String) extends Command
  case class Subscribe(year: Int, eventID: Int, email: String, ip: String) extends Command

  sealed trait Query
  case object GetSubscribtions extends Query

  sealed trait ScheduledCmd
  case object DrawWinner extends ScheduledCmd

  case class DrawingState(tennantID: String, tennantYear: Int, tennantEMail: String, drawingEventID: String, drawingEventName: String, drawingEventDate: DateTime, subscriptions: Seq[SubscriptionRecord] = Nil) {
    def updated(evt: DrawingEvent): DrawingState = evt match {
      case Subscribed(year, eventID, email, ip, date) => copy(tennantID, tennantYear, tennantEMail, drawingEventID, drawingEventName, drawingEventDate,
        SubscriptionRecord(year, eventID, email, ip, date) +: subscriptions)
      case _ => this
    }
  }

  case class SubscriptionRecord(year: Int, eventID: Int, email: String, ip: String, date: DateTime)

  def props(tennantID: String) = Props(new DrawingActor(tennantID))

}

class DrawingActor(tennantID: String) extends PersistentActor with ActorLogging {

  //Sheduled execution bootstrap
  import context.dispatcher
  val draw = context.system.scheduler.schedule(5 seconds, 1 hour, self, DrawWinner)
  override def postStop() = draw.cancel()

  def persistenceId = "Tennant." + tennantID

  var state: Option[DrawingState] = None

  def updateState(evt: DrawingEvent) = state = state.map(_.updated(evt))

  def setInitialState(evt: DrawingCreated) = {
    state = Some(DrawingState(evt.tennantID, evt.tennantYear, evt.tennantEMail, evt.drawingEventID, evt.drawingEventName, evt.drawingEventDate))
    context.become(receiveCommands)
  }

  val receiveRecover: Receive = {
    case evt: DrawingCreated => setInitialState(evt)
    case evt: DrawingEvent   => updateState(evt)
    case SnapshotOffer(_, snapshot: DrawingState) => {
      state = Some(snapshot)
      context.become(receiveCommands)
    }
  }

  val receiveCreate: Receive = {
    case c @ CreateDrawing(tennantID, tennantYear, tennantEMail, drawingEventID, drawingEventName, drawingEventDate, linkToTicket, securityCodeForTicket) => {
      persist(DrawingCreated(tennantID, tennantYear, tennantEMail, drawingEventID, drawingEventName, drawingEventDate, linkToTicket, securityCodeForTicket)) { evt =>
        log.info(s"Creating drawing with from message $c")
        setInitialState(evt)
      }
    }
  }

  val receiveCommands: Receive = {
    case Subscribe(year, eventID, email, ip) => {
      persist(Subscribed(year, eventID, email, ip, DateTime.now))(evt => {
        log.info(s"Subscribed $email for event '$eventID'")
        updateState(evt)
        sender() ! evt
      })

    }

    // Scheduled execution
    case DrawWinner => {
      log.info("Event DrawWinner recieved")

      //the drawing is 3 days before the event
      val eventDate = state.get.drawingEventDate
      if ((eventDate - 1000L * 3600L * 24L * 60L) <= DateTime.now) {
        log.info("Execute drawing for EventDate: " + eventDate)

        //24 Stunden-Regel bei der Selektion für die Verlosung: Wenn mehr als eine E-Mail Adresse von der gleichen IP in DB sind, dann wird nur die erste E-Mail berücksichtigt
        val subscribtionsByEvent = state.get.subscriptions.groupBy(_.eventID)
        log.info(subscribtionsByEvent.mkString)

      } else {
        log.info("Drawing for EventDate: " + eventDate + " is not yet due")

      }

    }

    // Queries
    case GetSubscribtions => {
      sender() ! state.get.subscriptions
    }
  }

  // Initially we expect a CreateDrawing command
  val receiveCommand: Receive = receiveCreate
}

