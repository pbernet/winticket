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
  * - A user which was set to "unsubscribed" via GUI produces a 404 - this should not happen
  * - Beautify workflow
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
      .runForeach(subscribeTo(listId))
  }

  private def subscribeTo(listId: String) = {
    eachRecord: Seq[String] =>
      println(s"About to subscribe: $eachRecord with length: ${eachRecord.length}")
      if (isEMailValid(eachRecord.head) && eachRecord.length == 3) {
          try {
            val status = new LookupMethod(apiKey, listId).run()
            System.out.println("STATUS: " + status)
            if (status == "unsubscribed" || status == "pending" || status == "cleaned") {
              println(s"User: $eachRecord is in status $status and thus will not be subscribed")

            } else {
              createOrUpdate(listId, eachRecord)
            }
          } catch {
            case e: MailchimpException => {   //Can I match for the code?
              System.out.println("MailchimpException: " + e.code)
              if (e.code == 404) {
                createOrUpdate(listId, eachRecord)
              } else {
                //do nothing
              }
            }
          case e: RuntimeException =>
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

  private def createOrUpdate(listId: String, eachRecord: Seq[String]) = {
    val createOrUpdate = new EditMemberMethod.CreateOrUpdate(listId, eachRecord.head)
    createOrUpdate.status = "subscribed"
    createOrUpdate.merge_fields = new MailchimpObject()
    createOrUpdate.merge_fields.mapping.put("FNAME", eachRecord(1))
    createOrUpdate.merge_fields.mapping.put("LNAME", eachRecord(2))
    val member = client.execute(createOrUpdate)
    System.out.println(s"The user: $eachRecord has been successfully subscribed: $member")
  }
}
