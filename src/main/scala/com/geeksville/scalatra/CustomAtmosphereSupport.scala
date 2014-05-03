package com.geeksville.scalatra

import org.scalatra.atmosphere.AtmosphereSupport
import org.scalatra.ScalatraBase
import org.scalatra.json.JsonSupport

/**
 * Atmosphere seems to break our unit tests - this hack allows us to turn it off
 */
trait CustomAtmosphereSupport extends AtmosphereSupport { self: ScalatraBase with org.scalatra.SessionSupport with JsonSupport[_] =>

  abstract override def initialize(config: ConfigT) {
    val isTesting = ScalatraTools.isTesting
    println(s"isTesting $isTesting")
    if (!isTesting)
      super.initialize(config)
  }
}