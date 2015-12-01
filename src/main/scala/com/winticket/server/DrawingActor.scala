package com.winticket.server

import akka.actor.{ActorLogging, Props}
import akka.http.scaladsl.model.DateTime
import akka.persistence.{PersistentActor, SnapshotOffer}
import com.winticket.core.Config
import com.winticket.mail.{EMailMessage, EMailService, SmtpConfig}
import com.winticket.server.DrawingActor._
import com.winticket.server.DrawingProtocol.{DrawWinnerExecuted, DrawingCreated, DrawingEvent, Subscribed}
import com.winticket.util.RenderHelper

import scala.concurrent.duration.DurationInt
import scala.util.Random

object DrawingActor {

  import DrawingProtocol._

  sealed trait Command
  case class CreateDrawing(tennantID: String, tennantYear: Int, tennantEMail: String, drawingEventID: String, drawingEventName: String, drawingEventDate: DateTime, linkToTicket: String, securityCodeForTicket: String) extends Command
  case class Subscribe(tennantID: String, year: String, eventID: String, email: String, ip: String) extends Command

  sealed trait ScheduledCmd
  case object DrawWinner extends ScheduledCmd

  sealed trait Query
  case object GetSubscribtions extends Query
  case object GetWinnerEMail extends Query

  case class DrawingState(tennantID: String, tennantYear: Int, tennantEMail: String, drawingEventID: String, drawingEventName: String, drawingEventDate: DateTime, drawingWinnerEMail: Option[String] = None, drawingLinkToTicket: String, drawinSsecurityCodeForTicket: String, subscriptions: Seq[SubscriptionRecord] = Nil) {
    def updated(evt: DrawingEvent): DrawingState = evt match {
      case Subscribed(year, eventID, email, ip, date) => copy(tennantID, tennantYear, tennantEMail, drawingEventID, drawingEventName, drawingEventDate, drawingWinnerEMail, drawingLinkToTicket, drawinSsecurityCodeForTicket,
        SubscriptionRecord(year, eventID, email, ip, date) +: subscriptions)
      case DrawWinnerExecuted(winnerEMail) => copy(tennantID, tennantYear, tennantEMail, drawingEventID, drawingEventName, drawingEventDate, drawingWinnerEMail = Some(winnerEMail), drawingLinkToTicket, drawinSsecurityCodeForTicket, subscriptions)
      case _                               => this
    }

    def sizeSubscriptions = subscriptions.length
  }

  case class SubscriptionRecord(year: String, eventID: String, email: String, ip: String, date: DateTime)

  def props(tennantID: String) = Props(new DrawingActor(tennantID))

}

/**
 * There is a DrawingActor for each event, which contains the business logic to:
 * - Create a event
 * - Accept subscriptions for the event
 * - Draw a winner for the event
 *
 *  States:
 *  - receiveCreate (aka preDrawing)
 *  - receiveCommands (aka whileDrawing)
 *  - postDrawing (This is the final state, the drawing is in the past. Only queries are accepted)
 *
 * @param actorID the tennantID
 */

class DrawingActor(actorID: String) extends PersistentActor with ActorLogging with Config {

  //Bootstrap for scheduled execution of DrawWinner
  //The initial shot is delayed, so that some testdata can be brought into the system with sbt test
  import context.dispatcher
  val draw = context.system.scheduler.schedule(5 minute, 1 hour, self, DrawWinner)

  override def postStop() = draw.cancel()

  def persistenceId = actorID

  var state: Option[DrawingState] = None

  def updateState(evt: DrawingEvent) = state = state.map(_.updated(evt))

  def setInitialState(evt: DrawingCreated) = {
    state = Some(DrawingState(evt.tennantID, evt.tennantYear, evt.tennantEMail, evt.drawingEventID, evt.drawingEventName, evt.drawingEventDate, None, evt.drawingLinkToTicket, evt.drawingSecurityCodeForTicket))
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
    case msg => log.warning(s"Received unknown message for state receiveRecover - do nothing. Message is: $msg")
  }

  val receiveCreate: Receive = {
    case c @ CreateDrawing(tennantID, tennantYear, tennantEMail, drawingEventID, drawingEventName, drawingEventDate, linkToTicket, securityCodeForTicket) => {
      persist(DrawingCreated(tennantID, tennantYear, tennantEMail, drawingEventID, drawingEventName, drawingEventDate, linkToTicket, securityCodeForTicket)) { evt =>
        log.info(s"Creating drawing from message $c")
        setInitialState(evt)
      }
    }
    case msg => log.warning(s"Received unknown message for state receiveCreate - do nothing. Message is: $msg")
  }

  val receiveCommands: Receive = {
    case Subscribe(tennantID, year, eventID, email, ip) => {

      def isMatchingEvent = {
        state.get.drawingEventID == eventID && state.get.tennantID == tennantID && state.get.tennantYear.toString == year
      }

      def sentConfirmationMail: Unit = {
        val smtpConfig = SmtpConfig(tls, ssl, port, host, user, password)
        val drawingDate = state.get.drawingEventDate - drawingDateDelta
        val drawinDateNice = drawingDate.day + "." + drawingDate.month + "." + drawingDate.year
        val bodyText = Some(RenderHelper.getFromResourceRenderedWith("/mail/confirm.txt", Map("drawinDateNice" -> drawinDateNice)))
        val confirmationMessage = EMailMessage("Teilnahme an Verlosung: " + state.get.drawingEventName, email, state.get.tennantEMail, None, bodyText, smtpConfig, 1 minute, 3)
        EMailService.send(confirmationMessage)
      }

      if (isMatchingEvent) {
        persist(Subscribed(year, eventID, email, ip, DateTime.now))(evt => {
          log.info(s"Subscribed $email for year: $year and eventID: $eventID")
          updateState(evt)
          sender() ! evt
        })
        sentConfirmationMail
      } else {
        log.debug(s"Subscribe for event: $eventID is ignored by: $persistenceId")
      }
    }

    case DrawWinner => {
      log.info("(Scheduled) command DrawWinner recieved")
      val eventDate = state.get.drawingEventDate
      val eventID = state.get.drawingEventID

      val drawingDate = state.get.drawingEventDate - drawingDateDelta

      //This test is not necessary anymore with the new state "postDrawing", but remains here for safety
      if (isDrawingExecuted) {
        log.info(s"Drawing for eventID: $eventID and eventDate: $eventDate is already executed")
      } else {
        if (drawingDate <= DateTime.now) {
          log.info(s"Execute drawing for eventID: $eventID and eventDate: $eventDate")

          //Prevent abuse: ignore re-subscriptions (= the size of the list of subscriptions can be anything from 1 to n)
          val subscribtionsByEMail = state.get.subscriptions.groupBy(_.email)
          if (subscribtionsByEMail.nonEmpty) {

            val theLUCKYNumber = Random.nextInt(subscribtionsByEMail.size)
            val theWinnerEMail = subscribtionsByEMail.keySet.toList(theLUCKYNumber)
            log.info(s"The Winner is: $theWinnerEMail with subscriptions: " + subscribtionsByEMail.get(theWinnerEMail).mkString)

            persist(DrawWinnerExecuted(theWinnerEMail))(evt => {
              updateState(evt)
              sender() ! evt
              context.become(postDrawing)
            })

            val smtpConfig = SmtpConfig(tls, ssl, port, host, user, password)
            val bodyText = Some(RenderHelper.getFromResourceRenderedWith("/mail/winner.txt", Map("linkToTicket" -> state.get.drawingLinkToTicket, "securityCodeForTicket" -> state.get.drawinSsecurityCodeForTicket)))
            val winnerMessage = EMailMessage("Sie haben gewonnen: 2 Tickets für: " + state.get.drawingEventName, theWinnerEMail, state.get.tennantEMail, None, bodyText, smtpConfig, 1 minute, 3)
            EMailService.send(winnerMessage)

            //TODO Activate Mail to tennant before live
            //val winnerMessageToTennant = EMailMessage("2 Tickets für: " + state.get.drawingEventName + " gehen an: " + theWinnerEMail, state.get.tennantEMail, state.get.tennantEMail, None, bodyText, smtpConfig, 1 minute, 3)
            //EMailService.send(winnerMessageToTennant)

          } else {
            log.info(s"Drawing for eventID: $eventID and eventDate: $eventDate has NO subscriptions, that means no winner can be drawn...")
            updateState(DrawWinnerExecuted("N/A"))
            context.become(postDrawing)
          }
        } else {
          log.info(s"Drawing for eventID: $eventID and eventDate: $eventDate is not yet due")
        }
      }
    }

    // Queries
    case GetSubscribtions => {
      log.debug("Number of subscriptions: " + state.get.subscriptions)
      sender() ! state.get.subscriptions
    }
    case GetWinnerEMail => {
      sender() ! state.get.drawingWinnerEMail
    }
    case msg => log.warning(s"Received unknown message for state receiveCommands - do nothing. Message is: $msg")
  }

  val postDrawing: Receive = {
    case GetSubscribtions => {
      sender() ! state.get.subscriptions
    }
    case GetWinnerEMail => {
      sender() ! state.get.drawingWinnerEMail
    }
    case msg => log.warning(s"Received unknown message for state postDrawing - do nothing. Message is: $msg")
  }

  // Initially we expect a CreateDrawing command
  val receiveCommand: Receive = receiveCreate

  val drawingDateDelta: Long = 1000L * 3600L * 24L * drawingDateDeltaDaysBackwards
}

