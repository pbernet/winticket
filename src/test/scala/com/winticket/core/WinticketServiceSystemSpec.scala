package com.winticket.core

import io.gatling.core.Predef._
import io.gatling.http.Predef._
import io.gatling.http.funspec.GatlingHttpFunSpec

/**
 *  This class is a Gatling-Simulation and connects directly via HTTP to the REST Interface
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

  //val baseURL = "http://localhost:9000"
  val baseURL = "http://winticket.elasticbeanstalk.com"
  override def httpConf = {
    super.httpConf.header("Custom-Header", "gatling.io")
    super.httpConf.userAgentHeader("gatling.io")
    //Bind the sockets from a specific local address instead of 127.0.0.1
    //For now: fill in manually IP of en0/en1
    super.httpConf.localAddress("192.168.1.54")
  }

  //simulate a redundant subscription
  spec {
    http("gruenfels/2016/49")
      .get("/gruenfels/2016/49/" + mailAccount1)
      .check(status.is(200))
    //.check(xpath("/html/body/status/text()").is("OK"))
  }

  spec {
    http("gruenfels/2016/49")
      .get("/gruenfels/2016/49/" + mailAccount1)
      .check(status.is(200))
    //.check(xpath("/html/body/status/text()").is("OK"))
  }

  //subscribe with different accounts for the same event
  spec {
    http("gruenfels/2016/49")
      .get("/gruenfels/2016/49/" + mailAccount2)
      .check(status.is(200))
    //.check(xpath("/html/body/status/text()").is("OK"))
  }

  spec {
    http("gruenfels/2016/49")
      .get("/gruenfels/2016/49/" + mailAccount3)
      .check(status.is(200))
    //.check(xpath("/html/body/status/text()").is("OK"))
  }

  //subscribe with default account for other events
  spec {
    http("gruenfels/2016/50")
      .get("/gruenfels/2016/50/" + mailAccount1)
      .check(status.is(200))
    //.check(xpath("/html/body/status/text()").is("OK"))
  }

  spec {
    http("gruenfels/2016/51")
      .get("/gruenfels/2016/51/" + mailAccount1)
      .check(status.is(200))
    //.check(xpath("/html/body/status/text()").is("OK"))
  }

  //subscribe with default account for 2nd tennant
  spec {
    http("mandant2/2016/1")
      .get("/mandant2/2016/1/" + mailAccount1)
      .check(status.is(200))
    //.check(xpath("/html/body/status/text()").is("OK"))
  }

}

object WinticketServiceSystemSpec {

}
