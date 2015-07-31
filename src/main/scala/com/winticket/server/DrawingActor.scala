package com.winticket.server

import akka.http.scaladsl.model.DateTime
import com.winticket.core.Config
import com.winticket.mail.{ SmtpConfig, EMailMessage, EMailService }
import scala.concurrent.duration.DurationInt
import akka.actor.{ Props, ActorLogging }
import akka.persistence.{ SnapshotOffer, PersistentActor }
import com.winticket.server.DrawingActor._
import com.winticket.server.DrawingProtocol.{ DrawWinnerExecuted, Subscribed, DrawingCreated, DrawingEvent }

import scala.util.Random

object DrawingActor {

  import DrawingProtocol._

  sealed trait Command

  case class CreateDrawing(tennantID: String, tennantYear: Int, tennantEMail: String, drawingEventID: String, drawingEventName: String, drawingEventDate: DateTime, linkToTicket: String, securityCodeForTicket: String) extends Command

  case class Subscribe(year: String, eventID: String, email: String, ip: String) extends Command

  sealed trait Query

  case object GetSubscribtions extends Query
  case object GetWinnerEMail extends Query

  sealed trait ScheduledCmd

  case object DrawWinner extends ScheduledCmd

  case class DrawingState(tennantID: String, tennantYear: Int, tennantEMail: String, drawingEventID: String, drawingEventName: String, drawingEventDate: DateTime, drawingWinnerEMail: Option[String] = None, subscriptions: Seq[SubscriptionRecord] = Nil) {
    def updated(evt: DrawingEvent): DrawingState = evt match {
      case Subscribed(year, eventID, email, ip, date) => copy(tennantID, tennantYear, tennantEMail, drawingEventID, drawingEventName, drawingEventDate, drawingWinnerEMail,
        SubscriptionRecord(year, eventID, email, ip, date) +: subscriptions)
      case DrawWinnerExecuted(winnerEMail) => copy(tennantID, tennantYear, tennantEMail, drawingEventID, drawingEventName, drawingEventDate, drawingWinnerEMail = Some(winnerEMail), subscriptions)
      case _                               => this
    }
  }

  case class SubscriptionRecord(year: String, eventID: String, email: String, ip: String, date: DateTime)

  def props(tennantID: String) = Props(new DrawingActor(tennantID))

}

/**
 *  TODO States  receiveCreate (aka preDrawing) -> receiveCommands (aka whileDrawing) -> postDrawing
 *
 * @param actorID
 */

class DrawingActor(actorID: String) extends PersistentActor with ActorLogging with Config {

  //Bootstrap for sheduled execution of DrawWinner
  import context.dispatcher
  //the initial shot is set to 1 minute, so that some testdata can be brought into the system with sbt test
  val draw = context.system.scheduler.schedule(1 minute, 1 hour, self, DrawWinner)

  override def postStop() = draw.cancel()

  def persistenceId = actorID

  var state: Option[DrawingState] = None

  def updateState(evt: DrawingEvent) = state = state.map(_.updated(evt))

  def setInitialState(evt: DrawingCreated) = {
    state = Some(DrawingState(evt.tennantID, evt.tennantYear, evt.tennantEMail, evt.drawingEventID, evt.drawingEventName, evt.drawingEventDate))
    context.become(receiveCommands)
  }

  def setPostDrawingState(evt: DrawWinnerExecuted) = {
    updateState(evt)
    context.become(postDrawing)
  }

  def isDrawingExecuted: Boolean = {
    state.get.drawingWinnerEMail.isDefined
  }

  //on startup of the actor these events are recovered
  val receiveRecover: Receive = {
    case evt: DrawingCreated =>
      log.info("RECOVER DrawingCreated: " + evt); setInitialState(evt)
    case evt: DrawWinnerExecuted =>
      log.info("RECOVER DrawWinnerExecuted: " + evt); setPostDrawingState(evt)
    case evt: DrawingEvent =>
      log.info("RECOVER DrawingEvent: " + evt); updateState(evt)
    case SnapshotOffer(_, snapshot: DrawingState) => {
      state = Some(snapshot)
      context.become(receiveCommands)
    }
    case a @ _ => log.warning("Received unknown message for state receiveRecover - do nothing. Message is: " + a)
  }

  val receiveCreate: Receive = {
    case c @ CreateDrawing(tennantID, tennantYear, tennantEMail, drawingEventID, drawingEventName, drawingEventDate, linkToTicket, securityCodeForTicket) => {
      persist(DrawingCreated(tennantID, tennantYear, tennantEMail, drawingEventID, drawingEventName, drawingEventDate, linkToTicket, securityCodeForTicket)) { evt =>
        log.info(s"Creating drawing with from message $c")
        setInitialState(evt)
      }
    }
    case a @ _ => log.warning("Received unknown message for state receiveCreate - do nothing. Message is: " + a)
  }

  val receiveCommands: Receive = {
    case Subscribe(year, eventID, email, ip) => {
      //only matching events are accepted
      if (state.get.drawingEventID == eventID) {
        persist(Subscribed(year, eventID, email, ip, DateTime.now))(evt => {
          log.info(s"Subscribed $email for event with ID $eventID")
          updateState(evt)
          sender() ! evt
        })

      } else {
        log.debug("Subscribe for Event: " + eventID + " is ignored by: " + this.persistenceId)
      }

    }

    case DrawWinner => {
      log.info("Scheduled command DrawWinner recieved")
      val eventDate = state.get.drawingEventDate
      val eventID = state.get.drawingEventID
      //TODO This test is not nessecary anymore with the new state postDrawing, but remains here for saftey
      if (isDrawingExecuted) {
        log.info("Drawing for eventID: " + eventID + " and eventDate: " + eventDate + " is already executed")
      } else {
        //TODO adjust: the drawing is 3 days before the event
        if ((eventDate - 1000L * 3600L * 24L * 60L) <= DateTime.now) {
          log.info("Execute drawing for eventID: " + eventID + " and eventDate: " + eventDate)

          //Prevent abuse: ignore re-subscriptions (= the size of the list of subscriptions can be anything from 1 to n)
          val subscribtionsByEMail = state.get.subscriptions.groupBy(_.email)
          if (subscribtionsByEMail.size > 0) {

            val theLUCKYNumber = Random.nextInt(subscribtionsByEMail.size)
            val theWinnerEMail = subscribtionsByEMail.keySet.toList(theLUCKYNumber)
            log.info("The Winner is: " + theWinnerEMail + " with subscriptions: " + subscribtionsByEMail.get(theWinnerEMail).mkString)

            persist(DrawWinnerExecuted(theWinnerEMail))(evt => {
              updateState(evt)
              sender() ! evt
              context.become(postDrawing)
            })

            //TODO winnerMessage cc to Organizer, HTML-Content with link to PDF-Docs and access code
            val smtpConfig = SmtpConfig(tls, ssl, port, host, user, password)
            val winnerMessage = EMailMessage("Subject: ", theWinnerEMail, state.get.tennantEMail, Some("YOU WON - Drawing was at: " + DateTime.now), None, smtpConfig, 1 minute, 3)
            EMailService.send(winnerMessage)
          } else {
            log.info("Drawing for eventID: " + eventID + " and eventDate: " + eventDate + " has NO subscriptions, that means no winner can be drawn...")
            updateState(DrawWinnerExecuted("N/A"))
            context.become(postDrawing)
          }
        } else {
          log.info("Drawing for eventID: " + eventID + " and eventDate: " + eventDate + " is not yet due")
        }
      }
    }

    // Queries
    case GetSubscribtions => {
      sender() ! state.get.subscriptions
    }
    case GetWinnerEMail => {
      sender() ! state.get.drawingWinnerEMail
    }
    case a @ _ => log.warning("Received unknown message for state receiveCommands - do nothing. Message is: " + a)
  }

  val postDrawing: Receive = {
    // This is the final state, the drawing is in the past. Only queries are accepted
    case GetSubscribtions => {
      sender() ! state.get.subscriptions
    }
    case GetWinnerEMail => {
      sender() ! state.get.drawingWinnerEMail
    }
    case a @ _ => log.warning("Received unknown message for state postDrawing - do nothing. Message is: " + a)
  }

  // Initially we expect a CreateDrawing command
  val receiveCommand: Receive = receiveCreate
}

