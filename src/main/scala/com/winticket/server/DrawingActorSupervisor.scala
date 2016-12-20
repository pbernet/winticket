package com.winticket.server

import akka.actor.SupervisorStrategy.{Escalate, Restart}
import akka.actor.{ActorLogging, OneForOneStrategy, Terminated}
import akka.pattern.ask
import akka.persistence.{PersistentActor, RecoveryCompleted, SnapshotOffer}
import akka.util.Timeout
import com.winticket.server.DrawingActor._
import com.winticket.server.DrawingActorSupervisor._
import com.winticket.server.DrawingProtocol.DrawingActorCreated
import com.winticket.server.GeoIPCheckerActor.IPCheckRecord

import scala.concurrent.Future
import scala.concurrent.duration._
import scala.util.{Failure, Success}

object DrawingActorSupervisor {

  import DrawingProtocol._

  sealed trait CommandSupervisor
  case class CreateChild(createDrawing: CreateDrawing) extends CommandSupervisor
  case class RemoveSubscription(record: IPCheckRecord) extends CommandSupervisor
  case object Subscribtions extends CommandSupervisor
  case object DrawingReports extends CommandSupervisor

  case class SupervisorState(actorPaths: List[String] = Nil) {

    def updated(evt: DrawingActorCreated): SupervisorState = copy(evt.actorPath :: actorPaths)

    def size: Int = actorPaths.length

    override def toString: String = actorPaths.reverse.toString
  }
}

class DrawingActorSupervisor extends PersistentActor with ActorLogging {

  override val supervisorStrategy: OneForOneStrategy = OneForOneStrategy(maxNrOfRetries = 2, withinTimeRange = 2.minutes) {
    case _: RuntimeException => Restart
    case t                   => super.supervisorStrategy.decider.applyOrElse(t, (_: Any) => Escalate)
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
      context.actorOf(DrawingActor.props(evt.actorPath), evt.actorPath)
    }
    case SnapshotOffer(_, snapshot: SupervisorState) => state = snapshot
    case RecoveryCompleted                           => log.info("RecoveryCompleted")
    case msg                                         => log.warning(s"Received unknown message for state receiveRecover - do nothing. Message is: $msg")
  }

  val receiveCommand: Receive = {
    case CreateChild(createDrawing) => {
      //TODO Extend uniqueActorName to include tennantYear so it is in the Form tennantID/tennantYear/drawingEventID
      //Refactor tennantYear and drawingEventID to Int
      //Extend case classes to do comparisons using uniqueActorName attributes
      val uniqueActorName = "DrawingActor-" + createDrawing.tennantID + "-" + createDrawing.drawingEventID
      val child = context.child(uniqueActorName)
      if (child.isDefined) {
        //do nothing
      } else {
        val child = context.actorOf(DrawingActor.props(uniqueActorName), uniqueActorName)
        child ! createDrawing
        log.info(s"------- DrawingActor -----> $createDrawing")

        persist(DrawingActorCreated(uniqueActorName)) { evt =>
          log.info(s"Creating actor with ref: $uniqueActorName")
          updateState(evt)
        }
        context.watch(child)
      }
    }
    case RemoveSubscription(iPCheckRecord) => {
      cmdToChild(iPCheckRecord.tennantID, iPCheckRecord.drawingEventID)(RemoveSubscription(iPCheckRecord))
    }
    case Subscribtions => {
      import context.dispatcher
      implicit val timeout = Timeout(5 seconds)

      var listOfFutures = List[Future[List[SubscriptionRecord]]]()
      val origSender = sender()

      context.children.foreach { drawingActor =>
        val subscriptionsFuture: Future[List[SubscriptionRecord]] = ask(drawingActor, GetSubscribtions).mapTo[List[SubscriptionRecord]]
        listOfFutures = subscriptionsFuture :: listOfFutures
      }
      val futureList = Future.sequence(listOfFutures)
      futureList onComplete {
        case Success(result)  => origSender ! result
        case Failure(failure) => log.error(s"Error occurred while collecting subscriptions. Details: $failure")
      }
    }
    case DrawingReports => {
      import context.dispatcher
      implicit val timeout = Timeout(5 seconds)

      var listOfFutures = List[Future[DrawingReport]]()
      val origSender = sender()

      context.children.foreach { drawingActor =>
        val drawingsFuture: Future[DrawingReport] = ask(drawingActor, GetDrawingReport).mapTo[DrawingReport]
        listOfFutures = drawingsFuture :: listOfFutures
      }

      val futureList = Future.sequence(listOfFutures)
      futureList onComplete {
        case Success(result)  => origSender ! result
        case Failure(failure) => log.error(s"Error occurred while collecting DrawingReports. Details: $failure")
      }
    }
    case subscribe @ Subscribe(tennantID, tennantYear, drawingEventID, subscriptionEMail, clientIPString) => {
      cmdToChild(tennantID, drawingEventID.toInt )(subscribe)
    }
    case DrawWinner => {
      context.children.foreach(drawingActor => drawingActor ! DrawWinner)
    }
    case Terminated(child) => {
      log.info("Child: {} has terminated." + child.actorRef.path)
    }
  }

  override def unhandled(message: Any): Unit = {
    log.warning(s"In state: ${state.size}  unhandled() called with msg $message")
    super.unhandled(message)
  }

  private def cmdToChild(tennantID: String, drawingEventID: Int) (cmd: Any) = {
    log.debug(s"Enter cmdToChild")
    val uniqueActorName = "DrawingActor-" + tennantID + "-" + drawingEventID
    val child = context.child(uniqueActorName)
    if (child.isDefined) {
      log.debug(s"Found child for name: $uniqueActorName - Pass on command $cmd.")
      child.get ! cmd
    } else {
      log.warning(s"No child found for name: $uniqueActorName - Do nothing.")
    }
  }
}