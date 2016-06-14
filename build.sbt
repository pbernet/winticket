import com.typesafe.sbt.SbtScalariform.ScalariformKeys

import scalariform.formatter.preferences._

name          := """winticket"""
organization  := "com.winticket"
version       := "0.0.4"
scalaVersion  := "2.11.7"
scalacOptions := Seq(
  "-encoding", "UTF-8", "-target:jvm-1.8", "-deprecation",
  "-feature", "-unchecked", "-language:implicitConversions", "-language:postfixOps")


libraryDependencies ++= {
  val scalazV          = "7.2.0-M2"
  val akkaV            = "2.4.7"
  val apacheMailV      = "1.2"
  val productCollV     = "1.4.2"
  val scalaTestV       = "3.0.0-M1"
  val scalaMockV       = "3.2.2"
  val scalazScalaTestV = "0.2.3"
  Seq(
    "org.scalaz"        %% "scalaz-core"                          % scalazV,
    "com.typesafe.akka" %% "akka-http-experimental"               % akkaV,
    "com.typesafe.akka" %% "akka-stream"                          % akkaV,
    "com.typesafe.akka" %% "akka-http-spray-json-experimental"    % akkaV,
    "com.typesafe.akka" %% "akka-slf4j"                           % akkaV,
    "ch.qos.logback"     % "logback-classic"                      % "1.1.3",

    "com.typesafe.akka" %% "akka-persistence"                     % akkaV,
    "org.iq80.leveldb"            % "leveldb"                     % "0.7",
    "org.fusesource.leveldbjni"   % "leveldbjni-all"              % "1.8",

    //needed for XHTML in HTTP Response
    "com.typesafe.akka" %% "akka-http-xml-experimental"           % akkaV,

    "org.apache.commons" % "commons-email"                        % apacheMailV,
    "com.github.marklister" %% "product-collections"              % productCollV,


    "org.scalatest"     %% "scalatest"                            % scalaTestV       % "it,test",
    "org.scalamock"     %% "scalamock-scalatest-support"          % scalaMockV       % "it,test",
    "org.scalaz"        %% "scalaz-scalacheck-binding"            % scalazV          % "it,test",
    "org.typelevel"     %% "scalaz-scalatest"                     % scalazScalaTestV % "it,test",
    "com.typesafe.akka" %% "akka-http-testkit-experimental"       % "2.0.4"          % "it,test",
    //needed for experimental ScalaTest/Gatling integration for REST API Testing
    "io.gatling" % "gatling-test-framework" % "2.2.0" % "it, test"

  )
}

lazy val root = project.in(file(".")).configs(IntegrationTest)
Defaults.itSettings
scalariformSettings
Revolver.settings
enablePlugins(JavaAppPackaging)
enablePlugins(GatlingPlugin)

maintainer in Docker := "Paul Bernet <paul.bernet@gmail.com>"
dockerBaseImage := "java:8-jre"
daemonUser in Docker := "root"
dockerExposedPorts := Seq(9000)
dockerEntrypoint := Seq(
  "bin/winticket",
  "-Dconfig.resource=/production.conf")

defaultLinuxInstallLocation in Docker := "/var/app/current"
dockerExposedVolumes := Seq("/var/app/current/stage/opt/docker/target/winticket/journal", "/var/app/current/stage/opt/docker/target/winticket/snapshots" )

//needed for experimental ScalaTest/Gatling integration for REST API Testing
resolvers +=
  "Sonatype OSS Snapshots" at "https://oss.sonatype.org/content/repositories/snapshots"
resolvers += Resolver.typesafeRepo("releases")

ScalariformKeys.preferences := ScalariformKeys.preferences.value
  .setPreference(AlignSingleLineCaseStatements, true)
  .setPreference(AlignSingleLineCaseStatements.MaxArrowIndent, 100)
  .setPreference(DoubleIndentClassDeclaration, true)
  .setPreference(SpacesAroundMultiImports, false)

initialCommands := """|import scalaz._
                     |import Scalaz._
                     |import akka.actor._
                     |import akka.pattern._
                     |import akka.util._
                     |import scala.concurrent._
                     |import scala.concurrent.duration._""".stripMargin

// set the main class for 'sbt run'. Does not work yet. There seems to be a conflict with the GatlingPlugin
mainClass in(Compile, run) := Some("com.winticket.server.WinticketMicroserviceMain")

fork in run := true