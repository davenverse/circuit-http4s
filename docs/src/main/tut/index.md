---
layout: home

---

# circuit-http4s - CircuitBreaker backed Http4s Middlewares [![Build Status](https://travis-ci.com/ChristopherDavenport/circuit-http4s.svg?branch=master)](https://travis-ci.com/ChristopherDavenport/circuit-http4s) [![Maven Central](https://maven-badges.herokuapp.com/maven-central/io.chrisdavenport/circuit-http4s_2.12/badge.svg)](https://maven-badges.herokuapp.com/maven-central/io.chrisdavenport/circuit-http4s_2.12)

## Quick Start

To use circuit-http4s in an existing SBT project with Scala 2.11 or a later version, add the following dependencies to your
`build.sbt` depending on your needs:

```scala
libraryDependencies ++= Seq(
  "io.chrisdavenport" %% "circuit-http4s" % "<version>"
)
```