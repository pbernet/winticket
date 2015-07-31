package com.winticket.core

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
  override def httpConf = super.httpConf.header("Client", "gatling.io")

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
