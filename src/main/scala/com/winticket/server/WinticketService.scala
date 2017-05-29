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
import akka.stream.scaladsl.FileIO
import com.github.marklister.collections.io.{CsvParser, GeneralConverter}
import com.typesafe.config.{Config, ConfigFactory}
import com.winticket.core.BaseService
import com.winticket.server.DrawingActor._
import com.winticket.util.{DataLoaderHelper, RenderHelper}

import scala.collection.JavaConverters._
import scala.util.{Failure, Success, Try}
import scala.xml.Elem

case class IpInfo(ip: String, country_code: Option[String], country_name: Option[String], region_code: Option[String], region_name: Option[String], city: Option[String], time_zone: Option[String], latitude: Option[Double], longitude: Option[Double], metro_code: Option[Int])

case class UserPass(username: String, password: String)

class TryIterator[T](it: Iterator[T]) extends Iterator[Try[T]] {
  def next = Try(it.next())

  def hasNext = it.hasNext
}

trait WinticketService extends BaseService with DrawingAPI {

  private val emailRegex = """^[a-zA-Z0-9\.!#$%&'*+/=?^_`{|}~-]+@[a-zA-Z0-9](?:[a-zA-Z0-9-]{0,61}[a-zA-Z0-9])?(?:\.[a-zA-Z0-9](?:[a-zA-Z0-9-]{0,61}[a-zA-Z0-9])?)*$""".r

  protected implicit val system = ActorSystem("winticket")

  val config: Config = ConfigFactory.load()

  //A Map is required for the route, convert the Java based tennantList from the config
  val tennantMap: Map[String, String] = tennantList.asScala.toList.map(k => k -> k).toMap

  def createSupervisor(): ActorRef = system.actorOf(Props[DrawingActorSupervisor], name = "DrawingActorSupervisor")

  def createGeoIPChecker(): ActorRef = system.actorOf(Props(new GeoIPCheckerActor(supervisor)), name = "GeoIPCheckerActor")

  def getSubscriptions: Elem = {
    <ul>
      {
        askForSubscriptions.map { eachList =>
          eachList.map { eachElement =>
            <li>
              { eachElement.toString() }
            </li>
          }
        }
      }
    </ul>
  }

  def getDrawingReports: Elem = {
    <ul>
      {
        askForDrawingReports.map { eachElement =>
          <li>
            { eachElement.toString() }
          </li>
        }
      }
    </ul>
  }

  private def isEMailValid(e: String): Boolean = e match {
    case null                                            => false
    case `e` if e.trim.isEmpty                           => false
    case `e` if emailRegex.findFirstMatchIn(e).isDefined => true
    case _                                               => false
  }

  private def basicAuthenticator: Authenticator[UserPass] = {
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

  private def processSubscription(tennantID: String, tennantYear: Int, drawingEventID: Int, commandORsubscriptionEMail: String, clientIP: Option[String]): StandardRoute = {
    if (isEMailValid(commandORsubscriptionEMail)) {

      subscribe(tennantID, tennantYear, drawingEventID, commandORsubscriptionEMail, clientIP)

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

  private def handleSubscribe(tennantID: String, tennantYear: Int, drawingEventID: Int): StandardRoute = {
    val drawingEventName = askForDrawingReports.find(each => each.tennantID == tennantID && each.year == tennantYear && each.eventID == drawingEventID.toString()).getOrElse(DrawingReport()).drawingEventName
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

  def routes: Route = logRequestResult("winticket") {
    subscriptionRoute ~ adminRoute ~ uploadRoute ~ publicResourcesRoute
  }

  private def subscriptionRoute = pathPrefix(tennantMap / IntNumber / IntNumber) { (tennantID, tennantYear, drawingEventID) =>
    (get & path(Segment)) { commandORsubscriptionEMail =>

      extractClientIP { clientIP =>
        val clientIPOption = clientIP.toOption.map(_.getHostAddress)
        commandORsubscriptionEMail match {
          case "subscribe" => handleSubscribe(tennantID, tennantYear, drawingEventID)
          case _           => processSubscription(tennantID, tennantYear, drawingEventID, commandORsubscriptionEMail, clientIPOption)
        }
      }
    }

  }

  private def adminRoute = pathPrefix("admin" / "cmd") {
    authenticateBasic(realm = "admin area", basicAuthenticator) { user =>

      log.info("User is: " + user.username)

      (get & path(Segment)) {
        case "startDrawings" => {
          supervisor ! DrawWinner
          complete(HttpResponse(status = StatusCodes.OK, entity = "Drawings for all events started..."))
        }
        case "showReport" => {
          complete {
            <html>
              <body>
                DrawingReports:
                <br/>{ getDrawingReports }
                Subscriptions:
                { getSubscriptions }
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

  /**
    * Upload the content to a file and then parse the CSV via the "product-collections" lib
    * This approach allows for easier conversion CSV -> CreateDrawing at the cost of having the data in memory
    */
  private def uploadRoute = path("uploaddata") {
    (post & extractRequest) {
      request =>
        {
          val source = request.entity.dataBytes
          val outFile: Path = Paths.get("/tmp/outfile.dat")
          val sink = FileIO.toPath(outFile)
          val replyFuture = source.runWith(sink).map(x => s"Finished uploading $x bytes!")

          onSuccess(replyFuture) { replyMsg =>

            //Convert directly to akka DataTime. The failure case looks like this: Failure(java.lang.IllegalArgumentException: None.get at line x)
            implicit val DateConverter: GeneralConverter[DateTime] = new GeneralConverter(DateTime.fromIsoDateTimeString(_).get)

            val aListOfDrawingEventsTry = new TryIterator(CsvParser(CreateDrawing).iterator(DataLoaderHelper.readFromFile(outFile.toFile), hasHeader = true)).toList
            val convertStatus = aListOfDrawingEventsTry.map {
              case Success(content) => createDrawing(content); s"\nOK ${content.drawingEventID} - ${content.drawingEventName}"
              case Failure(f)       => log.error(f.getMessage); s"\nERROR while converting: ${f.getMessage}"
            }
            complete(HttpResponse(status = StatusCodes.OK, entity = convertStatus.mkString("\n")))
          }
        }
    }
  }

  private def publicResourcesRoute = getFromResourceDirectory("web")
}
