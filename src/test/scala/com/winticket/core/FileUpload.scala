package com.winticket.core

import java.io.File

import akka.actor.ActorSystem
import akka.http.scaladsl.Http
import akka.http.scaladsl.marshalling.Marshal
import akka.http.scaladsl.model._
import akka.http.scaladsl.unmarshalling.Unmarshal
import akka.stream.ActorMaterializer
import akka.stream.io.SynchronousFileSource
import akka.stream.scaladsl.Source

import scala.concurrent.Future
import scala.concurrent.duration._

/**
  * Upload the test data file via Http
  * Has the sideeffect, that the file on the server contains Metainformation, don't know why...
  */
object FileUpload extends App {

  implicit val system = ActorSystem("ServerTest")
  import system.dispatcher
  implicit val materializer = ActorMaterializer()

  def createEntity(file: File): Future[RequestEntity] = {
    require(file.exists())
    val formData =
      Multipart.FormData(
        Source.single(
          Multipart.FormData.BodyPart(
            "uploadtest",
            HttpEntity(MediaTypes.`text/csv`, file.length(), SynchronousFileSource(file, chunkSize = 100000)), // the chunk size here is currently critical for performance
            Map("filename" -> file.getName))))
    Marshal(formData).to[RequestEntity]
  }

  def createRequest(target: Uri, file: File): Future[HttpRequest] =
    for {
      e <- createEntity(file)
      _ = println(s"Entity is: ${e.toString} ")
    } yield HttpRequest(HttpMethods.POST, uri = target, entity = e)

  try {
    val port = 9000
    val target = Uri(scheme = "http", authority = Uri.Authority(Uri.Host("localhost"), port = port), path = Uri.Path("/upload"))
    val testFile = new File(args(0))

    val result =
      for {
        req <- createRequest(target, testFile)
        _ = println(s"Running request, uploading test file of size ${testFile.length} bytes")
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
