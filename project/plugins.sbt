resolvers += Classpaths.sbtPluginReleases

addSbtPlugin("org.scalariform" % "sbt-scalariform" % "1.6.0")
addSbtPlugin("com.sksamuel.scapegoat" %% "sbt-scapegoat" % "1.0.0")
addSbtPlugin("org.scalastyle" %% "scalastyle-sbt-plugin" % "0.7.0")
addSbtPlugin("org.scoverage" % "sbt-scoverage" % "1.2.0")
addSbtPlugin("io.spray" %% "sbt-revolver" % "0.7.2")
addSbtPlugin("com.typesafe.sbt" %% "sbt-native-packager" % "1.0.6")
addSbtPlugin("io.gatling" % "gatling-sbt" % "2.1.6")