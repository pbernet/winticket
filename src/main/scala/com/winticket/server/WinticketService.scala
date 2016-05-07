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
import akka.http.scaladsl.server.StandardRoute
import akka.http.scaladsl.server.directives.Credentials.{Missing, Provided}
import akka.http.scaladsl.settings.ConnectionPoolSettings
import akka.http.scaladsl.unmarshalling.Unmarshal
import akka.pattern.ask
import akka.stream.scaladsl.{FileIO, Flow, Sink, Source}
import akka.util.Timeout
import com.github.marklister.collections.io.{CsvParser, GeneralConverter}
import com.typesafe.config.ConfigFactory
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

  private val emailRegex = """^[a-zA-Z0-9\.!#$%&'*+/=?^_`{|}~-]+@[a-zA-Z0-9](?:[a-zA-Z0-9-]{0,61}[a-zA-Z0-9])?(?:\.[a-zA-Z0-9](?:[a-zA-Z0-9-]{0,61}[a-zA-Z0-9])?)*$""".r

  protected implicit val system = ActorSystem("winticket")

  val supervisor = system.actorOf(Props[DrawingActorSupervisor], name = "DrawingActorSupervisor")

  lazy val geoipConnectionFlow: Flow[HttpRequest, HttpResponse, Any] =
    Http().outgoingConnection(geoipHost, geoipPort)

  //TODO Try to change form Connection-Level Client-Side API to Host-Level Client-Side API to set max-retries
  //http://doc.akka.io/docs/akka/2.4.4/scala/http/client-side/host-level.html

  //Feature: prevent Resource Starvation
  //http://kazuhiro.github.io/scala/akka/akka-http/akka-streams/2016/01/31/connection-pooling-with-akka-http-and-source-queue.html

  val config = ConfigFactory.load()
  val connectionPoolSettings = ConnectionPoolSettings(config)

  val geoipFlowViaConnectionPool = Http().cachedHostConnectionPool(geoipHost, geoipPort)

  val poolClientFlow = Http().cachedHostConnectionPool[Int]("akka.io")

  def geoipRequest(request: HttpRequest): Future[HttpResponse] = Source.single(request).via(geoipConnectionFlow).runWith(Sink.head)

  //TODO Does not compile anymore with newest version of akka-http
  //def geoipRequestPool(request: HttpRequest) = Source.single(request).via(geoipFlowViaConnectionPool).runWith(Sink.head)

  def createDrawing(createDrawing: CreateDrawing): Unit = {
    supervisor ! CreateChild(createDrawing)

  }

  def getSubscriptions(): Elem = {
    implicit val timeout = Timeout(5 seconds)
    val future: Future[List[List[SubscriptionRecord]]] = ask(supervisor, Subscribtions).mapTo[List[List[SubscriptionRecord]]]
    val listOfList = Await.result(future, timeout.duration)
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
    implicit val timeout = Timeout(10 seconds)
    val future: Future[List[DrawingReport]] = ask(supervisor, DrawingReports).mapTo[List[DrawingReport]]
    Await.result(future, timeout.duration)
  }

  def isIPValid(clientIP: String): Boolean = {

    //A sync check does not make sense because the geoip service is unreliable (Timeouts, service not available)
    //TODO Implement an async solution with a retry
    if (isCheck4SwissIPEnabled) {
      log.info("Client IP is: " + clientIP)
      if (clientIP == "127.0.0.1" || clientIP == "localhost") {
        true
      } else {
        val responseFuture: Future[Boolean] = geoipRequest(RequestBuilding.Get(s"/json/$clientIP")).flatMap { response =>
          response.status match {
            case OK => {
              Unmarshal(response.entity).to[IpInfo].map {
                ipinfo =>
                  {
                    val countryString = ipinfo.country_name.getOrElse("N/A country from geoip service")
                    if (countryString == "Switzerland") {
                      log.info(s"Request with IP: ${ipinfo.ip} is from Switzerland. Proceed")
                      true
                    } else {
                      log.info("Request is not from Switzerland or N/A. Ignore. Country value: " + countryString)
                      false
                    }
                  }
              }
            }
            case BadRequest => Future.successful(false)
            case _ => Unmarshal(response.entity).to[String].flatMap { entity =>
              val error = s"The request to the geoip service failed with status code ${response.status} and entity $entity"
              log.error(error)
              Future.failed(new IOException(error))
            }
          }
        }
        implicit val timeout = Timeout(10 seconds)
        log.info("Waiting for evaluation...")
        Await.result(responseFuture, timeout.duration)
      }
    } else {
      true
    }
  }

  def isEMailValid(e: String): Boolean = e match {
    case null                                          => false
    case e if e.trim.isEmpty                           => false
    case e if emailRegex.findFirstMatchIn(e).isDefined => true
    case _                                             => false
  }

  def basicAuthenticator: Authenticator[UserPass] = {
    case missing @ Missing => {
      log.info(s"Received UserCredentials is: $missing challenge the browser to ask the user again")
      None
    }
    case provided @ Provided(_) => {
      log.info(s"Received UserCredentials is: $provided")
      if (provided.identifier == adminUsername && provided.verify(adminPassword)) {
        Some(UserPass("admin", ""))
      } else {
        None
      }
    }
  }

  val routes = logRequestResult("winticket") {

    //A Map is required for the route. Convert the Java based listOfTennants...
    val tennantMap: Map[String, String] = tennantList.asScala.toList.map { case k => k -> k }.toMap

    def handleEmail(tennantID: String, tennantYear: Int, drawingEventID: Int, commandORsubscriptionEMail: String, clientIPString: String): StandardRoute = {
      if (isEMailValid(commandORsubscriptionEMail)) {

        // TOOD If a user subscribes for an event which is in postDrawing state a different confirmation page could be shown to user
        // Ask DrawingActor for State - or via isDrawingExecuted
        supervisor ! Subscribe(tennantID, tennantYear.toString, drawingEventID.toString, commandORsubscriptionEMail, clientIPString)

        val mediaTypeWithCharSet = MediaTypes.`text/html` withCharset HttpCharsets.`UTF-8`
        complete {
          HttpResponse(
            status = OK,
            entity = HttpEntity(
              mediaTypeWithCharSet,
              RenderHelper.getFromResourceRenderedWith("/web/confirm.html", Map("subscriptionEMail" -> commandORsubscriptionEMail))
            ))
        }
      } else {
        complete {
          <html>
            <body>
              <status>EMail Check failed - Please enter a valid EMail</status>
            </body>
          </html>
        }
      }
    }

    def handleCommand(tennantID: String, tennantYear: Int, drawingEventID: Int): StandardRoute = {
      val drawingEventName = drawingReports.find(each => each.tennantID == tennantID && each.year == tennantYear && each.eventID == drawingEventID.toString()).getOrElse(DrawingReport()).drawingEventName
      val pathToConfirmationPage = s"/$tennantID/$tennantYear/$drawingEventID"
      val cacheHeaders = headers.`Cache-Control`(`no-cache`, `no-store`, `must-revalidate`)
      val mediaTypeWithCharSet = MediaTypes.`text/html` withCharset HttpCharsets.`UTF-8`
      complete {
        HttpResponse(
          status = OK,
          headers = List(cacheHeaders),
          entity = HttpEntity(
            mediaTypeWithCharSet,
            RenderHelper.getFromResourceRenderedWith("/web/entry.html", Map("drawingEventName" -> drawingEventName, "pathToConfirmationPage" -> pathToConfirmationPage))
          ))
      }
    }

    pathPrefix(tennantMap / IntNumber / IntNumber) { (tennantID, tennantYear, drawingEventID) =>
      (get & path(Segment)) { commandORsubscriptionEMail =>

        extractClientIP { clientIP =>
          val clientIPString = clientIP.toOption.map(_.getHostAddress).getOrElse("N/A from request")
          if (isIPValid(clientIPString)) {

            commandORsubscriptionEMail match {
              case "subscribe" => handleCommand(tennantID, tennantYear, drawingEventID)
              case _           => handleEmail(tennantID, tennantYear, drawingEventID, commandORsubscriptionEMail, clientIPString)
            }
          } else {
            complete {
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
            val sink = FileIO.toFile(outFile)
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
