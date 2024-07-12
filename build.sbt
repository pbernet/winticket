name          := """winticket"""
organization  := "com.winticket"
version       := "1.2.7"
scalaVersion  := "2.12.19"
scalacOptions := Seq(
  "-encoding", "UTF-8", "-target:jvm-1.8", "-deprecation",
  "-feature", "-unchecked", "-language:implicitConversions", "-language:postfixOps")


libraryDependencies ++= {
  val scalazV          = "7.2.18"
  val akkaV            = "2.5.22"
  val akkaHttpV        = "10.1.8"
  val alpakkaVersion   = "1.0.2"
  val apacheMailV      = "1.2"
  val productCollV     = "1.4.5"
  val scalaTestV       = "3.0.4"
  val scalaMockV       = "3.6.0"
  val scalazScalaTestV = "1.1.2"
  Seq(
    "org.scalaz"        %% "scalaz-core"                          % scalazV,
    "com.typesafe.akka" %% "akka-http-core"                       % akkaHttpV,
    "com.typesafe.akka" %% "akka-http"                            % akkaHttpV,
    "com.typesafe.akka" %% "akka-stream"                          % akkaV,
    "com.typesafe.akka" %% "akka-http-spray-json"                 % akkaHttpV,
    "com.typesafe.akka" %% "akka-slf4j"                           % akkaV,
    "ch.qos.logback"     % "logback-classic"                      % "1.2.3",
    "com.lightbend.akka" %% "akka-stream-alpakka-xml"             % alpakkaVersion,

    "com.typesafe.akka" %% "akka-persistence"                     % akkaV,
    //TODO Only one of those two needs to be used
    //see https://github.com/akka/akka/issues/22816
    "org.iq80.leveldb"            % "leveldb"                     % "0.9",
    "org.fusesource.leveldbjni"   % "leveldbjni-all"              % "1.8",

    //needed for XHTML in HTTP Response
    "com.typesafe.akka" %% "akka-http-xml"                        % akkaHttpV,

    "org.apache.commons" % "commons-email"                        % apacheMailV,
    "com.github.marklister" %% "product-collections"              % productCollV,


    "org.scalatest"     %% "scalatest"                            % scalaTestV       % "it,test",
    "org.scalamock"     %% "scalamock-scalatest-support"          % scalaMockV       % "it,test",
    "org.scalaz"        %% "scalaz-scalacheck-binding"            % scalazV          % "it,test",
    "org.typelevel"     %% "scalaz-scalatest"                     % scalazScalaTestV % "it,test",
    "com.typesafe.akka" %% "akka-http-testkit"                    % akkaHttpV        % "it,test",
    //needed for experimental ScalaTest/Gatling integration for REST API Testing
    "io.gatling" % "gatling-test-framework" % "2.3.0" % "it, test",
    //MailChimp API, not maintained anymore...
    "com.ecwid" %	"maleorang" %	"3.0-0.9.6",
    "org.apache.commons" % "commons-text" % "1.11.0",
    "io.circe" %% "circe-core" % "0.14.6",
    "io.circe" %% "circe-generic" % "0.14.6",
    "io.circe" %% "circe-parser" % "0.14.6"
  )
}

lazy val root = project.in(file(".")).configs(IntegrationTest)
Defaults.itSettings
Revolver.settings
enablePlugins(JavaAppPackaging)
enablePlugins(GatlingPlugin)

maintainer in Docker := "Paul Bernet <paul.bernet@gmail.com>"
dockerBaseImage := "java:8-jre"
daemonUser in Docker := "root"
dockerExposedPorts := Seq(9000)
dockerEntrypoint := Seq(
  "bin/winticket-microservice-main",
  "-Dconfig.resource=/production.conf")

defaultLinuxInstallLocation in Docker := "/var/app/current"
dockerExposedVolumes := Seq("/var/app/current/stage/opt/docker/target/winticket/journal", "/var/app/current/stage/opt/docker/target/winticket/snapshots" )

//needed for experimental ScalaTest/Gatling integration for REST API Testing
resolvers += "Sonatype OSS Snapshots" at "https://oss.sonatype.org/content/repositories/snapshots"


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