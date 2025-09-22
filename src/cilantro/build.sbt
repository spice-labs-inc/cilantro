val projectName = "cilantro"
val scala3Version = "3.7.1"


val _homepage = Some(url("https://github.com/spice-labs-inc/cilantro"))

// This chunk of info is used for the pom file
ThisBuild / organization := "io.spicelabs"
ThisBuild / organizationName := "Spice Labs"
ThisBuild / organizationHomepage := _homepage
ThisBuild / version := "0.0.1-SNAPSHOT" // overridden by GitHub Actions
ThisBuild / description := "A scala library for manipulating Microsoft .NET PE files"
ThisBuild / licenses := Seq(
  "MIT License" -> url("https://mit-license.org/") 
)

// This is the Source Code Management info for maven
ThisBuild / homepage := _homepage
ThisBuild / scmInfo := Some(
  ScmInfo(
    url("https://github.com/spice-labs-inc/cilantro"),
    "scm:git@github.com:spice-labs-inc/cilantro.git"
  )
)

// This is the developer information
ThisBuild / developers := List(
  Developer(
    id = "spicelabs",
    name = "Spice Labs",
    email = "engineering@spicelabs.io",
    url = url("https://github.com/spice-labs-inc")
  )
)

val _mavenCentral = "maven-central"
val _isMavenCentralPublish = sys.env.getOrElse("PUBLISHING_DESTINATION", _mavenCentral) == _mavenCentral
val repo = "https://maven.pkg.github.com/spice-labs-inc/cilantro"
val githubResolver = Some("GitHub Package Registry" at repo)

ThisBuild / publishTo := {
  val log = sLog.value
  if (_isMavenCentralPublish) {
    log.info("setting publishTo to localStaging")
    localStaging.value
  } else {
    log.info("setting publishTo to githubResolver")
    githubResolver
  }
}

credentials += Credentials(
  "GitHub Package Registry",
  "maven.pkg.github.com",
  "x-access-token",
  sys.env.getOrElse("GITHUB_TOKEN", "")
)

// make the PGP_PASSPHRASE available
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
