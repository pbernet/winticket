package com.winticket.core

import com.typesafe.config.ConfigFactory

trait Config {
  private val config = ConfigFactory.load()
  private val httpConfig = config.getConfig("http")
  private val tennantConfig = config.getConfig("tennant")
  private val smtpConfig = config.getConfig("smtpConfig")

  val httpInterface = httpConfig.getString("interface")
  val httpPort = httpConfig.getInt("port")

  val tennantID = tennantConfig.getString("tennantID")

  val tls = smtpConfig.getBoolean("tls")
  val ssl = smtpConfig.getBoolean("ssl")
  val port = smtpConfig.getInt("port")
  val host = smtpConfig.getString("host")
  val user = smtpConfig.getString("user")
  val password = smtpConfig.getString("password")
}
