package com.winticket.core

import java.net.InetAddress

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

  val baseURL = "http://localhost:9000"
  override def httpConf = {
    super.httpConf.header("Custom-Header", "gatling.io")
    super.httpConf.userAgentHeader("gatling.io")
    //bind the sockets from a specific local address instead of the default one (127.0.0.1)
    //TODO This works only for pber mac
    val localAddress = InetAddress.getByName("192.168.1.51")
    super.httpConf.localAddress(localAddress: InetAddress)
  }

  //simulate a redundant subscription
  spec {
    http("gruenfels/2015/49")
      .get("/gruenfels/2015/49/" + mailAccount1)
      .check(status.is(200))
      .check(xpath("/html/body/status/text()").is("OK"))
  }

  spec {
    http("gruenfels/2015/49")
      .get("/gruenfels/2015/49/" + mailAccount1)
      .check(status.is(200))
      .check(xpath("/html/body/status/text()").is("OK"))
  }

  //subscribe with different accounts for the same event
  spec {
    http("gruenfels/2015/49")
      .get("/gruenfels/2015/49/" + mailAccount2)
      .check(status.is(200))
      .check(xpath("/html/body/status/text()").is("OK"))
  }

  spec {
    http("gruenfels/2015/49")
      .get("/gruenfels/2015/49/" + mailAccount3)
      .check(status.is(200))
      .check(xpath("/html/body/status/text()").is("OK"))
  }

  //subscribe with default account for other events
  spec {
    http("gruenfels/2015/50")
      .get("/gruenfels/2015/50/" + mailAccount1)
      .check(status.is(200))
      .check(xpath("/html/body/status/text()").is("OK"))
  }

  spec {
    http("gruenfels/2015/51")
      .get("/gruenfels/2015/51/" + mailAccount1)
      .check(status.is(200))
      .check(xpath("/html/body/status/text()").is("OK"))
  }

  spec {
    http("gruenfels/2015/52")
      .get("/gruenfels/2015/52/" + mailAccount1)
      .check(status.is(200))
      .check(xpath("/html/body/status/text()").is("OK"))
  }

  spec {
    http("gruenfels/2015/53")
      .get("/gruenfels/2015/53/" + mailAccount1)
      .check(status.is(200))
      .check(xpath("/html/body/status/text()").is("OK"))
  }

  //subscribe with default account for 2nd tennant
  spec {
    http("mandant2/2015/1")
      .get("/mandant2/2015/1/" + mailAccount1)
      .check(status.is(200))
      .check(xpath("/html/body/status/text()").is("OK"))
  }

}

object WinticketServiceSystemSpec {

}
