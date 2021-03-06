package com.winticket.server

import akka.actor.{ActorLogging, Props}
import akka.http.scaladsl.model.DateTime
import akka.persistence.{PersistentActor, RecoveryCompleted, SnapshotOffer}
import com.winticket.core.Config
import com.winticket.mail.{EMailMessage, EMailService, SmtpConfig}
import com.winticket.server.DrawingActor._
import com.winticket.server.DrawingActorSupervisor.RemoveSubscription
import com.winticket.server.DrawingProtocol._
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
  case object GetDrawingReport extends Query

  case class DrawingState(tennantID: String, tennantYear: Int, tennantEMail: String, drawingEventID: String, drawingEventName: String, drawingEventDate: DateTime, drawingWinnerEMail: Option[String] = None, drawingLinkToTicket: String, drawinSsecurityCodeForTicket: String, subscriptions: Seq[SubscriptionRecord] = Nil) {
    def updated(evt: DrawingEvent): DrawingState = evt match {
      case Subscribed(tID, year, eventID, email, ip, date)   => copy(subscriptions = SubscriptionRecord(tID, year, eventID, email, ip, date) +: subscriptions)
      case SubscriptionRemoved(tID, year, eventID, clientIP) => copy(subscriptions = subscriptions.filter(each => each.tennantID == tID && each.year == year.toString && each.eventID == eventID.toString).filterNot(_.ip == clientIP.get))
      case DrawWinnerExecuted(winnerEMail)                   => copy(drawingWinnerEMail = Some(winnerEMail))
      case _                                                 => this
    }

    def totalSubscriptions = subscriptions.length
  }

  case class SubscriptionRecord(tennantID: String, year: String, eventID: String, email: String, ip: String, date: DateTime) {
    def removeLink = s"./../../admin/cmd/$tennantID/$year/$eventID/remove/$ip"
    override def toString = { s"$tennantID-$year-$eventID, $email,$ip,${date.toIsoDateTimeString()}" }
  }

  case class DrawingReport(tennantID: String = "N/A", year: Int = 1970, eventID: String = "N/A", drawingEventDate: DateTime = DateTime.now, drawingEventName: String = "N/A", winnerEMail: String = "N/A", uniqueSubscriptions: Int = 0, totalSubscriptions: Int = 0) {
    def subscriptionLink = s"./../../$tennantID/$year/$eventID/subscribe"
    override def toString = { s"Event: $tennantID-$year-$eventID Date/Name: $drawingEventDate/$drawingEventName Winner: $winnerEMail Subscriptions: ($uniqueSubscriptions/$totalSubscriptions) " }
  }

  def props(uniqueActorName: String) = Props(new DrawingActor(uniqueActorName))

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
 */
class DrawingActor(actorName: String) extends PersistentActor with ActorLogging with Config {

  import context.dispatcher
  private val draw = context.system.scheduler.schedule(initialDelayDrawWinner, intervalDrawWinner, self, DrawWinner)

  private val drawingDateDelta: Long = 1000L * 3600L * 24L * drawingDateDeltaDaysBackwards

  override def postStop() = draw.cancel()

  def persistenceId: String = actorName

  var state: Option[DrawingState] = None

  private def updateState(evt: DrawingEvent) = state = state.map(_.updated(evt))

  private def setInitialState(evt: DrawingCreated) = {
    state = Some(DrawingState(evt.tennantID, evt.tennantYear, evt.tennantEMail, evt.drawingEventID, evt.drawingEventName, evt.drawingEventDate, None, evt.drawingLinkToTicket, evt.drawingSecurityCodeForTicket))
    context.become(receiveCommands)
  }

  private def setPostDrawingState(evt: DrawWinnerExecuted) = {
    updateState(evt)
    context.become(postDrawing)
  }

  private def isDrawingExecuted: Boolean = {
    state.get.drawingWinnerEMail.isDefined
  }

  private def uniqueSubscribtions = {
    //Prevent abuse: ignore re-subscriptions (= the size of the list of subscriptions can be anything from 1 to n)
    state.get.subscriptions.groupBy(_.email)
  }

  private def uniqueActorName = {
    s"${state.get.tennantID}-${state.get.tennantYear}-${state.get.drawingEventID}"
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
    case RecoveryCompleted => log.info("RecoveryCompleted")
    case msg               => log.warning(s"Received unknown message for state receiveRecover - do nothing. Message is: $msg")
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

  // Initially we expect a CreateDrawing command
  val receiveCommand: Receive = receiveCreate

  val receiveCommands: Receive = {
    case Subscribe(tennantID, year, eventID, email, ip) => {

      def isMatchingEvent = {
        state.get.drawingEventID == eventID && state.get.tennantID == tennantID && state.get.tennantYear.toString == year
      }

      def sendConfirmationMail() = {
        val smtpConfig = SmtpConfig(tls, ssl, port, host, user, password)
        val drawingDate = state.get.drawingEventDate - drawingDateDelta
        val drawingDatePretty = drawingDate.day + "." + drawingDate.month + "." + drawingDate.year
        val bodyText = Some(RenderHelper.getFromResourceRenderedWith("/mail/confirm.txt", Map("drawingDatePretty" -> drawingDatePretty)))
        val confirmationMessage = EMailMessage("Teilnahme an Verlosung: " + state.get.drawingEventName, email, state.get.tennantEMail, None, bodyText, smtpConfig, 1 minute, 3)
        EMailService.send(confirmationMessage)
      }

      if (isMatchingEvent) {
        persist(Subscribed(tennantID, year, eventID, email, ip, DateTime.now))(evt => {
          log.info(s"Subscribed $email for Drawing: $uniqueActorName")
          updateState(evt)
        })
        sendConfirmationMail()
      } else {
        log.debug(s"Subscribe for event: $eventID is ignored by: $persistenceId")
      }
    }
    case RemoveSubscription(iPCheckRecord) => {
      persist(SubscriptionRemoved(tennantID = iPCheckRecord.tennantID, tennantYear = iPCheckRecord.tennantYear, drawingEventID = iPCheckRecord.drawingEventID, clientIP = iPCheckRecord.clientIP))(evt => {
        log.info(s"All Subscriptions from IP: ${iPCheckRecord.clientIP.get} removed for drawing event: $uniqueActorName")
        updateState(evt)
      })
    }
    case DrawWinner => {
      log.info("(Scheduled) command DrawWinner recieved")
      val eventDate = state.get.drawingEventDate
      val eventID = state.get.drawingEventID

      val drawingDate = state.get.drawingEventDate - drawingDateDelta

      //This test is not necessary anymore with the new state "postDrawing", but remains here for safety
      if (isDrawingExecuted) {
        log.info(s"Drawing for $uniqueActorName and eventDate: $eventDate is already executed")
      } else {
        if (drawingDate <= DateTime.now) {
          log.info(s"Starting drawing for: $uniqueActorName and eventDate: $eventDate")

          val numberOfUniqueSubscriptions = uniqueSubscribtions.size
          if (uniqueSubscribtions.nonEmpty) {

            val theLUCKYNumber = Random.nextInt(numberOfUniqueSubscriptions)
            val theWinnerEMail = uniqueSubscribtions.keySet.toList(theLUCKYNumber)
            log.info(s"The Winner is: $theWinnerEMail. Unique subscriptions: $numberOfUniqueSubscriptions out of: ${state.get.totalSubscriptions}")

            persist(DrawWinnerExecuted(theWinnerEMail))(evt => {
              updateState(evt)
              context.become(postDrawing)
            })

            val smtpConfig = SmtpConfig(tls, ssl, port, host, user, password)
            val bodyText = Some(RenderHelper.getFromResourceRenderedWith("/mail/winner.txt", Map("linkToTicket" -> state.get.drawingLinkToTicket, "securityCodeForTicket" -> state.get.drawinSsecurityCodeForTicket)))
            val winnerMessage = EMailMessage("Sie haben gewonnen: 2 Tickets für: " + state.get.drawingEventName, theWinnerEMail, state.get.tennantEMail, None, bodyText, smtpConfig, 1 minute, 3)
            EMailService.send(winnerMessage)

            if (winnerMessageToTennantisActivated) {
              def assembleTennantMailText: Some[String] = {
                var subscriptionMailText = ""
                state.get.subscriptions.foreach { each =>
                  subscriptionMailText = subscriptionMailText + each.toString() + "<br>"
                }
                val tennantMailText = Some(s"${bodyText.get} <br> <br> Teilnahmen ($numberOfUniqueSubscriptions/${state.get.totalSubscriptions}) : <br> ${subscriptionMailText}")
                tennantMailText
              }

              val winnerMessageToTennant = EMailMessage("2 Tickets für: " + state.get.drawingEventName + " gehen an: " + theWinnerEMail, state.get.tennantEMail, state.get.tennantEMail, None, assembleTennantMailText, smtpConfig, 1 minute, 3)
              EMailService.send(winnerMessageToTennant)
            }

          } else {
            log.info(s"Drawing: $uniqueActorName and eventDate: $eventDate has NO subscriptions, that means no winner can be drawn...")
            updateState(DrawWinnerExecuted("N/A"))
            context.become(postDrawing)
          }
        } else {
          log.info(s"Drawing: $uniqueActorName and eventDate: $eventDate is not yet due")
        }
      }
    }

    // Queries
    case GetSubscribtions => {
      sender() ! state.get.subscriptions
    }
    case GetDrawingReport => {
      sender() ! DrawingReport(state.get.tennantID, state.get.tennantYear, state.get.drawingEventID, state.get.drawingEventDate, state.get.drawingEventName, state.get.drawingWinnerEMail.getOrElse("N/A"), uniqueSubscribtions.size, state.get.totalSubscriptions)
    }
    case msg => log.warning(s"Received unknown message for state receiveCommands - do nothing. Message is: $msg")
  }

  val postDrawing: Receive = {
    case GetSubscribtions => {
      sender() ! state.get.subscriptions
    }
    case GetDrawingReport => {
      sender() ! DrawingReport(state.get.tennantID, state.get.tennantYear, state.get.drawingEventID, state.get.drawingEventDate, state.get.drawingEventName, state.get.drawingWinnerEMail.getOrElse("N/A"), uniqueSubscribtions.size, state.get.totalSubscriptions)
    }
    //TODO For unknown reasons Subscribe events are recieved for events in postDrawing state - set to debug to minimize the noise in the log
    case msg => log.debug(s"$uniqueActorName - Received unknown message for state postDrawing - do nothing. Message is: $msg")
  }
}

