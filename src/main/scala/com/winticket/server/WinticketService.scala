package com.winticket.server

import java.io.IOException

import akka.actor.{ActorRef, ActorSystem}
import akka.http.scaladsl.Http
import akka.http.scaladsl.client.RequestBuilding
import akka.http.scaladsl.marshallers.xml.ScalaXmlSupport._
import akka.http.scaladsl.model.StatusCodes._
import akka.http.scaladsl.model.{HttpRequest, HttpResponse}
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.directives.UserCredentials.{Missing, Provided}
import akka.http.scaladsl.unmarshalling.Unmarshal
import akka.pattern.ask
import akka.stream.scaladsl.{Flow, Sink, Source}
import akka.util.Timeout
import com.winticket.core.BaseService
import com.winticket.server.DrawingActor._

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
                ipinfo =>
                  {
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
        //Use callbacks instead of
        //Await.result(responseFuture, 10 seconds)
        responseFuture onSuccess {
          case result => result
        }

        responseFuture onFailure {
          case t => log.error("An error has occured: " + t.getMessage)
        }
        false
      }
    } else {
      true
    }
  }

  def basicAuthenticator: Authenticator[UserPass] = {
    case missing@Missing => {
      log.info("Received UserCredentials is: " + missing + " challenge the browser to ask the user again...")
      None
    }
    case provided@Provided(_) => {
      log.info("Received UserCredentials is: " + provided)
      if (provided.username == adminUsername && provided.verifySecret(adminPassword)) {
        Some(UserPass("admin", ""))
      } else {
        None
      }
    }
  }

  val routes = logRequestResult("winticket") {
    //TODO Make tennant a variable in Config
    //TODO Make param email accessible via "parameter(s)"
    //TODO Param Extract tennantYear and drawingEventID as String
    pathPrefix(tennantID / IntNumber / IntNumber) { (tennantYear, drawingEventID) =>
      (get & path(Segment)) { subscriptionEMail =>
        extractClientIP { clientIP =>
          val clientIPString = clientIP.toOption.map(_.getHostAddress).getOrElse("N/A from request")
          if (isIPValid(clientIPString)) {

            aListOfDrawingActors.foreach(drawingActor => drawingActor ! Subscribe(tennantYear.toString, drawingEventID.toString, subscriptionEMail, clientIPString))

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
          } else {
            complete {
              //http://doc.akka.io/docs/akka-stream-and-http-experimental/1.0/scala/http/common/xml-support.html
              <html>
                <body>
                  <status>NOK - IP Check failed</status>
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
    }
  }
}
