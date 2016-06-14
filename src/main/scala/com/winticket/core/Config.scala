package com.winticket.core

import java.util.concurrent.TimeUnit

import com.typesafe.config.ConfigFactory

import scala.concurrent.duration.FiniteDuration

trait Config {
  private val config = ConfigFactory.load()
  private val httpConfig = config.getConfig("http")
  private val appConfig = config.getConfig("app")
  private val smtpConfig = config.getConfig("smtp")
  private val servicesConfig = config.getConfig("services")
  private val securityConfig = config.getConfig("security")
  private val envConfig = config.getConfig("env")
  private val testConfig = config.getConfig("test")

  val httpInterface = httpConfig.getString("interface")
  val httpPort = httpConfig.getInt("port")

  val drawingDateDeltaDaysBackwards = appConfig.getLong("drawingDateDeltaDaysBackwards")
  val tennantList = appConfig.getStringList("tennantList")
  val winnerMessageToTennantisActivated = appConfig.getBoolean("winnerMessageToTennantisActivated")
  val initialDelayDrawWinner = FiniteDuration(appConfig.getDuration("initialDelayDrawWinner", TimeUnit.MINUTES), TimeUnit.MINUTES)
  val intervalDrawWinner = FiniteDuration(appConfig.getDuration("intervalDrawWinner", TimeUnit.HOURS), TimeUnit.HOURS)

  val tls = smtpConfig.getBoolean("tls")
  val ssl = smtpConfig.getBoolean("ssl")
  val port = smtpConfig.getInt("port")
  val host = smtpConfig.getString("host")
  val user = smtpConfig.getString("user")
  val password = smtpConfig.getString("password")

  val isCheck4SwissIPEnabled = servicesConfig.getBoolean("isCheck4SwissIPEnabled")
  val geoipHost = servicesConfig.getString("geoipHost")
  val geoipPort = servicesConfig.getInt("geoipPort")
  val initialDelayGeoIPCheck = FiniteDuration(servicesConfig.getDuration("initialDelayGeoIPCheck", TimeUnit.MINUTES), TimeUnit.MINUTES)
  val intervalGeoIPCheck = FiniteDuration(servicesConfig.getDuration("intervalGeoIPCheck", TimeUnit.HOURS), TimeUnit.HOURS)

  val adminUsername = securityConfig.getString("adminUsername")
  val adminPassword = securityConfig.getString("adminPassword")

  val eventsFilePath = envConfig.getString("eventsFilePath")

  val mailAccount1 = testConfig.getString("mailAccount1")
  val mailAccount2 = testConfig.getString("mailAccount2")
  val mailAccount3 = testConfig.getString("mailAccount3")
}
