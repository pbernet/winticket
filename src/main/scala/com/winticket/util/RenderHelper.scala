package com.winticket.util

import java.io.InputStream


/**
 * This is an simple solution for the current needs. Remarks:
 *  - May have side effects and may not scale
 *  - Could be replaced by a custom directive
 */

object RenderHelper {

  private val tokenIdentifier = "%%"

  def getFromResourceRenderedWith(resource: String, replaceMap: Map[String, String]): String = {

    val stream: InputStream = getClass.getResourceAsStream(resource )
    var finalContent = scala.io.Source.fromInputStream(stream).getLines.mkString
    replaceMap.foreach{pair => finalContent = finalContent.replace(tokenIdentifier + pair._1 + tokenIdentifier, pair._2)}
    finalContent
  }
}
