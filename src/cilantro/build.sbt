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
Global / excludeLintKeys += ThisBuild / organization

Compile / packageBin := (Compile /  packageBin).value

publishMavenStyle := true

lazy val root = project
  .in(file("."))
  .settings(
    name := "cilantro",

    // Provenance for Surveyor: META-INF/git/<artifact>.properties names the commit this
    // jar was built from (same file the Maven components write via git-commit-id).
    Compile / resourceGenerators += Def.task {
      def sh(cmd: String): String =
        scala.util.Try(scala.sys.process.Process(cmd).!!.trim).getOrElse("")
      val f = (Compile / resourceManaged).value / "META-INF" / "git" / s"${moduleName.value}_${scalaBinaryVersion.value}.properties"
      val dirty = if (sh("git status --porcelain").nonEmpty) "true" else "false"
      IO.write(
        f,
        s"""git.commit.id.full=${sh("git rev-parse HEAD")}
           |git.commit.id.describe=${sh("git describe --tags --always --dirty")}
           |git.commit.time=${sh("git log -1 --format=%cI")}
           |git.branch=${sh("git rev-parse --abbrev-ref HEAD")}
           |git.dirty=$dirty
           |git.build.version=${version.value}
           |""".stripMargin
      )
      Seq(f)
    }.taskValue,
    scalacOptions ++= Seq(
      "-no-indent",
      "-Yexplicit-nulls",
      // json4s' extract[A] API requires a Manifest; Scala 3 deprecates the
      // compiler-synthesized Manifest. The API shape is the only idiomatic
      // json4s accessor for the corpus manifests, so this one warning class
      // is silenced by message (everything else must stay warning-free).
      "-Wconf:msg=(?s).*synthesis of Manifest.*:s",
      "-deprecation",
      "-unchecked",
      "-Wunused:imports",
      "-feature"
    ),

    organization := "io.spicelabs",

    version := version.value,

    scalaVersion := scala3Version,

    libraryDependencies += "org.scalameta" %% "munit" % "1.3.5" % Test,
    libraryDependencies += "org.json4s" %% "json4s-native" % "4.0.7" % Test,
    Test / fork := true,
    Test / javaOptions += "-Xmx6g",
    Test / javaOptions += "-Xss16m"
  )

// House style (see workspace/2026_08_26_cilantro/00_style_and_ground_rules.md):
// brace format only, Option-only (no null), enforced at the compiler level.
lazy val root2 = root.settings(
  scalacOptions ++= Seq(
    "-no-indent",
    "-Yexplicit-nulls",
    // json4s' extract[A] API requires a Manifest; Scala 3 deprecates the
    // compiler-synthesized Manifest. The API shape is the only idiomatic
    // json4s accessor for the corpus manifests, so this one warning class
    // is silenced by message (everything else must stay warning-free).
    "-Wconf:msg=(?s).*synthesis of Manifest.*:s",
    "-deprecation",
    "-unchecked",
    "-Wunused:imports",
    "-feature"
  )
)

lazy val rootFinal = root2
