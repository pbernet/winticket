package com.winticket.maleorang

import java.io.IOException
import java.nio.file.Paths

import akka.actor.ActorSystem
import akka.stream.scaladsl.{FileIO, Framing}
import akka.stream.{ActorMaterializer, ThrottleMode}
import akka.util.ByteString
import com.ecwid.maleorang.method.v3_0.lists.members.EditMemberMethod
import com.ecwid.maleorang.{MailchimpClient, MailchimpException, MailchimpObject}

import scala.concurrent.duration._

/**
  * TODO Verify exceptions before using on prod data
  * - Add logging also about failures
  * - Add CHECK the current user status - if user is in status "unsubscribed" OR "cleaned" - do not change to subscribe!
  *
  */
object SubscribeUsers {
  implicit val system = ActorSystem()
  implicit val ec = system.dispatcher
  implicit val materializer = ActorMaterializer()

  val apiKey = "***"
  val listId = "***"

  val pathToCSV = "/Users/pbernetold/Desktop/gf_newsletter_canditates_test.csv"
  val client = new MailchimpClient(apiKey)

  val emailRegex = """^[a-zA-Z0-9\.!#$%&'*+/=?^_`{|}~-]+@[a-zA-Z0-9](?:[a-zA-Z0-9-]{0,61}[a-zA-Z0-9])?(?:\.[a-zA-Z0-9](?:[a-zA-Z0-9-]{0,61}[a-zA-Z0-9])?)*$""".r

  private def isEMailValid(e: String): Boolean = e match {
    case null => false
    case `e` if e.trim.isEmpty => false
    case `e` if emailRegex.findFirstMatchIn(e).isDefined => true
    case _ => false
  }

  def main(args: Array[String]): Unit = {

    FileIO.fromPath(Paths.get(pathToCSV))
      .via(Framing.delimiter(ByteString("\n"), 1024))
      .map(_.utf8String.split(",").toVector)
      .throttle(1, 1.second, 1, ThrottleMode.shaping)
      .runForeach {
        subscribeTo(listId)
      }
  }

  private def subscribeTo(listId: String) = {
    eachRecord: Seq[String] =>
     println(s"About to subscribe: $eachRecord with length: ${eachRecord.length}")
      if (isEMailValid(eachRecord.head) && eachRecord.length == 3) {
        try {
          val method = new EditMemberMethod.CreateOrUpdate(listId, eachRecord.head)
          method.status = "subscribed"
          method.merge_fields = new MailchimpObject()
          method.merge_fields.mapping.put("FNAME", eachRecord(1))
          method.merge_fields.mapping.put("LNAME", eachRecord(2))
          val member = client.execute(method)
          System.out.println("The user has been successfully subscribed: " + member)
        } catch {
          case e: RuntimeException =>
            e.printStackTrace()
          case e: MailchimpException =>
            e.printStackTrace()
          case e: IOException =>
            e.printStackTrace()
        } finally try {
          client.close()
          system.terminate()
        }
        catch {
          case e: IOException =>
            e.printStackTrace()
        }
      }
  }
}
