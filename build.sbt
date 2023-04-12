ThisBuild / tlBaseVersion := "0.4" // your current series x.y

ThisBuild / organization := "io.chrisdavenport"
ThisBuild / organizationName := "Christopher Davenport"
ThisBuild / licenses := Seq(License.MIT)
ThisBuild / developers := List(
  // your GitHub handle and name
  tlGitHubDev("christopherdavenport", "Christopher Davenport")
)

ThisBuild / tlCiReleaseBranches := Seq("main")

// true by default, set to false to publish to s01.oss.sonatype.org
ThisBuild / tlSonatypeUseLegacyHost := true


val catsV = "2.9.0"
val catsEffectV = "3.4.8"
val fs2V = "3.6.1"

val circuitV = "0.5.1"
val http4sV = "0.23.18"

val scala213 = "2.13.8"

ThisBuild / crossScalaVersions := Seq("2.12.15", scala213, "3.2.2")
ThisBuild / testFrameworks += new TestFramework("munit.Framework")

lazy val `circuit-http4s` = tlCrossRootProject
  .aggregate(server, client, site)

lazy val server = crossProject(JVMPlatform, JSPlatform, NativePlatform)
  .in(file("server"))
  .settings(commonSettings)
  .settings(
    name := "circuit-http4s-server",
    libraryDependencies ++= Seq(
      "org.http4s" %% "http4s-core" % http4sV % Test
    )
  )

lazy val client = crossProject(JVMPlatform, JSPlatform, NativePlatform)
  .in(file("client"))
  .settings(commonSettings)
  .settings(
    name := "circuit-http4s-client",
    libraryDependencies ++= Seq(
      "org.http4s" %% "http4s-client" % http4sV
    )
  )

lazy val site = project.in(file("site"))
  .enablePlugins(TypelevelSitePlugin)
  .dependsOn(server.jvm, client.jvm)

// General Settings
lazy val commonSettings = Seq(
  libraryDependencies ++= Seq(
    "org.typelevel"               %% "cats-core"                  % catsV,
    "org.typelevel"               %% "cats-effect"                % catsEffectV,
    "co.fs2"                      %% "fs2-io"                     % fs2V,
    "io.chrisdavenport"           %% "circuit"                    % circuitV,

    "org.http4s" %% "http4s-dsl" % http4sV % Test,

    "org.typelevel" %% "munit-cats-effect" % "2.0.0-M3" %  Test,

    // "org.specs2"                  %% "specs2-core"                % specs2V       % Test,
  )
)