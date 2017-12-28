package com.winticket.core

import com.winticket.core.WinticketServiceSystemSpec._
import io.gatling.core.Predef._
import io.gatling.http.Predef._
import io.gatling.http.funspec.GatlingHttpFunSpec
import org.scalatest.FlatSpecLike

/**
 * This class is a Gatling-Simulation and connects directly via HTTP to the REST Interface
 *
 * This test is started (via the sbt gatling plugin):
 * sbt -Dconfig.resource=/production.conf gatling:test
 *
 * Note that:
 *  * The WinticketMicroserviceMain must be started and the URLs must match uploaded drawings
 *  * The production.conf config file is used for the emails
 *
 */
class WinticketServiceSystemSpec extends GatlingHttpFunSpec with FlatSpecLike with Config {

  val baseURL = baseURLConfig
  override def httpConf = {
    super.httpConf.header("Custom-Header", "gatling.io")
    super.httpConf.userAgentHeader("gatling.io")
    //For testing on localhost:
    //Bind the sockets from a specific local address instead of 127.0.0.1
    //Take IP from en0/en1
    super.httpConf.localAddress("192.168.1.36")
  }

  "Traditional unit tests" should "work like they normally do" in {
    assert(true)
  }

  //simulate a redundant subscription -> only one per EMail will be used for drawing
  spec {
    http("gruenfels/2018/10")
      .get("/gruenfels/2018/10/" + mailAccount1)
      .check(confirmationText.exists)
      .check(status.is(200))
  }

  spec {
    http("gruenfels/2018/10")
      .get("/gruenfels/2018/10/" + mailAccount1)
      .check(confirmationText.exists)
      .check(status.is(200))
  }

  //subscribe with different accounts for the same event
  spec {
    http("gruenfels/2018/10")
      .get("/gruenfels/2018/10/" + mailAccount2)
      .check(confirmationText.exists)
      .check(status.is(200))
  }

  spec {
    http("gruenfels/2018/10")
      .get("/gruenfels/2018/10/" + mailAccount3)
      .check(confirmationText.exists)
      .check(status.is(200))
  }

  //subscribe with default account to not existing drawings -> The system currently accepts these requests
  spec {
    http("gruenfels/2017/50")
      .get("/gruenfels/2017/50/" + mailAccount1)
      .check(confirmationText.exists)
      .check(status.is(200))
  }

  //subscribe with default account for 2nd tennant
  spec {
    http("mandant2/2017/49")
      .get("/mandant2/2017/49/" + mailAccount1)
      .check(confirmationText.exists)
      .check(status.is(200))
  }

}

object WinticketServiceSystemSpec {
  def h1 = css("h1")
  def confirmationText = css("#confirmation")
}
