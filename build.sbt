import sbtcrossproject.CrossPlugin.autoImport.{crossProject, CrossType}



val catsV = "2.6.1"
val catsEffectV = "3.2.9"
val fs2V = "3.1.3"

val circuitV = "0.5.0-M2"
val http4sV = "0.23.5"
val mapRefV = "0.2.1"

val specs2V = "4.12.12"

val scala213 = "2.13.6" 

ThisBuild / crossScalaVersions := Seq("2.12.14", scala213, "3.0.2")
ThisBuild / testFrameworks += new TestFramework("munit.Framework")

lazy val `circuit-http4s` = project.in(file("."))
  .disablePlugins(MimaPlugin)
  .aggregate(server, client, site)

lazy val server = project.in(file("server"))
  .settings(commonSettings)
  .settings(
    name := "circuit-http4s-server",
    libraryDependencies ++= Seq(
      "org.http4s" %% "http4s-core" % http4sV % Test
    )
  )

lazy val client = project.in(file("client"))
  .settings(commonSettings)
  .settings(
    name := "circuit-http4s-client",
    libraryDependencies ++= Seq(
      "org.http4s" %% "http4s-client" % http4sV
    )
  )

lazy val site = project.in(file("site"))
  .enablePlugins(DavenverseMicrositePlugin)
  .disablePlugins(MimaPlugin)
  .dependsOn(server,client)
  .settings(
    micrositeName := "circuit-http4s",
    micrositeDescription := "CircuitBreaker backed Http4s Middlewares",
    micrositeAuthor := "Christopher Davenport",
    micrositeGithubOwner := "ChristopherDavenport",
    micrositeGithubRepo := "circuit-http4s",
  )

// General Settings
lazy val commonSettings = Seq(
  libraryDependencies ++= Seq(
    "org.typelevel"               %% "cats-core"                  % catsV,
    "org.typelevel"               %% "cats-effect"                % catsEffectV,
    "co.fs2"                      %% "fs2-io"                     % fs2V,
    "io.chrisdavenport"           %% "circuit"                    % circuitV,
    "io.chrisdavenport"           %% "mapref"                     % mapRefV,

    "org.http4s" %% "http4s-dsl" % http4sV % Test,

    "org.typelevel" %% "munit-cats-effect-3" % "1.0.6" %  Test,

    // "org.specs2"                  %% "specs2-core"                % specs2V       % Test,
  )
)