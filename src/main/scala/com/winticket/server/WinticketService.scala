package com.winticket.server

import java.io._

import akka.actor.{ActorSystem, Props}
import akka.http.scaladsl.Http
import akka.http.scaladsl.client.RequestBuilding
import akka.http.scaladsl.marshallers.xml.ScalaXmlSupport._
import akka.http.scaladsl.model.StatusCodes.{BadRequest, OK}
import akka.http.scaladsl.model._
import akka.http.scaladsl.model.headers.CacheDirectives.{`must-revalidate`, `no-cache`, `no-store`}
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.PathMatchers.IntNumber
import akka.http.scaladsl.server.directives.UserCredentials.{Missing, Provided}
import akka.http.scaladsl.unmarshalling.Unmarshal
import akka.pattern.ask
import akka.stream.io.SynchronousFileSink
import akka.stream.scaladsl.{Flow, Sink, Source}
import akka.util.Timeout
import com.github.marklister.collections.io.{CsvParser, GeneralConverter}
import com.winticket.core.BaseService
import com.winticket.server.DrawingActor._
import com.winticket.server.DrawingActorSupervisor.{CreateChild, DrawingReports, Subscribtions}
import com.winticket.util.{DataLoaderHelper, RenderHelper}

import scala.collection.JavaConverters._
import scala.concurrent.duration._
import scala.concurrent.{Await, Future}
import scala.util.{Failure, Success, Try}
import scala.xml.Elem

case class IpInfo(ip: String, country_code: Option[String], country_name: Option[String], region_code: Option[String], region_name: Option[String], city: Option[String], time_zone: Option[String], latitude: Option[Double], longitude: Option[Double], metro_code: Option[Int])

case class UserPass(username: String, password: String)

class TryIterator[T](it: Iterator[T]) extends Iterator[Try[T]] {
  def next = Try(it.next())

  def hasNext = it.hasNext
}

trait WinticketService extends BaseService {

  protected implicit val system = ActorSystem("winticket")

  val supervisor = system.actorOf(Props[DrawingActorSupervisor], name = "DrawingActorSupervisor")

  lazy val geoipConnectionFlow: Flow[HttpRequest, HttpResponse, Any] =
    //Use Connection-Level Client-Side API
    Http().outgoingConnection(geoipHost, geoipPort)

  def geoipRequest(request: HttpRequest): Future[HttpResponse] = Source.single(request).via(geoipConnectionFlow).runWith(Sink.head)

  def createDrawing(createDrawing: CreateDrawing): Unit = {
    supervisor ! CreateChild(createDrawing)

  }

  def getSubscriptions(): Elem = {
    implicit val timeout = Timeout(5 seconds)
    val future: Future[List[List[SubscriptionRecord]]] = ask(supervisor, Subscribtions).mapTo[List[List[SubscriptionRecord]]]
    val listOfList = Await.result(future, timeout.duration).asInstanceOf[List[List[SubscriptionRecord]]]
    <ul>{
      listOfList.map { eachList =>
        eachList.map { eachElement => <li>{ eachElement.toString() }</li>
        }
      }
    }</ul>
  }

  def getDrawingReports(): Elem = {
    <ul>{
      drawingReports().map { eachElement => <li>{ eachElement.toString() }</li>
      }
    }</ul>
  }

  private def drawingReports(): List[DrawingReport] = {
    implicit val timeout = Timeout(5 seconds)
    val future: Future[List[DrawingReport]] = ask(supervisor, DrawingReports).mapTo[List[DrawingReport]]
    Await.result(future, timeout.duration).asInstanceOf[List[DrawingReport]]
  }

  def isIPValid(clientIP: String): Boolean = {

    if (isCheck4SwissIPEnabled) {
      log.info("Client IP is: " + clientIP)
      if (clientIP == "127.0.0.1" || clientIP == "localhost") {
        true
      } else {
        val responseFuture = geoipRequest(RequestBuilding.Get(s"/json/$clientIP")).flatMap { response =>
          response.status match {
            case OK => {
              Unmarshal(response.entity).to[IpInfo].map {
                ipinfo =>
                  {
                    val countryString = ipinfo.country_name.getOrElse("N/A country from telize.com")
                    if (countryString == "Switzerland") {
                      log.info(s"Request with IP: ${ipinfo.ip} is from Switzerland. Proceed")
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
              val error = s"The request to the geoip service failed with status code ${response.status} and entity $entity"
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

    //A Map is required for the route. Convert the Java based listOfTennants...
    val tennantMap: Map[String, String] = tennantList.asScala.toList.map { case k => k -> k }.toMap

    pathPrefix(tennantMap / IntNumber / IntNumber) { (tennantID, tennantYear, drawingEventID) =>
      (get & path(Segment)) { commandORsubscriptionEMail =>

        extractClientIP { clientIP =>
          val clientIPString = clientIP.toOption.map(_.getHostAddress).getOrElse("N/A from request")
          if (isIPValid(clientIPString)) {

            commandORsubscriptionEMail match {
              case "subscribe" => {
                val drawingEventName = drawingReports.find(each => each.tennantID == tennantID && each.year == tennantYear && each.eventID == drawingEventID.toString()).getOrElse(DrawingReport()).drawingEventName
                val pathToConfirmationPage = s"/$tennantID/$tennantYear/$drawingEventID"
                val cacheHeaders = headers.`Cache-Control`(`no-cache`, `no-store`, `must-revalidate`)
                complete {
                  HttpResponse(
                    status = OK,
                    headers = List(cacheHeaders),
                    entity = HttpEntity(
                      MediaTypes.`text/html`,
                      RenderHelper.getFromResourceRenderedWith("/web/entry.html", Map("drawingEventName" -> drawingEventName, "pathToConfirmationPage" -> pathToConfirmationPage))
                    ))
                }

              }
              case _ => {

                // TOOD If a user subscribes for an event which is in postDrawing state a different confirmation page could be shown to user
                // Ask DrawingActor for State - or via isDrawingExecuted
                supervisor ! Subscribe(tennantID, tennantYear.toString, drawingEventID.toString, commandORsubscriptionEMail, clientIPString)

                complete {
                  HttpResponse(
                    status = OK,
                    entity = HttpEntity(
                      MediaTypes.`text/html`,
                      RenderHelper.getFromResourceRenderedWith("/web/confirm.html", Map("subscriptionEMail" -> commandORsubscriptionEMail))
                    ))
                }
              }
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
          command match {
            case "startDrawings" => {
              supervisor ! DrawWinner
              complete(HttpResponse(status = StatusCodes.OK, entity = "Drawings for all events started..."))
            }
            case "showReport" => {
              complete {
                <html>
                  <body>
                    DrawingReports:<br/>
                    { getDrawingReports() }
                    Subscriptions:
                    { getSubscriptions() }
                  </body>
                </html>
              }
            }
          }
        }
      }
    } ~ path("upload") {
      (post & extractRequest) {
        request =>
          {
            val source = request.entity.dataBytes
            val outFile = new File("/tmp/outfile.dat")
            val sink = SynchronousFileSink.create(outFile)
            val replyFuture = source.runWith(sink).map(x => s"Finished uploading ${x} bytes!")

            onSuccess(replyFuture) { replyMsg =>

              //Convert directly to akka DataTime. The failure case looks like this: Failure(java.lang.IllegalArgumentException: None.get at line x)
              implicit val DateConverter: GeneralConverter[DateTime] = new GeneralConverter(DateTime.fromIsoDateTimeString(_).get)

              val aListOfDrawingEventsTry = new TryIterator(CsvParser(CreateDrawing).iterator(DataLoaderHelper.readFromFile(outFile), hasHeader = true)).toList
              aListOfDrawingEventsTry.foreach {
                case Success(content) => createDrawing(content)
                case Failure(f)       => log.error(f.getMessage)
              }
              complete(HttpResponse(status = StatusCodes.OK, entity = replyMsg))
            }
          }
      }
      //All the static stuff
    } ~ path("")(getFromResource("")) ~ getFromResourceDirectory("web")
  }
}
