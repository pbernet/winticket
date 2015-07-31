package com.winticket.mail

import scala.concurrent.duration.FiniteDuration

/**
 * The email message sent to Actors in charge of delivering email
 *
 * @param subject the email subject
 * @param recipient the recipient
 * @param from the sender
 * @param text alternative simple text
 * @param html html body
 */
case class EMailMessage(subject: String,
  recipient: String,
  from: String,
  text: Option[String] = None,
  html: Option[String] = None,
  smtpConfig: SmtpConfig,
  retryOn: FiniteDuration,
  var deliveryAttempts: Int = 0)
