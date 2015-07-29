import scalariform.formatter.preferences._

name          := """winticket"""
organization  := "com.winticket"
version       := "0.0.1"
scalaVersion  := "2.11.7"
scalacOptions := Seq(
  "-encoding", "UTF-8", "-target:jvm-1.7", "-deprecation",
  "-feature", "-unchecked", "-language:implicitConversions", "-language:postfixOps")


libraryDependencies ++= {
  val scalazV          = "7.2.0-M2"
  val akkaStreamV      = "1.0"
  val akkaV            = "2.3.12"  //2.4-SNAPSHOT seems to work only for Java 1.8
  val jodaTimeV        = "2.8.1"
  val scalaTestV       = "3.0.0-M1"
  val scalaMockV       = "3.2.2"
  val scalazScalaTestV = "0.2.3"
  Seq(
    "org.scalaz"        %% "scalaz-core"                          % scalazV,
    "com.typesafe.akka" %% "akka-stream-experimental"             % akkaStreamV,
    "com.typesafe.akka" %% "akka-http-core-experimental"          % akkaStreamV,
    "com.typesafe.akka" %% "akka-http-spray-json-experimental"    % akkaStreamV,

    //TODO Check purpose of leveldb libs
    "com.typesafe.akka" %% "akka-persistence-experimental"        % akkaV,
    "org.iq80.leveldb"            % "leveldb"                     % "0.7",
    "org.fusesource.leveldbjni"   % "leveldbjni-all"              % "1.8",

    //needed for XHTML in HTTP Response
    "com.typesafe.akka" %% "akka-http-xml-experimental"           % akkaStreamV,
    "org.scalatest"     %% "scalatest"                            % scalaTestV       % "it,test",
    "org.scalamock"     %% "scalamock-scalatest-support"          % scalaMockV       % "it,test",
    "org.scalaz"        %% "scalaz-scalacheck-binding"            % scalazV          % "it,test",
    "org.typelevel"     %% "scalaz-scalatest"                     % scalazScalaTestV % "it,test",
    "com.typesafe.akka" %% "akka-http-testkit-experimental"       % akkaStreamV      % "it,test",
    //needed for experimental ScalaTest/Gatling integration for REST API Testing
    "io.gatling" % "gatling-test-framework" % "2.2.0-SNAPSHOT" % "it, test"

  )
}

lazy val root = project.in(file(".")).configs(IntegrationTest)
Defaults.itSettings
scalariformSettings
Revolver.settings
enablePlugins(JavaAppPackaging)
enablePlugins(GatlingPlugin)

//needed for experimental ScalaTest/Gatling integration for REST API Testing
resolvers +=
  "Sonatype OSS Snapshots" at "https://oss.sonatype.org/content/repositories/snapshots"

ScalariformKeys.preferences := ScalariformKeys.preferences.value
  .setPreference(AlignSingleLineCaseStatements, true)
  .setPreference(AlignSingleLineCaseStatements.MaxArrowIndent, 100)
  .setPreference(DoubleIndentClassDeclaration, true)

initialCommands := """|import scalaz._
                     |import Scalaz._
                     |import akka.actor._
                     |import akka.pattern._
                     |import akka.util._
                     |import scala.concurrent._
                     |import scala.concurrent.duration._""".stripMargin


fork in run := true