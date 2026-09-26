ThisBuild / tlBaseVersion := "0.7" // your current series x.y

ThisBuild / organization := "io.chrisdavenport"
ThisBuild / organizationName := "Christopher Davenport"
ThisBuild / licenses := Seq(License.MIT)
ThisBuild / developers := List(
  // your GitHub handle and name
  tlGitHubDev("christopherdavenport", "Christopher Davenport")
)

ThisBuild / tlCiReleaseBranches := Seq()

val catsV = "2.13.0"
val catsEffectV = "3.7.1"
val fs2V = "3.14.0"

val circuitV = "0.7.0"
val http4sV = "0.23.37"

ThisBuild / crossScalaVersions := Seq("2.13.18", "3.3.8")
ThisBuild / scalaVersion := "3.3.8"

ThisBuild / testFrameworks += new TestFramework("munit.Framework")

lazy val `circuit-http4s` = tlCrossRootProject
  .aggregate(server, client)

lazy val server = crossProject(JVMPlatform, JSPlatform, NativePlatform)
  .crossType(CrossType.Pure)
  .in(file("server"))
  .settings(commonSettings)
  .settings(
    name := "circuit-http4s-server",
    libraryDependencies ++= Seq(
      "org.http4s" %%% "http4s-core" % http4sV % Test
    )
  ).platformsSettings(JSPlatform, NativePlatform)(
    tlVersionIntroduced := List("2.12", "2.13", "3").map(_ -> "0.4.1").toMap
  )

lazy val client = crossProject(JVMPlatform, JSPlatform, NativePlatform)
  .crossType(CrossType.Pure)
  .in(file("client"))
  .settings(commonSettings)
  .settings(
    name := "circuit-http4s-client",
    libraryDependencies ++= Seq(
      "org.http4s" %%% "http4s-client" % http4sV
    )
  ).platformsSettings(JSPlatform, NativePlatform)(
    tlVersionIntroduced := List("2.12", "2.13", "3").map(_ -> "0.4.1").toMap
  )

lazy val site = project.in(file("site"))
  .enablePlugins(TypelevelSitePlugin)
  .settings(
    laikaTheme := tlSiteHelium.value.site
      .topNavigationBar(
        homeLink = laika.helium.config.IconLink.internal(laika.ast.Path.Root / "index.md", laika.helium.config.HeliumIcon.home)
      )
      .build
  )
  .dependsOn(server.jvm, client.jvm)

// General Settings
lazy val commonSettings = Seq(
  libraryDependencies ++= Seq(
    "org.typelevel"               %%% "cats-core"                  % catsV,
    "org.typelevel"               %%% "cats-effect"                % catsEffectV,
    "co.fs2"                      %%% "fs2-io"                     % fs2V,
    "io.chrisdavenport"           %%% "circuit"                    % circuitV,

    "org.http4s" %%% "http4s-dsl" % http4sV % Test,

    "org.typelevel" %%% "munit-cats-effect" % "2.2.1" %  Test,
  )
)