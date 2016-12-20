package com.winticket.core

import io.gatling.core.Predef._
import io.gatling.http.Predef._
import io.gatling.http.funspec.GatlingHttpFunSpec

/**
 * This class is a Gatling-Simulation and connects directly via HTTP to the REST Interface
 *
 * This test is started (via the sbt gatling plugin):
 * sbt -Dconfig.resource=/production.conf test
 *
 * The production.conf config file is re-used
 *
 *  Normal FlatSpec Tests (= with org.scalatest.FlatSpecLike with Matchers) must not be included in this test
 *  because the the spec(s) are executed twice...
 *
 */
class WinticketServiceSystemSpec extends GatlingHttpFunSpec with Config {

  val baseURL = baseURLConfig
  override def httpConf = {
    super.httpConf.header("Custom-Header", "gatling.io")
    super.httpConf.userAgentHeader("gatling.io")
    //For testing on localhost:
    //Bind the sockets from a specific local address instead of 127.0.0.1
    //Take IP from en0/en1
    super.httpConf.localAddress("192.168.1.54")
  }

  //simulate a redundant subscription -> only one per EMail is accepted for drawing
  spec {
    http("gruenfels/2017/49")
      .get("/gruenfels/2017/49/" + mailAccount1)
      .check(status.is(200))
  }

  spec {
    http("gruenfels/2017/49")
      .get("/gruenfels/2017/49/" + mailAccount1)
      .check(status.is(200))
  }

  //subscribe with different accounts for the same event -> That is possible
  spec {
    http("gruenfels/2017/49")
      .get("/gruenfels/2017/49/" + mailAccount2)
      .check(status.is(200))
  }

  spec {
    http("gruenfels/2017/49")
      .get("/gruenfels/2017/49/" + mailAccount3)
      .check(status.is(200))
  }

  //subscribe with default account not existing events -> The system currently accepts these requests
  spec {
    http("gruenfels/2017/50")
      .get("/gruenfels/2017/50/" + mailAccount1)
      .check(status.is(200))
  }

  //subscribe with default account for 2nd tennant -> To show the tennant functionality
  spec {
    http("mandant2/2017/49")
      .get("/mandant2/2017/49/" + mailAccount1)
      .check(status.is(200))
  }

}

object WinticketServiceSystemSpec {

}
