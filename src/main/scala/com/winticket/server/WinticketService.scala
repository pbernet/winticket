package com.winticket.server

import java.nio.file.{Path, Paths}

import akka.actor.{ActorRef, ActorSystem, Props}
import akka.http.scaladsl.marshallers.xml.ScalaXmlSupport._
import akka.http.scaladsl.model.StatusCodes.OK
import akka.http.scaladsl.model._
import akka.http.scaladsl.model.headers.CacheDirectives.{`must-revalidate`, `no-cache`, `no-store`}
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.PathMatchers.IntNumber
import akka.http.scaladsl.server.directives.Credentials.{Missing, Provided}
import akka.http.scaladsl.server.{Route, StandardRoute}
import akka.pattern.ask
import akka.stream.scaladsl.FileIO
import akka.util.Timeout
import com.github.marklister.collections.io.{CsvParser, GeneralConverter}
import com.typesafe.config.{Config, ConfigFactory}
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

  val supervisor: ActorRef = system.actorOf(Props[DrawingActorSupervisor], name = "DrawingActorSupervisor")

  val geoIPCheckerActor: ActorRef = system.actorOf(Props(new GeoIPCheckerActor(supervisor)), name = "GeoIPCheckerActor")

  val config: Config = ConfigFactory.load()

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

  def isEMailValid(e: String): Boolean = e match {
    case null                                            => false
    case `e` if e.trim.isEmpty                           => false
    case `e` if emailRegex.findFirstMatchIn(e).isDefined => true
    case _                                               => false
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

  val routes: Route = logRequestResult("winticket") {

    //A Map is required for the route, convert the Java based tennantList from the config
    val tennantMap: Map[String, String] = tennantList.asScala.toList.map(k => k -> k).toMap

    def processSubscription(tennantID: String, tennantYear: Int, drawingEventID: Int, commandORsubscriptionEMail: String, clientIP: Option[String]): StandardRoute = {
      if (isEMailValid(commandORsubscriptionEMail)) {

        // TOOD With this approach geoIP checks are made for these corner cases:
        // - Subscription for an event which is already in postDrawing state. A different confirmation page (= sorry drawing done) could be shown to user
        // - Subscription for an event, which does not exist
        supervisor ! Subscribe(tennantID, tennantYear.toString, drawingEventID.toString, commandORsubscriptionEMail, clientIP.getOrElse("N/A from request"))
        geoIPCheckerActor ! GeoIPCheckerActor.AddIPCheckRecord(tennantID, tennantYear, drawingEventID, clientIP)

        val mediaTypeWithCharSet = MediaTypes.`text/html` withCharset HttpCharsets.`UTF-8`
        complete {
          HttpResponse(
            status = OK,
            entity = HttpEntity(
              mediaTypeWithCharSet,
              RenderHelper.getFromResourceRenderedWith("/web/confirm.html", Map("subscriptionEMail" -> commandORsubscriptionEMail))
            )
          )
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

    def handleSubscribe(tennantID: String, tennantYear: Int, drawingEventID: Int): StandardRoute = {
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
          )
        )
      }
    }

    pathPrefix(tennantMap / IntNumber / IntNumber) { (tennantID, tennantYear, drawingEventID) =>
      (get & path(Segment)) { commandORsubscriptionEMail =>

        extractClientIP { clientIP =>
          val clientIPOption = clientIP.toOption.map(_.getHostAddress)
          commandORsubscriptionEMail match {
            case "subscribe" => handleSubscribe(tennantID, tennantYear, drawingEventID)
            case _           => processSubscription(tennantID, tennantYear, drawingEventID, commandORsubscriptionEMail, clientIPOption)
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
            case "fileUpload" => {
              getFromResource("admin/fileupload.html")
            }
          }
        }
      }
    } ~ path("uploaddata") {
      (post & extractRequest) {
        request =>
          {
            val source = request.entity.dataBytes
            val outFile: Path = Paths.get("/tmp/outfile.dat")
            val sink = FileIO.toPath(outFile)
            val replyFuture = source.runWith(sink).map(x => s"Finished uploading ${x} bytes!")

            onSuccess(replyFuture) { replyMsg =>

              //Convert directly to akka DataTime. The failure case looks like this: Failure(java.lang.IllegalArgumentException: None.get at line x)
              implicit val DateConverter: GeneralConverter[DateTime] = new GeneralConverter(DateTime.fromIsoDateTimeString(_).get)

              val aListOfDrawingEventsTry = new TryIterator(CsvParser(CreateDrawing).iterator(DataLoaderHelper.readFromFile(outFile.toFile), hasHeader = true)).toList
              aListOfDrawingEventsTry.foreach {
                case Success(content) => createDrawing(content)
                case Failure(f)       => log.error(f.getMessage)
              }
              complete(HttpResponse(status = StatusCodes.OK, entity = replyMsg))
            }
          }
      }
      //Public resources and admin js resources
    } ~ getFromResourceDirectory("web")
  }
}
