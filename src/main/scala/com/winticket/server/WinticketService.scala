package com.winticket.server

import java.io._

import akka.actor.{ActorSystem, Props}
import akka.http.scaladsl.Http
import akka.http.scaladsl.client.RequestBuilding
import akka.http.scaladsl.marshallers.xml.ScalaXmlSupport._
import akka.http.scaladsl.model.StatusCodes.{BadRequest, OK}
import akka.http.scaladsl.model._
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.PathMatchers.IntNumber
import akka.http.scaladsl.server.directives.UserCredentials.{Missing, Provided}
import akka.http.scaladsl.unmarshalling.Unmarshal
import akka.stream.io.SynchronousFileSink
import akka.stream.scaladsl.{Flow, Sink, Source}
import com.github.marklister.collections.io.{CsvParser, GeneralConverter}
import com.winticket.core.BaseService
import com.winticket.server.DrawingActor._
import com.winticket.server.DrawingActorSupervisor.{CreateChild, Subscribtions, Winners}
import com.winticket.util.{DataLoaderHelper, RenderHelper}

import scala.collection.JavaConverters._
import scala.concurrent.Future
import scala.util.{Failure, Success, Try}

case class IpInfo(ip: String, country: Option[String], city: Option[String], latitude: Option[Double], longitude: Option[Double])

case class UserPass(username: String, password: String)

class TryIterator[T](it: Iterator[T]) extends Iterator[Try[T]] {
  def next = Try(it.next())

  def hasNext = it.hasNext
}

trait WinticketService extends BaseService {

  protected implicit val system = ActorSystem("winticket")

  val supervisor = system.actorOf(Props[DrawingActorSupervisor], name = "DrawingActorSupervisor")

  lazy val telizeConnectionFlow: Flow[HttpRequest, HttpResponse, Any] =
    //Use Connection-Level Client-Side API
    Http().outgoingConnection(telizeHost, telizePort)

  def telizeRequest(request: HttpRequest): Future[HttpResponse] = Source.single(request).via(telizeConnectionFlow).runWith(Sink.head)

  def createDrawing(createDrawing: CreateDrawing): Unit = {
    supervisor ! CreateChild(createDrawing)

  }

  def logSubscriptions(): Unit = {
    supervisor ! Subscribtions
  }

  def logWinners(): Unit = {
    supervisor ! Winners
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
    case missing @ Missing => {
      log.info(s"Received UserCredentials is: $missing challenge the browser to ask the user again")
      None
    }
    case provided @ Provided(_) => {
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
            supervisor ! Subscribe(tennantID, tennantYear.toString, drawingEventID.toString, subscriptionEMail, clientIPString)

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
          if (command == "startDrawings") supervisor ! DrawWinner

          //Debug response
          complete {
            <html>
              <body>
                Command is:
                { command }
                User is:
                { user.username }
              </body>
            </html>
          }
        }
      }
    } ~ path("upload") {
      (post & extractRequest) {
        request =>
          {
            //Drawback: writes the file with the metadata...
            val source = request.entity.dataBytes
            val outFile = new File("/tmp/outfile.dat")
            val sink = SynchronousFileSink.create(outFile)
            val replyMessage = source.runWith(sink).map(x => s"Finished uploading ${x} bytes!")
            onSuccess(replyMessage) { repl =>

              //Convert directly to akka DataTime. The failure case looks like this: Failure(java.lang.IllegalArgumentException: None.get at line x)
              implicit val DateConverter: GeneralConverter[DateTime] = new GeneralConverter(DateTime.fromIsoDateTimeString(_).get)

              val aListOfDrawingEventsTry = new TryIterator(CsvParser(CreateDrawing).iterator(DataLoaderHelper.readFromFile(outFile), hasHeader = true)).toList
              aListOfDrawingEventsTry.foreach {
                case Success(content) => createDrawing(content)
                case Failure(f)       => log.error(f.getMessage)
              }
              complete(HttpResponse(status = StatusCodes.OK, entity = repl))
            }
          }
      }
      //All the static stuff
    } ~ path("")(getFromResource("")) ~ getFromResourceDirectory("web")
  }
}
