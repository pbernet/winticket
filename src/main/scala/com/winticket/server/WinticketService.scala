package com.winticket.server

import akka.actor.{ ActorRef, ActorSystem }
import akka.event.{ Logging, LoggingAdapter }
import akka.http.scaladsl.marshallers.xml.ScalaXmlSupport._
import akka.http.scaladsl.model.DateTime
import akka.http.scaladsl.server.Directives._
import akka.pattern.ask
import akka.stream.ActorMaterializer
import akka.util.Timeout
import com.winticket.core.BaseService
import com.winticket.server.DrawingActor.{ GetWinnerEMail, CreateDrawing, GetSubscribtions, Subscribe }
import com.winticket.server.DrawingProtocol.DrawWinnerExecuted

import scala.collection.mutable.ListBuffer
import scala.concurrent.{ ExecutionContext, Await }
import scala.concurrent.duration.DurationInt

trait WinticketService extends BaseService {

  protected implicit val system = ActorSystem("winticket")

  protected var aListOfDrawingActors = ListBuffer[ActorRef]()

  //TODO generalize for other tennants via passing the tennantID
  def createDrawingGruenfels(create: CreateDrawing): Unit = {
    val uniqueActorRef = "DrawingActorGruenfels" + create.drawingEventID
    val drawingActor = system.actorOf(DrawingActor.props(uniqueActorRef), uniqueActorRef)
    drawingActor ! create
    log.info(s"------- DrawingActor -----> $create")
    aListOfDrawingActors += drawingActor
  }

  def logSubscriptions: Unit = {
    implicit val timeout = Timeout(5 seconds)
    aListOfDrawingActors.foreach { drawingActor =>
      val subscriptionsFuture = drawingActor ? GetSubscribtions
      log.info(s"------- DrawingActor -----> $GetSubscribtions")
      val subscriptions = Await.result(subscriptionsFuture, timeout.duration)
      val size = subscriptions.asInstanceOf[List[String]].size
      log.info(s"<------ DrawingActor ------ Size: $size Data: $subscriptions")
    }
  }

  def logWinners: Unit = {
    implicit val timeout = Timeout(5 seconds)
    aListOfDrawingActors.foreach { drawingActor =>
      val winnerFuture = drawingActor ? GetWinnerEMail
      log.info(s"------- DrawingActor -----> $GetWinnerEMail")
      val winnerEMail = Await.result(winnerFuture, timeout.duration)
      log.info(s"<------ DrawingActor ------ WinnerEMail: $winnerEMail")
    }
  }

  val routes = logRequestResult("winticket") {
    //TODO Make tennant a variable in Config
    //TODO Make param email accessible via "parameter(s)"
    //TODO Param Extract tennantYear and drawingEventID as String
    pathPrefix(tennantID / IntNumber / IntNumber) { (tennantYear, drawingEventID) =>
      (get & path(Segment)) { subscriptionEMail =>
        extractClientIP { clientIP =>
          //TODO Test IP for correctness
          aListOfDrawingActors.foreach(drawingActor => drawingActor ! Subscribe(tennantYear.toString, drawingEventID.toString, subscriptionEMail, clientIP.toOption.map(_.getHostAddress).getOrElse("unknown")))

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
