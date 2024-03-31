package com.winticket.tools.maleorang

import java.io.IOException
import java.nio.file.Paths
import java.time.{LocalDateTime, ZoneId, ZoneOffset}
import java.time.format.DateTimeFormatter
import java.util.Locale
import akka.Done
import akka.actor.ActorSystem
import akka.http.scaladsl.Http
import akka.http.scaladsl.model.headers.RawHeader
import akka.http.scaladsl.model.{HttpEntity, HttpMethods, HttpRequest, MediaTypes}
import akka.stream.scaladsl.{FileIO, Framing, Sink}
import akka.stream.{ActorMaterializer, ThrottleMode}
import akka.util.ByteString
import com.ecwid.maleorang.MailchimpClient
import com.ecwid.maleorang.method.v3_0.campaigns.{CampaignActionMethod, CampaignInfo, EditCampaignMethod}
import com.winticket.util.RenderHelper
import io.circe.Json
import io.circe.parser.parse
import org.apache.commons.text.StringEscapeUtils

import scala.concurrent.{Await, Future}
import scala.concurrent.duration._
import scala.util.{Failure, Success}

/**
 * Bulk create campaigns via the MailChimp REST API:
 *  - Local: Render local master template with values from csv
 *  - API: Create new mailchimp template
 *  - API: Create new campaign by replicating existing campaign and assign the created template
 *  - API: Schedule the campaign
 *
 * Remarks:
 *  - The master HTML template is maintained (and rendered) locally
 *  - For each run a new template as well as a new campaign is created on Mailchimp
 *  - Constraints of this approach:
 *    - NOT possible to edit campaign in Mailchimp WISIWIG editor
 *    - certain event values eg xx-yy-knuth do not template render correctly
 *
 */
object PrepareCampaigns extends App {
  implicit val system = ActorSystem()
  implicit val ec = system.dispatcher
  implicit val materializer = ActorMaterializer()
  implicit val http = Http(system)

  val apiKey = "***"
  // We are using an old CampaignId, because we only override the metadata
  // Note that the CampaignId is now hidden from the GUI user, wtf
  // https://us13.admin.mailchimp.com/campaigns/show?id=1062881
  val masterCampaignId = "***"

  val pathToMasterTemplate = "/template/GRUENFELS_TEMPLATE_NEW_DESIGN.html"

  // Make sure to include a CR after the last line, to include this line in the processing as well
  val pathToCSV = "/Users/Shared/projects/winticket/data/events_production_2024.csv"

  val client = new MailchimpClient(apiKey)

  def toEventRecord(cols: Array[String]) = {
    if (cols(0) != "year") {
      val record = EventRecord(cols(0), cols(1), cols(2), cols(3), cols(4), cols(5), cols(6))
      println(s"Parsed EventRecord: ${record.toString}")
      record
    } else {
      // do nothing because it is the 1st line in the csv with the heading
      EventRecord()
    }
  }

  def prepareTemplate(record: EventRecord): (Integer, EventRecord) = {

    if (!record.isHeading) {
      println(s"About to create new template for event: ${record.eventID}...")

      // Load and render template locally
      val renderedTemplate = RenderHelper.getFromResourceRenderedWith(pathToMasterTemplate, record.toValueMap)
      val renderedTemplateEscaped = StringEscapeUtils.escapeJson(renderedTemplate)

      val jsonPayload = s"""{"name":"${record.name}","folder_id":"","html":"$renderedTemplateEscaped"}"""
      val entity = HttpEntity(MediaTypes.`application/json`, jsonPayload)

      // Post it via API
      val result = http.singleRequest(HttpRequest(HttpMethods.POST, uri = "https://us13.api.mailchimp.com/3.0/templates", entity = entity)
        .withHeaders(List(RawHeader("Authorization", s"Bearer $apiKey"))))

      // Parse response
      val json = result.flatMap(response => response.entity.toStrict(2.seconds))
        .map(_.data.utf8String)
      val cursor = parse(Await.result(json, 10.seconds)).getOrElse(Json.Null).hcursor
      val templateId: Integer = cursor.get[Integer]("id").toOption.getOrElse(0)
      if (templateId == 0) {
        println(s"FAILED to create new template. Probably local template rendering failed because of malformed content")
      } else {
        println(s"Successfully created new template with templateID: $templateId")
      }

      (templateId, record)
    } else {
      (0, record)
    }
  }

  def prepareCampaign(tuple: (Integer, EventRecord)): Unit = {
    val record = tuple._2
    val templateId = tuple._1

    if (!record.isHeading) {
      println(s"About to create campaign for eventID: ${record.eventID}")
      val replicateMethod = new CampaignActionMethod.Replicate(masterCampaignId)
      val replicateResponse: CampaignInfo = client.execute(replicateMethod)
      val newCampaignId = replicateResponse.id
      println(s"Created new campaign with ID: $newCampaignId and content_type: ${replicateResponse.content_type}")

      val localDate = LocalDateTime.parse(record.eventDate)


      // Edit Campaign
      val editMethod = new EditCampaignMethod.Update(newCampaignId)
      val deLocale = new Locale("de", "DE")
      val formatter = DateTimeFormatter.ofPattern("EEEE, dd. MMMM HH.mm", deLocale)
      val formattedDate = localDate.format(formatter)
      val subject = s"${record.eventArtist} am $formattedDate"
      val editableSettings = replicateResponse.settings
      editableSettings.subject_line = subject
      editableSettings.title = record.name
      editableSettings.template_id = templateId
      editMethod.settings = editableSettings
      val editCampaignMethodResponse = client.execute(editMethod)
      println(s"Updated campaign with campaignID: $newCampaignId, new templateID: $templateId, eventID: ${record.eventID} and content_type: ${editCampaignMethodResponse.content_type}")

      // Schedule Campaign, use UTC TimeZone for now
      val scheduleMethod = new CampaignActionMethod.Schedule(newCampaignId)
      val scheduleTime = java.util.Date.from(localDate.minusDays(14).minusHours(2L).toInstant(ZoneOffset.UTC))
      scheduleMethod.schedule_time = scheduleTime
      scheduleMethod.timewarp = false
      client.execute(scheduleMethod)
      println(s"Scheduled campaign with campaignID: $newCampaignId to: $scheduleTime")

    } else {
      // do nothing
    }
  }




  def terminateWhen(done: Future[Done]) = {
    done.onComplete {
      case Success(_) =>
        println(s"Flow Success. About to terminate...")
        system.terminate()
      case Failure(e) =>
        println(s"Flow Failure: $e. About to terminate...")
        system.terminate()
    }
  }

  val done: Future[Done] = FileIO.fromPath(Paths.get(pathToCSV))
    .via(Framing.delimiter(ByteString("\n"), 1024))
    .map(_.utf8String.split(";"))
    .map(raw => toEventRecord(raw))
    .throttle(2, 1.second, 5, ThrottleMode.shaping)
    .map(record => prepareTemplate(record))
    .runForeach(tuple => prepareCampaign(tuple))

  terminateWhen(done)

  case class EventRecord(year: String = "", eventID: String = "", eventDate: String = "", eventArtist: String = "", eventName: String = "", pathToPic: String = "", teaserText: String = "") {
    def isHeading = year.isEmpty

    def toValueMap = Map("year" -> year, "eventID" -> eventID, "eventDate" -> eventDate, "eventArtist" -> eventArtist, "eventName" -> eventName, "pathToPic" -> pathToPic, "teaserText" -> teaserText)
    def name = s"PROD_${year}_$eventID"
  }
}
