package com.winticket.util

import java.io.{File, FileInputStream, InputStream, InputStreamReader}

object DataLoaderHelper {

  def readFromResource(eventsFilePath: String) = {
    val stream: InputStream = getClass.getResourceAsStream(eventsFilePath)
    new InputStreamReader(stream, "UTF-8")
  }

  def readFromFile(file: File) = {
    import sys.process._
    //TODO Remove last (blank) line/char - leads to an error in the import...
    Seq("sed", "-i", ".bak", "1,4d", file.getAbsolutePath)!;
    Seq("sed", "-i", ".bak", "$ d", file.getAbsolutePath)!;

    val inputStream = new FileInputStream(file)
    new InputStreamReader(inputStream)
  }
}
