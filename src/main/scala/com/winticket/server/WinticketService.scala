package com.winticket.server

import java.io._

import akka.actor.{ActorRef, ActorSystem}
import akka.http.scaladsl.Http
import akka.http.scaladsl.client.RequestBuilding
import akka.http.scaladsl.marshallers.xml.ScalaXmlSupport._
import akka.http.scaladsl.model.StatusCodes._
import akka.http.scaladsl.model.{HttpEntity, HttpRequest, HttpResponse, MediaTypes}
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.PathMatchers.IntNumber
import akka.http.scaladsl.server.directives.UserCredentials.{Missing, Provided}
import akka.http.scaladsl.unmarshalling.Unmarshal
import akka.pattern.ask
import akka.stream.scaladsl.{Flow, Sink, Source}
import akka.util.Timeout
import com.winticket.core.BaseService
import com.winticket.server.DrawingActor._
import com.winticket.util.RenderHelper

import scala.collection.JavaConverters._
import scala.collection.mutable.ListBuffer
import scala.concurrent.duration.DurationInt
import scala.concurrent.{Await, Future}


case class IpInfo(ip: String, country: Option[String], city: Option[String], latitude: Option[Double], longitude: Option[Double])

case class UserPass(username: String, password: String)


trait WinticketService extends BaseService {

  protected implicit val system = ActorSystem("winticket")

  protected var aListOfDrawingActors = ListBuffer[ActorRef]()

  lazy val telizeConnectionFlow: Flow[HttpRequest, HttpResponse, Any] =
  //Use Connection-Level Client-Side API
    Http().outgoingConnection(telizeHost, telizePort)

  def telizeRequest(request: HttpRequest): Future[HttpResponse] = Source.single(request).via(telizeConnectionFlow).runWith(Sink.head)

  def createDrawing(data: CreateDrawing): Unit = {
    val uniqueActorRef = "DrawingActor-" + data.tennantID + "-" + data.drawingEventID
    val drawingActor = system.actorOf(DrawingActor.props(uniqueActorRef), uniqueActorRef)
    drawingActor ! data
    log.info(s"------- DrawingActor -----> $data")
    aListOfDrawingActors += drawingActor
  }

  def logSubscriptions(): Unit = {
    implicit val timeout = Timeout(5 seconds)
    aListOfDrawingActors.foreach { drawingActor =>
      val subscriptionsFuture = drawingActor ? GetSubscribtions
      log.info(s"------- DrawingActor -----> $GetSubscribtions")
      val subscriptions = Await.result(subscriptionsFuture, timeout.duration)
      val size = subscriptions.asInstanceOf[List[String]].size
      log.info(s"<------ DrawingActor ------ Size: $size Data: $subscriptions")
    }
  }

  def logWinners(): Unit = {
    implicit val timeout = Timeout(5 seconds)
    aListOfDrawingActors.foreach { drawingActor =>
      val winnerFuture = drawingActor ? GetWinnerEMail
      log.info(s"------- DrawingActor -----> $GetWinnerEMail")
      val winnerEMail = Await.result(winnerFuture, timeout.duration)
      log.info(s"<------ DrawingActor ------ WinnerEMail: $winnerEMail")
    }
  }

  def isIPValid(clientIP: String): Boolean = {

    if (isCheck4SwissIPEnabled) {
      log.info("Client IP is: " + clientIP)
      if (clientIP == "127.0.0.1" || clientIP == "localhost") {
        true
      } else {
        val responseFuture = telizeRequest(RequestBuilding.Get(s"/geoip/$clientIP")).flatMap { response =>
          response.status match {
            case OK => {
              Unmarshal(response.entity).to[IpInfo].map {
                ipinfo => {
                  val countryString = ipinfo.country.getOrElse("N/A country from telize.com")
                  if (countryString == "Switzerland") {
                    log.info("Request is from Switzerland. Proceed")
                    Future.successful(true)
                  } else {
                    log.info("Request is not from Switzerland or N/A. Ignore. Country value: " + countryString)
                    Future.successful(false)
                  }
                }
              }
              Future.successful(false)

            }
            case BadRequest => Future.successful(false)
            case _ => Unmarshal(response.entity).to[String].flatMap { entity =>
              val error = s"The request to telize.com failed with status code ${response.status} and entity $entity"
              log.error(error)
              Future.failed(new IOException(error))
            }
          }
        }

        responseFuture.onSuccess {
          case result => result
        }
        responseFuture.onFailure {
          case t => log.error("An error has occurred: " + t.getMessage)
        }
        false
      }
    } else {
      true
    }
  }

  def basicAuthenticator: Authenticator[UserPass] = {
    case missing@Missing => {
      log.info(s"Received UserCredentials is: $missing challenge the browser to ask the user again")
      None
    }
    case provided@Provided(_) => {
      log.info(s"Received UserCredentials is: $provided")
      if (provided.username == adminUsername && provided.verifySecret(adminPassword)) {
        Some(UserPass("admin", ""))
      } else {
        None
      }
    }
  }


  val routes = logRequestResult("winticket") {

    //TODO Make param email accessible via "url parameter(s)"
    //TODO Param Extract tennantYear and drawingEventID as String

    //A Map is required for the route. Convert the Java based listOfTennants...
    val tennantMap: Map[String, String] = listOfTennants.asScala.toList.map { case k => k -> k }.toMap

    pathPrefix(tennantMap / IntNumber / IntNumber) { (tennantID, tennantYear, drawingEventID) =>

      (get & path(Segment)) { subscriptionEMail =>
        extractClientIP { clientIP =>
          val clientIPString = clientIP.toOption.map(_.getHostAddress).getOrElse("N/A from request")
          if (isIPValid(clientIPString)) {

            // TOOD Ask DrawingActor for State - or via isDrawingExecuted  and render html response to user when in postDrawing state
            aListOfDrawingActors.foreach(drawingActor => drawingActor ! Subscribe(tennantID, tennantYear.toString, drawingEventID.toString, subscriptionEMail, clientIPString))

            complete {
              HttpResponse(
                status = OK,
                entity = HttpEntity(
                  MediaTypes.`text/html`,
                  RenderHelper.getFromResourceRenderedWith("/web/confirm.html", Map("subscriptionEMail" -> subscriptionEMail))
                ))
            }
          } else {
            complete {
              //http://doc.akka.io/docs/akka-stream-and-http-experimental/1.0/scala/http/common/xml-support.html
              <html>
                <body>
                  <status>IP Check failed - your subscription was not accepted</status>
                </body>
              </html>
            }
          }
        }
      }

    } ~ pathPrefix("admin" / "cmd") {
      authenticateBasic(realm = "admin area", basicAuthenticator) { user =>

        log.info("User is: " + user.username)

        (get & path(Segment)) { command =>
          if (command == "startDrawings") aListOfDrawingActors.foreach(drawingActor => drawingActor ! DrawWinner)

          //Debug response
          complete {
            <html>
              <body>
                Command is:
                {command}
                User is:
                {user.username}
              </body>
            </html>
          }
        }
      }
      //All the static stuff
    } ~ path("")(getFromResource("")) ~ getFromResourceDirectory("web")
  }
}
