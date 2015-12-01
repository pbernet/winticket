package com.winticket.mail

import akka.actor.SupervisorStrategy._
import akka.actor._
import akka.routing.SmallestMailboxPool
import org.apache.commons.mail.{DefaultAuthenticator, EmailException, HtmlEmail}

/**
 * Email service with its own ActorSystem
 */
object EMailService {

  /**
   * Uses the smallest inbox strategy to keep 20 instances alive ready to send out email
   * @see SmallestMailboxRouter
   */
  val emailServiceActor = akka.actor.ActorSystem("system").actorOf(
    Props[EmailServiceActor].withRouter(
      SmallestMailboxPool(nrOfInstances = 20)
    ), name = "EMailService"
  )

  /**
   * public interface to send out emails that dispatch the message to the listening actors
   * @param emailMessage the email message
   */
  def send(emailMessage: EMailMessage) {
    emailServiceActor ! emailMessage
  }

  /**
   * Private helper invoked by the actors that sends the email
   * @param emailMessage the email message
   */
  private def sendEmailSync(emailMessage: EMailMessage) {

    // Create the email message
    val email = new HtmlEmail()
    email.setTLS(emailMessage.smtpConfig.tls)
    email.setSSL(emailMessage.smtpConfig.ssl)
    email.setSmtpPort(emailMessage.smtpConfig.port)
    email.setHostName(emailMessage.smtpConfig.host)
    email.setAuthenticator(new DefaultAuthenticator(
      emailMessage.smtpConfig.user,
      emailMessage.smtpConfig.password
    ))
    email.setCharset("utf-8")

    emailMessage.text match {
      case Some(text) => email.setTextMsg(text)
      case None       =>
    }

    emailMessage.html match {
      case Some(html) => email.setHtmlMsg(html)
      case None       =>
    }

    email.addTo(emailMessage.recipient)
      .setFrom(emailMessage.from)
      .setSubject(emailMessage.subject)
      .send()
  }

  /**
   * An Email sender actor that sends out email messages
   * Retries delivery up to 10 times every 5 minutes as long as it receives
   * an EmailException, gives up at any other type of exception
   */
  class EmailServiceActor extends Actor with ActorLogging {

    /**
     * The actor supervisor strategy attempts to send email up to 10 times if there is a EmailException
     */
    override val supervisorStrategy =
      OneForOneStrategy(maxNrOfRetries = 10) {
        case emailException: EmailException => {
          log.debug("Restarting after receiving EmailException : {}", emailException.getMessage)
          Restart
        }
        case unknownException: Exception => {
          log.debug("Giving up. Can you recover from this? : {}", unknownException)
          Stop
        }
        case unknownCase: Any => {
          log.debug("Giving up on unexpected case : {}", unknownCase)
          Stop
        }
      }

    /**
     * Forwards messages to child workers
     */
    def receive = {
      case message: Any => context.actorOf(Props[EmailServiceWorker]) ! message
    }

  }

  /**
   * Email worker that delivers the message
   */
  class EmailServiceWorker extends Actor with ActorLogging {

    /**
     * The email message in scope
     */
    private var emailMessage: Option[EMailMessage] = None

    /**
     * Delivers a message
     */
    def receive = {
      case email: EMailMessage => {
        emailMessage = Option(email)
        email.deliveryAttempts = email.deliveryAttempts + 1
        log.debug("Atempting to deliver message")
        sendEmailSync(email)
        log.debug("Message delivered")
      }
      case unexpectedMessage: Any => {
        log.debug("Received unexepected message : {}", unexpectedMessage)
        throw new Exception("can't handle %s".format(unexpectedMessage))
      }
    }

    /**
     * If this child has been restarted due to an exception attempt redelivery
     * based on the message configured delay
     */
    override def preRestart(reason: Throwable, message: Option[Any]) {
      if (emailMessage.isDefined) {
        log.debug("Scheduling email message to be sent after attempts: {}", emailMessage.get)
        import context.dispatcher
        // Use this Actors' Dispatcher as ExecutionContext

        context.system.scheduler.scheduleOnce(emailMessage.get.retryOn, self, emailMessage.get)
      }
    }

    override def postStop() {
      if (emailMessage.isDefined) {
        log.debug("Stopped child email worker after attempts {}, {}", emailMessage.get.deliveryAttempts, self)
      }
    }
  }
}
