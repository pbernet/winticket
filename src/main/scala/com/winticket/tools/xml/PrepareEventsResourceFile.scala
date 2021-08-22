package com.winticket.tools.xml

import java.io.{FileWriter, StringWriter}

import akka.actor.ActorSystem
import akka.http.scaladsl.model.DateTime
import akka.stream.ActorMaterializer
import akka.stream.alpakka.xml.scaladsl.XmlParsing
import akka.stream.alpakka.xml.{EndElement, ParseEvent, StartElement, TextEvent}
import akka.stream.scaladsl.{Sink, Source}
import akka.util.ByteString
import com.github.marklister.collections.immutable.CollSeq
import com.github.marklister.collections.io.Utils.CsvOutput
import com.winticket.server.DrawingActor.CreateDrawing

import scala.collection.immutable
import scala.concurrent.Future
import scala.util.Try

/**
 * Parse DB export XML file and
 * generate events resource csv file
 *
 */

object PrepareEventsResourceFile {
  implicit val system = ActorSystem("PrepareEventsResourceFile")
  implicit val executionContext = system.dispatcher
  implicit val materializer = ActorMaterializer()

  val pathToXML = "/Users/Shared/projects/winticket/data/events_export_herbst_2021.xml"

  def main(args: Array[String]): Unit = {
    val fileContentsWithRemovedComments = scala.io.Source.fromFile(pathToXML).getLines.mkString.replaceAll("(?s)<!--.*?-->", "")
    val byteStringDoc = ByteString.fromString(fileContentsWithRemovedComments, "UTF-8")

    val result: Future[immutable.Seq[String]] = Source
      .single(byteStringDoc)
      .via(XmlParsing.parser)
      .statefulMapConcat(() => {
        // state
        val textBuffer = StringBuilder.newBuilder
        // aggregation function
        parseEvent: ParseEvent =>
          parseEvent match {
            case s: StartElement =>
              textBuffer.clear()
              immutable.Seq.empty
            case s: EndElement if s.localName == "column" =>
              val text = textBuffer.toString
              //println("Add column payload: " + text)
              immutable.Seq(text)
            case t: TextEvent =>
              textBuffer.append(t.text)
              immutable.Seq.empty
            case _ =>
              immutable.Seq.empty
          }
      })
      .runWith(Sink.seq)

    result.onComplete {
      results: Try[immutable.Seq[String]] =>

        val currentYear = DateTime.now.year
        val fw = new FileWriter(s"events_production_$currentYear.csv")
        val sw = new StringWriter
        val tenantID = "gruenfels"

        results.get.sliding(17, 17).foreach { each =>

          val date = DateTime.fromIsoDateTimeString(each(3).toString.replace(" ", "T")).get
          val eventNameTitle = each(1).toString + " - " + each(2).toString.replaceAll("\"", "").stripPrefix("«").stripSuffix("»")

          val createDrawing = CreateDrawing(tenantID, currentYear, s"winticket@$tenantID.ch", each.head.toString, eventNameTitle, date, "xxx", "-")

          CollSeq(Vector(createDrawing)).writeCsv(sw)
          println(each)
        }
        fw.write(sw.toString)
        fw.close()

        println("Written Flow completed, about to terminate")
        system.terminate()
    }
  }
}