package com.winticket.core

import com.typesafe.config.ConfigFactory

trait Config {
  private val config = ConfigFactory.load()
  private val httpConfig = config.getConfig("http")
  private val tennantConfig = config.getConfig("tennant")
  private val smtpConfig = config.getConfig("smtp")
  private val telizeConfig = config.getConfig("services")
  private val securityConfig = config.getConfig("security")

  val httpInterface = httpConfig.getString("interface")
  val httpPort = httpConfig.getInt("port")

  val tennantID = tennantConfig.getString("tennantID")

  val tls = smtpConfig.getBoolean("tls")
  val ssl = smtpConfig.getBoolean("ssl")
  val port = smtpConfig.getInt("port")
  val host = smtpConfig.getString("host")
  val user = smtpConfig.getString("user")
  val password = smtpConfig.getString("password")

  val telizeHost = telizeConfig.getString("telizeHost")
  val telizePort = telizeConfig.getInt("telizePort")

  val isCheck4SwissIPEnabled = securityConfig.getBoolean("isCheck4SwissIPEnabled")
  val adminUsername = securityConfig.getString("adminUsername")
  val adminPassword = securityConfig.getString("adminPassword")
}
