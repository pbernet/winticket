package com.winticket.core

import java.nio.file.{Path, Paths}

import akka.actor.ActorSystem
import akka.http.scaladsl.Http
import akka.http.scaladsl.marshalling.Marshal
import akka.http.scaladsl.model._
import akka.http.scaladsl.unmarshalling.Unmarshal
import akka.stream.ActorMaterializer
import akka.stream.scaladsl.FileIO

import scala.concurrent.Future
import scala.concurrent.duration._

/**
 * Test client for upload an internal test data file via HTTP
 * It requires WinticketMicroserviceMain to run on localhost
 *
 */
object FileUpload extends App {

  implicit val system = ActorSystem("ServerTest")
  import system.dispatcher
  implicit val materializer = ActorMaterializer()

  def createEntity(filePath: Path): Future[RequestEntity] = {
    require(filePath.toFile.exists())
    val source = FileIO.fromPath(filePath, chunkSize = 100000) // the chunk size here is currently critical for performance
    val mediaTypeWithCharSet = MediaTypes.`text/csv` withCharset HttpCharsets.`UTF-8`
    val indef: HttpEntity = HttpEntity.IndefiniteLength(mediaTypeWithCharSet, source)

    val multipartForm =
      Multipart.FormData(
        Multipart.FormData.BodyPart.Strict(
          "csv",
          HttpEntity(ContentTypes.`text/plain(UTF-8)`, "1,5,7\n11,13,17"),
          Map("filename" -> "data.csv")))

    Marshal(multipartForm).to[RequestEntity]
  }

  def createRequest(target: Uri, filePath: Path): Future[HttpRequest] =
    for {
      e <- createEntity(filePath)
      _ = println(s"Entity is: ${e.toString} ")
    } yield HttpRequest(HttpMethods.POST, uri = target, entity = e)

  try {
    val port = 9000
    val target = Uri(scheme = "http", authority = Uri.Authority(Uri.Host("localhost"), port = port), path = Uri.Path("/uploadtest"))

    val testFilePath: Path = Paths.get(args(0))
    val result =
      for {
        req <- createRequest(target, testFilePath)
        _ = println(s"Running request, uploading test file of size ${testFilePath.toFile.length} bytes")
        response <- Http().singleRequest(req)
        responseBodyAsString <- Unmarshal(response).to[String]
      } yield responseBodyAsString

    result.onComplete { res =>
      println(s"The result was $res")
      system.terminate()
    }

    system.scheduler.scheduleOnce(60.seconds) {
      println("Shutting down after timeout...")
      system.terminate()
    }
  } catch {
    case _: Throwable ⇒ system.terminate()
  }
}
