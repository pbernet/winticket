package com.winticket.core

import akka.actor.ActorSystem
import akka.http.scaladsl.Http
import akka.http.scaladsl.marshalling.Marshal
import akka.http.scaladsl.model._
import akka.http.scaladsl.unmarshalling.Unmarshal
import akka.stream.ActorMaterializer

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

  def createEntity(): Future[RequestEntity] = {
    val multipartForm =
      Multipart.FormData(
        Multipart.FormData.BodyPart.Strict(
          "csv",
          HttpEntity(ContentTypes.`text/plain(UTF-8)`, "1,5,7\n11,13,17"),
          Map("filename" -> "data.csv")))

    Marshal(multipartForm).to[RequestEntity]
  }

  def createRequest(target: Uri): Future[HttpRequest] =
    for {
      e <- createEntity()
      _ = println(s"Entity is: ${e.toString} ")
    } yield HttpRequest(HttpMethods.POST, uri = target, entity = e)

  try {
    val port = 9000
    val target = Uri(scheme = "http", authority = Uri.Authority(Uri.Host("localhost"), port = port), path = Uri.Path("/uploadtest"))

    val result =
      for {
        req <- createRequest(target)
        _ = println(s"Running request, uploading internal test file")
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
