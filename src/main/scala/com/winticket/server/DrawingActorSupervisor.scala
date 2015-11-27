package com.winticket.server

import akka.actor.SupervisorStrategy.Restart
import akka.actor.{ActorLogging, OneForOneStrategy}
import akka.pattern.ask
import akka.persistence.{PersistentActor, SnapshotOffer}
import akka.util.Timeout
import com.winticket.server.DrawingActor._
import com.winticket.server.DrawingActorSupervisor.{CreateChild, Subscribtions, SupervisorState, Winners}
import com.winticket.server.DrawingProtocol.DrawingActorCreated

import scala.concurrent.Await
import scala.concurrent.duration._

object DrawingActorSupervisor {

  import DrawingProtocol._

  sealed trait CommandSupervisor
  case class CreateChild(createDrawing: CreateDrawing) extends CommandSupervisor
  case object Subscribtions extends CommandSupervisor
  case object Winners extends CommandSupervisor

  case class SupervisorState(actorRefs: List[String] = Nil) {

    def updated(evt: DrawingActorCreated): SupervisorState = copy(evt.actorRef :: actorRefs)

    def size: Int = actorRefs.length

    override def toString: String = actorRefs.reverse.toString
  }
}

class DrawingActorSupervisor extends PersistentActor with ActorLogging {

  //TODO Find a more suitable strategy
  override val supervisorStrategy = OneForOneStrategy(maxNrOfRetries = 2, withinTimeRange = 30.seconds) {
    case _: NullPointerException => Restart
    case _: RuntimeException     => Restart
  }

  override def persistenceId: String = "DrawingActorSupervisor"

  var state = SupervisorState()

  def updateState(event: DrawingActorCreated): Unit =
    state = state.updated(event)

  val receiveRecover: Receive = {
    case evt: DrawingActorCreated => {
      log.info("RECOVER DrawingActorCreated: " + evt)
      updateState(evt)
      //kick off the recovery of the child
      context.actorOf(DrawingActor.props(evt.actorRef), evt.actorRef)
    }
    case SnapshotOffer(_, snapshot: SupervisorState) => state = snapshot
  }

  val receiveCommand: Receive = {
    case CreateChild(createDrawing) => {
      val uniqueActorRef = "DrawingActor-" + createDrawing.tennantID + "-" + createDrawing.drawingEventID
      if (context.child(uniqueActorRef).isDefined) {
        //do nothing
      } else {
        val child = context.actorOf(DrawingActor.props(uniqueActorRef), uniqueActorRef)
        child ! createDrawing
        log.info(s"------- DrawingActor -----> $createDrawing")

        persist(DrawingActorCreated(uniqueActorRef)) { evt =>
          log.info(s"Creating actor with ref: $uniqueActorRef")
          updateState(evt)
        }
        context.watch(child)
      }
    }

    case Subscribtions => {
      implicit val timeout = Timeout(5 seconds)
      context.children.foreach { drawingActor =>
        val subscriptionsFuture = drawingActor ? GetSubscribtions
        log.info(s"------- DrawingActor -----> $GetSubscribtions")
        val subscriptions = Await.result(subscriptionsFuture, timeout.duration)
        val size = subscriptions.asInstanceOf[List[String]].size
        log.info(s"<------ DrawingActor ------ Size: $size Data: $subscriptions")
      }
    }
    case Winners => {
      implicit val timeout = Timeout(5 seconds)
      context.children.foreach { drawingActor =>
        val winnerFuture = drawingActor ? GetWinnerEMail
        log.info(s"------- DrawingActor -----> $GetWinnerEMail")
        val winnerEMail = Await.result(winnerFuture, timeout.duration)
        log.info(s"<------ DrawingActor ------ WinnerEMail: $winnerEMail")
      }
    }
    case subscribe @ Subscribe(tennantID, tennantYear, drawingEventID, subscriptionEMail, clientIPString) => {
      context.children.foreach(drawingActor => drawingActor ! subscribe)
    }
    case DrawWinner => {
      context.children.foreach(drawingActor => drawingActor ! DrawWinner)
    }
    case a @ _ => log.debug(s"Received unknown message for state receive - do nothing. Message is: $a")
  }
}