package com.winticket.core

import java.net.InetAddress

import io.gatling.core.Predef._
import io.gatling.http.Predef._
import io.gatling.http.funspec.GatlingHttpFunSpec
import io.gatling.core.check.extractor.xpath._

/**
 *  This class is a Gatling-Simulation and connects directly via HTTP to the REST Interface
 *  It is started via "sbt test" (using the sbt gatling plugin)
 *
 *  Normal FlatSpec Tests (= with org.scalatest.FlatSpecLike with Matchers) must not be included in this test
 *  because the the spec(s) are executed twice...
 *
 */
class WinticketServiceSystemSpec extends GatlingHttpFunSpec {

  val baseURL = "http://localhost:9000"
  override def httpConf = {
    super.httpConf.header("Custom-Header", "gatling.io")
    super.httpConf.userAgentHeader("gatling.io")
    //bind the sockets from a specific local address instead of the default one (127.0.0.1)
    //TODO This works only for pber mac
    val localAddress = InetAddress.getByName("192.168.1.51")
    super.httpConf.localAddress(localAddress: InetAddress)
  }

  spec {
    http("gruenfels/2015/49")
      .get("/gruenfels/2015/49/paul.bernet@gmail.com")
      .check(status.is(200))
      .check(xpath("/html/body/status/text()").is("OK"))
  }

  spec {
    http("gruenfels/2015/49")
      .get("/gruenfels/2015/49/paul.bernet@gmail.com")
      .check(status.is(200))
      .check(xpath("/html/body/status/text()").is("OK"))
  }

  spec {
    http("gruenfels/2015/49")
      .get("/gruenfels/2015/49/paul.bernet@bluewin.ch")
      .check(status.is(200))
      .check(xpath("/html/body/status/text()").is("OK"))
  }

  spec {
    http("gruenfels/2015/49")
      .get("/gruenfels/2015/49/paul.bernet@earthling.net")
      .check(status.is(200))
      .check(xpath("/html/body/status/text()").is("OK"))
  }

  spec {
    http("gruenfels/2015/50")
      .get("/gruenfels/2015/50/paul.bernet@gmail.com")
      .check(status.is(200))
      .check(xpath("/html/body/status/text()").is("OK"))
  }

}

object WinticketServiceSystemSpec {

  //TODO Tests should be here
  //def h1 = css("h1")

}
