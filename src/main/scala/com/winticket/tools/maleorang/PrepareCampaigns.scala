package com.winticket.tools.maleorang

import java.io.IOException
import java.nio.file.Paths
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

import akka.Done
import akka.actor.ActorSystem
import akka.stream.scaladsl.{FileIO, Framing}
import akka.stream.{ActorMaterializer, ThrottleMode}
import akka.util.ByteString
import com.ecwid.maleorang.MailchimpClient
import com.ecwid.maleorang.method.v3_0.campaigns.{CampaignActionMethod, CampaignInfo, EditCampaignMethod}

import scala.concurrent.Future
import scala.concurrent.duration._

/**
  * Bulk create campaigns via the MailChimp REST API
  *
  */
object PrepareCampaigns {
  implicit val system = ActorSystem()
  implicit val ec = system.dispatcher
  implicit val materializer = ActorMaterializer()

  val apiKey = "***"
  val masterCampaignId = "***" //because "CreateCampaignMethod" does not exist in Lib
  val templateId: Integer = 111 //template (which can be preconfigured for the year)

  val pathToCSV = "/path/to/file.csv"

  val client = new MailchimpClient(apiKey)


  def main(args: Array[String]): Unit = {
    val closed: Future[Done] = FileIO.fromPath(Paths.get(pathToCSV))
      .via(Framing.delimiter(ByteString("\n"), 1024))
      .map(_.utf8String.split(",").toVector)
      .throttle(2, 1.second, 5, ThrottleMode.shaping)
      .runForeach(prepareCampaign(masterCampaignId))
    closed.onComplete { each =>
      try {
        client.close()
        system.terminate()
      } catch {
        case e: IOException => e.printStackTrace()
      }
    }
  }

  private def prepareCampaign(masterCampainId: String) = {
    eachRecord: Seq[String] => {
      if (eachRecord(0) != "tennantID") {


        val tennantYear = eachRecord(1)
        val drawingEventID = eachRecord(3)
        val drawingEventNameHead = eachRecord(4).split("-").head
        val drawingEventDate = eachRecord(5)
        println(s"About to create campaign for drawingEventID: $drawingEventID")
        val campaignActionMethod = new CampaignActionMethod.Replicate(masterCampainId)
        val campaignActionMethodResponse: CampaignInfo = client.execute(campaignActionMethod)
        val newCampaignId = campaignActionMethodResponse.id
        println(s"Created new campaign with ID: $newCampaignId and content_type: ${campaignActionMethodResponse.content_type}")


        val editCampaignMethod = new EditCampaignMethod.Update(newCampaignId)
        val deLocale = new Locale("de", "DE")
        val localDate = LocalDateTime.parse(drawingEventDate)
        val formatter = DateTimeFormatter.ofPattern("EEEE, dd. MMMM HH.mm", deLocale)
        val formattedDate = localDate.format(formatter)
        val subject = s"$drawingEventNameHead am $formattedDate"
        val editableSettings = campaignActionMethodResponse.settings
        editableSettings.subject_line = subject
        editableSettings.title = s"PROD_${tennantYear}_$drawingEventID $drawingEventNameHead"
        editableSettings.template_id = templateId
        editCampaignMethod.settings = editableSettings
        val editCampaignMethodResponse = client.execute(editCampaignMethod)
        println(s"Updated campaign with ID: $newCampaignId and drawingEventID: $drawingEventID. content_type: ${editCampaignMethodResponse.content_type}")



        //http://developer.mailchimp.com/documentation/mailchimp/reference/campaigns/content
        //Does not work because content_type ‘template’ does not allow content replacement
        //Needs to be ‘html’ but this can only set in GUI...
        //        val getCampaignContentMethod = new GetCampaignContentMethod(newCampaignId)
        //        val getCampaignContentMethodResponse = client.execute(getCampaignContentMethod)
        //        println("html content: " + getCampaignContentMethodResponse.html)
        //
        //
        //        val setSetCampaignContentMethod =  new SetCampaignContentMethod(newCampaignId)
        //        val template = new Template
        //        template.id = templateId
        //        val content: java.util.Map[String, String]= new util.HashMap()
        //        content.put("xxx", "DYNAMIC VALUE1")
        //        template.sections = content
        //        setSetCampaignContentMethod.template = template
        //        setSetCampaignContentMethod.html = getCampaignContentMethodResponse.html.replace("Desperados" , "xxxx")
        //        val setSetCampaignContentMethodResponse = client.execute(setSetCampaignContentMethod)
      }
      else {
        //do nothing because it is the 1st line in the csv
      }
    }
  }
}
