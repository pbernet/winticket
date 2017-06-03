package com.winticket.server

import akka.actor.Status.Failure
import akka.actor.{ActorLogging, ActorRef}
import akka.http.scaladsl.client.RequestBuilding
import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport
import akka.http.scaladsl.model._
import akka.http.scaladsl.settings.ConnectionPoolSettings
import akka.http.scaladsl.unmarshalling.Unmarshal
import akka.persistence.{PersistentActor, RecoveryCompleted}
import akka.stream.{ActorMaterializer, ActorMaterializerSettings}
import com.typesafe.config.ConfigFactory
import com.winticket.core.{Config, Protocol}
import com.winticket.server.DrawingActorSupervisor.RemoveSubscription
import com.winticket.server.DrawingProtocol._
import com.winticket.server.GeoIPCheckerActor.{GeoIPCheckerState, IPCheckRecord, RemoveIPCheckRecord}

object GeoIPCheckerActor {
  sealed trait Command
  case object Check extends Command
  case class AddIPCheckRecord(tennantID: String, tennantYear: Int, drawingEventID: Int, clientIP: Option[String]) extends Command
  case class RemoveIPCheckRecord(clientIP: Option[String]) extends Command

  case class GeoIPCheckerState(todoList: Seq[IPCheckRecord] = Nil) {
    def updated(evt: IPCheckEvent): GeoIPCheckerState = evt match {
      case IPCheckRecordAdded(tennantID, tennantYear, drawingEventID, clientIP: Option[String]) => copy(todoList = IPCheckRecord(tennantID, tennantYear, drawingEventID, clientIP) +: todoList)
      case IPCheckRecordRemoved(clientIP: Option[String])                                       => copy(todoList = todoList.filterNot(_.clientIP == clientIP))
      case _                                                                                    => this
    }
    def totalTodos = todoList.length
    def itemFor(ipOption: Option[String]) = todoList.find(_.clientIP == ipOption)
  }

  case class IPCheckRecord(tennantID: String = "", tennantYear: Int = 0, drawingEventID: Int = 0, clientIP: Option[String]) {
    override def toString = s"$tennantID-$tennantYear-$drawingEventID ${clientIP.getOrElse("N/A")}"
  }
}

/**
 * The GeoIPCheckerActor is an async way of detecting "unwanted subscriptions"
 * - periodically checks via an external geoip service
 * - notifies the DrawingActor (via the supervisor) to remove subscriptions
 *
 * Note that this implementation has no real business value nor does it provide better security
 * It just how to use a akka-http client to access an external service
 * Activate it in application.conf with: isCheck4SwissIPEnabled = true
 */
class GeoIPCheckerActor(drawingActorSupervisor: ActorRef) extends PersistentActor with Protocol with SprayJsonSupport with ActorLogging with Config {

  import akka.pattern.pipe
  import context.dispatcher

  val check = context.system.scheduler.schedule(initialDelayGeoIPCheck, intervalGeoIPCheck, self, GeoIPCheckerActor.Check)

  var state: Option[GeoIPCheckerState] = Some(GeoIPCheckerState())

  def updateState(evt: IPCheckEvent) = state = state.map(_.updated(evt))

  override def postStop() = check.cancel()

  override def persistenceId: String = "GeoIPCheckerActor"

  final implicit val materializer: ActorMaterializer = ActorMaterializer(ActorMaterializerSettings(context.system))

  private val config = ConfigFactory.load()
  private val restClient = RestClient(geoipHost, geoipPort, ConnectionPoolSettings(config))(context.system, materializer, log)

  val receiveRecover: Receive = {
    case evt: IPCheckRecordAdded =>
      log.info("RECOVER CheckItAdded: " + evt); updateState(evt)
    case evt: IPCheckRecordRemoved =>
      log.info("RECOVER Removed: " + evt); updateState(evt)
    case RecoveryCompleted => log.info("RecoveryCompleted")
    case msg               => log.warning(s"Received unknown message for state receiveRecover - do nothing. Message is: $msg")
  }

  val receiveCommand: Receive = {
    case checkit @ GeoIPCheckerActor.AddIPCheckRecord(tennantID, tennantYear, drawingEventID, clientIP) => {
      persist(DrawingProtocol.IPCheckRecordAdded(tennantID, tennantYear, drawingEventID, clientIP)) { evt =>
        log.info(s"Recieved cmd: $checkit")
        updateState(evt)
      }
    }
    case removeIt @ GeoIPCheckerActor.RemoveIPCheckRecord(clientIP) => {
      persist(DrawingProtocol.IPCheckRecordRemoved(clientIP)) { evt =>
        log.info(s"Recieved cmd: $removeIt")
        updateState(evt)
      }
    }
    case GeoIPCheckerActor.Check => {
      if (isCheck4SwissIPEnabled) {
        log.info(s"Check4SwissIP is enabled. Size of todo list: ${state.get.totalTodos}")
        state.get.todoList.foreach { each =>
          val clientIP = each.clientIP.getOrElse("N/A")
          log.info("Starting IP Check for: " + each.clientIP.getOrElse("N/A"))
          if (clientIP == "127.0.0.1" || clientIP == "localhost" || clientIP == "0:0:0:0:0:0:0:1") {
            log.debug("No external IP check, because IP is localhost")
            self ! RemoveIPCheckRecord(clientIP = Some(clientIP))
          } else {
            val httpRequest = RequestBuilding.Get(s"/json/$clientIP")
            restClient.execTyped[IPCheckRecord](httpRequest, each).pipeTo(self)
          }
        }
      }
    }

    case ResultWrapper(HttpResponse(StatusCodes.OK, _, entity, _), context: IPCheckRecord) =>
      log.info(s"Got ResultWrapper with HttpResponse with code: ${StatusCodes.OK} and event context: ${context.toString()}")
      Unmarshal(entity).to[IpInfo].map {
        ipinfo =>
          {
            val countryName = ipinfo.country_name.getOrElse("N/A")
            if (countryName == "Switzerland") {
              log.info(s"Request with IP: ${ipinfo.ip} is from Switzerland. Proceed")
            } else {
              log.info(s"Request with IP: ${ipinfo.ip} is not from Switzerland (countryName value: $countryName). Try to remove ALL subscriptions with this IP for drawing event/IP: ${context.toString()}")
              drawingActorSupervisor ! RemoveSubscription(context.copy(clientIP = Some(ipinfo.ip)))
            }
          }
          self ! RemoveIPCheckRecord(clientIP = Some(ipinfo.ip))
      }

    case ResultWrapper(HttpResponse(code, _, _, _), _) => log.error(s"The request to the geoip service failed with HTTP status code: $code. Retry on next run.")
    case Failure(cause)                                => log.error(s"The geoip service could not be reached. Retry on next run. Possibly a network or configuration problem. Details: $cause")
    case msg                                           => log.warning(s"Received unknown message - do nothing. Message is: $msg")
  }
}