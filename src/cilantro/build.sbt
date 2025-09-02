val projectName = "cilantro"
val scala3Version = "3.7.1"

ThisBuild / organization := "io.spicelabs"
ThisBuild / version := "0.0.1-SNAPSHOT" // overridden by GitHub Actions

ThisBuild / licenses := Seq(
  "MIT License" -> url("https://mit-license.org/") 
)

ThisBuild / homepage := Some(url("https://github.com/spice-labs-inc/cilantro"))
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

ThisBuild / publishTo := {
  val repo = "https://maven.pkg/github.com/spice-labs-inc/cilantro"
  Some("GitHub Package Registry" at repo)
}

credentials += Credentials(
  "GitHub Pacakge Registry",
  "maven.pkg/github.com",
  "x-access-token",
  sys.env.getOrElse("GITHUB_TOKEN", "")
)

ThisBuild / pgpPassphrase := sys.env.get("PGP_PASSPHRASE").map(_.toCharArray)
Global / excludeLintKeys += pgpPassphrase

Compile / packageBin := (Compile /  packageBin).value
val theJar = taskKey[File]("Assembles the jar for publishing")

theJar := {
  val jar = (Compile / assmebly).value
  val targetPath = targe.value / s"${projectName}-${version.value}.jar"
  IO.copyFile(jar, targetPath)
  targetPath
}

publishMavenStyle := true
publish / packagedArtifacts += (Artifact (
  projectName,
  "jar",
  "jar"
))

lazy val root = project
  .in(file("."))
  .settings(
    name := "cilantro",

    organization := "io.spicelabs",

    version := "0.1.0-SNAPSHOT",

    scalaVersion := scala3Version,

    libraryDependencies += "org.scalameta" %% "munit" % "1.0.0" % Test
  )
