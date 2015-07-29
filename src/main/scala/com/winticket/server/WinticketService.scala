package com.winticket.server

import akka.actor.ActorSystem
import akka.event.{ Logging, LoggingAdapter }
import akka.http.scaladsl.marshallers.xml.ScalaXmlSupport._
import akka.http.scaladsl.model.DateTime
import akka.http.scaladsl.server.Directives._
import akka.pattern.ask
import akka.stream.ActorMaterializer
import akka.util.Timeout
import com.winticket.core.BaseService
import com.winticket.server.DrawingActor.{ CreateDrawing, GetSubscribtions, Subscribe }

import scala.concurrent.{ ExecutionContext, Await }
import scala.concurrent.duration.DurationInt

trait WinticketService extends BaseService {

  protected implicit val system = ActorSystem("winticket")
  protected val drawingActor = system.actorOf(DrawingActor.props("gruenfels"), "drawingActorGruenfels")

  def addStammdata: Unit = {
    val drawingEventDateGMT = DateTime(2015, 9, 12, 20, 0, 0)
    val linkToTicket = "http://tkpk.ch/twc/ZmN2AakhLajkZQN4BGy8"
    val securityCodeForTicket = "8ded"
    val create = CreateDrawing("gruenfels", 2015, "info@gruenfels.ch", "50", "Schertenleib & Jegerlehner", drawingEventDateGMT, linkToTicket, securityCodeForTicket)
    drawingActor ! create
    log.info(s"------- DrawingActor -----> $create")
  }

  def printSubscriptions: Unit = {
    implicit val timeout = Timeout(5 seconds)
    val subscriptionsFuture = drawingActor ? GetSubscribtions
    log.info(s"------- DrawingActor -----> $GetSubscribtions")
    val subscriptions = Await.result(subscriptionsFuture, timeout.duration)
    val size = subscriptions.asInstanceOf[List[String]].size
    log.info(s"<------ DrawingActor ------ Size: $size Data: $subscriptions")
  }

  val routes = logRequestResult("winticket") {
    //TODO Make tennant a variable in Config
    //TODO Make email accessible via "parameter(s)"
    pathPrefix("gruenfels" / IntNumber / IntNumber) { (tennantYear, drawingEventID) =>
      (get & path(Segment)) { subscriptionEMail =>
        extractClientIP { clientIP =>
          //TODO Test IP for correctness
          drawingActor ! Subscribe(tennantYear, drawingEventID, subscriptionEMail, clientIP.toOption.map(_.getHostAddress).getOrElse("unknown"))

          complete {
            //http://doc.akka.io/docs/akka-stream-and-http-experimental/1.0/scala/http/common/xml-support.html
            <html>
              <body>
                <status>OK</status>
                <tennantYear>
                  { tennantYear }
                </tennantYear>
                <drawingEventID>
                  { drawingEventID }
                </drawingEventID>
                <subscriptionEMail>
                  { subscriptionEMail }
                </subscriptionEMail>
              </body>
            </html>
          }
        }
      }
    } ~
      //TODO remove since this is for testing
      pathPrefix("xx") {

        (get & path(Segment)) { value =>

          //Additional Test
          complete {
            //http://doc.akka.io/docs/akka-stream-and-http-experimental/1.0/scala/http/common/xml-support.html
            <html>
              <body>
                Hello world! IP:
                { value }
              </body>
            </html>
          }
        }

      } ~ extractClientIP { ip =>
        complete("Client's ip is " + ip.toOption.map(_.getHostAddress).getOrElse("unknown"))
      }
  }
}
