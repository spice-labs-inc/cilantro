import sbt.internal.librarymanagement

val projectName = "cilantro"
val scala3Version = "3.7.1"


val _homepage = Some(url("https://github.com/spice-labs-inc/cilantro"))

ThisBuild / organization := "io.spicelabs"
ThisBuild / organizationName := "Spice Labs"
ThisBuild / organizationHomepage := _homepage
ThisBuild / version := "0.0.1-SNAPSHOT" // overridden by GitHub Actions
ThisBuild / description := "A scala library for manipulating Microsoft .NET PE files"
ThisBuild / licenses := Seq(
  "MIT License" -> url("https://mit-license.org/") 
)

ThisBuild / homepage := _homepage
ThisBuild / scmInfo := Some(
  ScmInfo(
    url("https://github.com/spice-labs-inc/cilantro"),
    "scm:git@github.com:spice-labs-inc/cilantro.git"
  )
)

ThisBuild / developers := List(
  Developer(
    id = "spicelabs",
    name = "Spice Labs",
    email = "engineering@spicelabs.io",
    url = url("https://github.com/spice-labs-inc")
  )
)

publishResolvers := Seq(
  Resolver.url("GitHub Package Registry", url("https://maven.pkg.github.com/spice-labs-inc/cilantro")),
  localStaging.value.get
)

publish := publishAll.value

credentials += Credentials(
  "GitHub Package Registry",
  "maven.pkg.github.com",
  "x-access-token",
  sys.env.getOrElse("GITHUB_TOKEN", "")
)

ThisBuild / pgpPassphrase := sys.env.get("PGP_PASSPHRASE").map(_.toCharArray)
Global / excludeLintKeys += pgpPassphrase

Compile / packageBin := (Compile /  packageBin).value

publishMavenStyle := true

lazy val root = project
  .in(file("."))
  .settings(
    name := "cilantro",

    organization := "io.spicelabs",

    version := version.value,

    scalaVersion := scala3Version,

    libraryDependencies += "org.scalameta" %% "munit" % "1.0.0" % Test
  )
