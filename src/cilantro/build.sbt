val scala3Version = "3.7.1"

lazy val root = project
  .in(file("."))
  .settings(
    name := "cilantro",

    organization := "io.spicelabs",

    version := "0.1.0-SNAPSHOT",

    scalaVersion := scala3Version,

    libraryDependencies += "org.scalameta" %% "munit" % "1.0.0" % Test
  )
