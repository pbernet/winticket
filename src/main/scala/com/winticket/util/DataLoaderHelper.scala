package com.winticket.util

import java.io.{File, FileInputStream, InputStreamReader}

object DataLoaderHelper {

  def readFromResource(eventsFilePath: String) = {
    val inputStream = getClass.getResourceAsStream(eventsFilePath)
    new InputStreamReader(inputStream, "UTF-8")
  }

  def readFromFile(file: File) = {
    val fileInputStream = new FileInputStream(file)
    new InputStreamReader(fileInputStream, "UTF-8")
  }
}
