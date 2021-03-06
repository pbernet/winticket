package com.winticket.core

import com.winticket.server.IpInfo
import spray.json.DefaultJsonProtocol

trait Protocol extends DefaultJsonProtocol {
  implicit val ipInfoFormat = jsonFormat10(IpInfo.apply)
}
