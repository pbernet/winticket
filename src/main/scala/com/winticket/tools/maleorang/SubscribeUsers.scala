package com.winticket.tools.maleorang

import java.io.IOException
import java.nio.file.Paths

import akka.Done
import akka.actor.ActorSystem
import akka.stream.scaladsl.{FileIO, Framing}
import akka.stream.{ActorMaterializer, ThrottleMode}
import akka.util.ByteString
import com.ecwid.maleorang.method.v3_0.lists.members.EditMemberMethod
import com.ecwid.maleorang.{MailchimpClient, MailchimpException, MailchimpObject}

import scala.concurrent.Future
import scala.concurrent.duration._

/**
  * Bulk import users to MailChimp via the REST API
  * The csv file is in format:
  * valid.email@xxxx.com,firstname,lastname
  * valid.email2@xxxx.com,firstname2,lastname2
  * //last line do not remove
  *
  * This is an alternative to the bulk import via Admin GUI:
  * https://kb.mailchimp.com/lists/growth/import-subscribers-to-a-list
  *
  */
object SubscribeUsers {
  implicit val system = ActorSystem()
  implicit val ec = system.dispatcher
  implicit val materializer = ActorMaterializer()

  val apiKey = "***"
  val listId = "***"

  val pathToCSV = "/path/to/file.csv"
  val client = new MailchimpClient(apiKey)

  val emailRegex = """^[a-zA-Z0-9\.!#$%&'*+/=?^_`{|}~-]+@[a-zA-Z0-9](?:[a-zA-Z0-9-]{0,61}[a-zA-Z0-9])?(?:\.[a-zA-Z0-9](?:[a-zA-Z0-9-]{0,61}[a-zA-Z0-9])?)*$""".r

  private def isEMailValid(e: String): Boolean = e match {
    case null => false
    case `e` if e.trim.isEmpty => false
    case `e` if emailRegex.findFirstMatchIn(e).isDefined => true
    case _ => false
  }

  def main(args: Array[String]): Unit = {
    val closed: Future[Done] = FileIO.fromPath(Paths.get(pathToCSV))
      .via(Framing.delimiter(ByteString("\n"), 1024))
      .map(_.utf8String.split(",").toVector)
      .throttle(2, 1.second, 5, ThrottleMode.shaping)
      .runForeach(subscribeTo(listId))
    closed.onComplete { each =>
      try {
        client.close()
        system.terminate()
      }
      catch {
        case e: IOException => e.printStackTrace()
      }
    }
  }

  private def subscribeTo(listId: String) = {
    eachRecord: Seq[String] => {
      val email = eachRecord.head
      if (isEMailValid(email) && eachRecord.length == 3) {
        println(s"About to subscribe: $email with correct length: ${eachRecord.length}")
        try {
          val status = new LookupMethod(apiKey, listId).run(eachRecord.head)
          if (status == "unsubscribed" || status == "pending" || status == "cleaned") {
            println(s"User: $eachRecord is in status: $status and thus will not be subscribed")
          } else if (status == "subscribed") {
            println(s"User: $eachRecord is already on list and thus will not be subscribed")
          }  else  {
            println(s"User: $eachRecord is not on list and thus will be subscribed")
            createOrUpdate(listId, eachRecord)
          }
        } catch {
          case e: MailchimpException if e.code == 404 => {
            println(s"User: $eachRecord is not on list. Subscribe")
            createOrUpdate(listId, eachRecord)
          }
          case e: RuntimeException => e.printStackTrace()
        }
      } else {
        println(s"$eachRecord does not contain a valid EMail Address OR metadata is not provided, proceed")
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
    System.out.println(s"The user: $eachRecord has been successfully created/updated: $member")
  }
}
