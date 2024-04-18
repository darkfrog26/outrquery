import sbt.Keys._
import sbt._

object ScalaRelationalBuild extends Build {
  import Dependencies._

  lazy val root = Project(
    id = "root",
    base = file(".")
  ).settings(
    name := "ScalaRelational",
    publish := {},
    publishArtifact := false,
    crossScalaVersions := Details.scalaVersions
  )
   .aggregate(core, macros, h2, mariadb, postgresql, mapper, versioning)
  lazy val core = project("core").withDependencies(enumeratum, logging, hikariCP, scalaTest, reactify).settings(
    libraryDependencies <+= scalaVersion("org.scala-lang" % "scala-reflect" % _)
  )
  lazy val macros = project("macros").withDependencies(scalaTest).dependsOn(core)
  lazy val h2 = project("h2").withDependencies(h2database, scalaTest).dependsOn(core, core % "test->test")
  lazy val mariadb = project("mariadb").withDependencies(mariadbdatabase).dependsOn(core, core % "test->test")
  lazy val postgresql = project("postgresql").withDependencies(postgresqldatabase).dependsOn(core, core % "test->test")
    .configs(PGSslTest)
    .settings(inConfig(PGSslTest)(Defaults.testTasks): _*)
    .settings(
      testOptions in Test := Seq(Tests.Filter(pgRegFilter)),
      testOptions in PGSslTest := Seq(Tests.Filter(pgSslFilter))
     )
  lazy val PGSslTest = config("pgssl") extend Test
  def pgRegFilter(name: String): Boolean = (name endsWith "Spec") && !pgSslFilter(name)
  def pgSslFilter(name: String):Boolean = name endsWith "SslSpec"

  lazy val mapper = project("mapper").withDependencies(scalaTest).dependsOn(core, macros, h2 % "test->test")
  lazy val versioning = project("versioning").withDependencies(scalaTest).dependsOn(core, h2 % "test->test")

  private def project(projectName: String) = Project(id = projectName, base = file(projectName)).settings(
    name := s"${Details.name}-$projectName",
    version := Details.version,
    organization := Details.organization,
    scalaVersion := Details.scalaVersion,
    crossScalaVersions := Details.scalaVersions,
    fork := true,
    scalacOptions ++= Seq("-unchecked", "-deprecation", "-feature"),
    resolvers ++= Seq(
      Resolver.sonatypeRepo("snapshots"),
      Resolver.sonatypeRepo("releases"),
      Resolver.typesafeRepo("releases")
    ),
    addCompilerPlugin("org.scalamacros" % "paradise" % "2.1.0" cross CrossVersion.full),
    publishArtifact in Test := false,
    pomExtra := <url>{Details.url}</url>
      <licenses>
        <license>
          <name>{Details.licenseType}</name>
          <url>{Details.licenseURL}</url>
          <distribution>repo</distribution>
        </license>
      </licenses>
      <scm>
        <developerConnection>scm:{Details.repoURL}</developerConnection>
        <connection>scm:{Details.repoURL}</connection>
        <url>{Details.projectURL}</url>
      </scm>
      <developers>
        <developer>
          <id>{Details.developerId}</id>
          <name>{Details.developerName}</name>
          <url>{Details.developerURL}</url>
        </developer>
      </developers>
  )

  implicit class EnhancedProject(project: Project) {
    def withDependencies(modules: ModuleID*): Project = project.settings(libraryDependencies ++= modules)
  }
}

object Details {
  val organization = "org.scalarelational"
  val name = "scalarelational"
  val version = "1.3.15"
  val url = "http://outr.com"
  val licenseType = "Apache 2.0"
  val licenseURL = "http://opensource.org/licenses/Apache-2.0"
  val projectURL = "https://github.com/darkfrog26/scalarelational"
  val repoURL = "https://github.com/darkfrog26/scalarelational.git"
  val developerId = "darkfrog"
  val developerName = "Matt Hicks"
  val developerURL = "http://matthicks.com"

  val scalaVersion = "2.12.19"
  val scalaVersions = List("2.12.19", "2.11.12")
}

object Dependencies {
  val hikariCP = "com.zaxxer" % "HikariCP" % "3.3.1"
  val h2database = "com.h2database" % "h2" % "1.4.197"
  val mariadbdatabase = "mysql" % "mysql-connector-java" % "8.0.15"
  val postgresqldatabase = "org.postgresql" % "postgresql" % "42.2.5"
  val scalaTest = "org.scalatest" %% "scalatest" % "3.0.5" % "test"
  val reactify = "com.outr" %%  "reactify" % "3.0.3"
  val enumeratum = "com.beachape" %% "enumeratum" % "1.5.13"
  val logging = "com.outr" %% "scribe-slf4j" % "2.7.0"
}
