package com.winticket.server

import akka.actor.ActorRef
import akka.pattern.ask
import akka.util.Timeout
import com.winticket.core.BaseService

import scala.concurrent.duration._
import scala.concurrent.{Await, ExecutionContext, Future}

trait DrawingAPI extends BaseService {
  import com.winticket.server.DrawingActor._
  import com.winticket.server.DrawingActorSupervisor._

  implicit def executor: ExecutionContext = system.dispatcher
  implicit def requestTimeout = Timeout(10 seconds)

  def createSupervisor(): ActorRef
  lazy val supervisor: ActorRef = createSupervisor()

  def createGeoIPChecker(): ActorRef
  lazy val geoIPCheckerActor: ActorRef = createGeoIPChecker()

  def createDrawing(createDrawing: CreateDrawing): Unit = {
    supervisor ! CreateChild(createDrawing)
  }

  //TODO With this approach subscriptions (and geoIP checks) are made for these corner cases:
  // - Subscription for an event which is already in postDrawing state. A different confirmation page (= sorry drawing is done) could be shown to user
  // - Subscription for an event, which does not exist
  def subscribe(tennantID: String, tennantYear: Int, drawingEventID: Int, commandORsubscriptionEMail: String, clientIP: Option[String]): Unit = {
    supervisor ! Subscribe(tennantID, tennantYear.toString, drawingEventID.toString, commandORsubscriptionEMail, clientIP.getOrElse("N/A from request"))
    geoIPCheckerActor ! GeoIPCheckerActor.AddIPCheckRecord(tennantID, tennantYear, drawingEventID, clientIP)
  }

  def askForSubscriptions: List[List[SubscriptionRecord]] = {
    val future: Future[List[List[SubscriptionRecord]]] = ask(supervisor, Subscribtions).mapTo[List[List[SubscriptionRecord]]]
    Await.result(future, requestTimeout.duration)
  }

  def askForDrawingReports: List[DrawingReport] = {
    val future: Future[List[DrawingReport]] = ask(supervisor, DrawingReports).mapTo[List[DrawingReport]]
    Await.result(future, requestTimeout.duration)
  }
}
