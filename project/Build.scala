import sbt._
import Keys._

object ApplicationBuild extends Build {

  override def rootProject = Some(sbtWebGss) 

  lazy val sbtWebGss = Project("sbt-web-gss", file("sbt-web-gss"))
    .settings(commonSettings:_*)
    .settings(addSbtPlugin("com.typesafe.sbt" % "sbt-web" % "1.1.0"):_*)
    .settings(
      version := "0.0.2-SNAPSHOT",
      sbtPlugin := true,
      libraryDependencies ++= Seq(
        "com.github.scala-incubator.io" %% "scala-io-file" % "0.4.2",
        "com.google.closure-stylesheets" % "closure-stylesheets" % "20140426",
        "args4j" % "args4j" % "2.0.16",
        "com.google.guava" % "guava" % "12.0",
        "com.google.code.gson" % "gson" % "1.7.1",
        "com.google.code.findbugs" % "jsr305" % "1.3.9"
      ),
      resolvers ++= Seq(
        "Sonatype OSS Releases" at "http://oss.sonatype.org/content/repositories/releases/",
        "Sonatype OSS Snapshots" at "http://oss.sonatype.org/content/repositories/snapshots/"
      )
    )

  val commonResolvers = Seq(
    Resolver.file("Local ivy Repository", file("/home/alvaro/.ivy2/local/"))(Resolver.ivyStylePatterns),
    "devstack" at "https://devstack.io/repo/gravitydev/public",
    "Typesafe Repository" at "http://repo.typesafe.com/typesafe/releases/",
    Classpaths.sbtPluginSnapshots
  )

  val gravityRepo = "gravitydev" at "https://devstack.io/repo/gravitydev/public"

  val commonSettings = Seq(
    organization := "com.gravitydev",
    scalaVersion := "2.10.4",
    offline := true,
    publishTo := Some(gravityRepo),
    credentials += Credentials(Path.userHome / ".ivy2" / ".credentials"),
    publishArtifact in (Compile, packageDoc) := false,
    scalacOptions ++= Seq(
      "-deprecation", 
      "-unchecked", 
      "-Xcheckinit", 
      "-encoding", 
      "utf8", 
      "-feature", 
      "-language:postfixOps",
      "-language:implicitConversions"
    ),
    libraryDependencies ++= Seq(
      "com.typesafe.scala-logging" %% "scala-logging-slf4j" % "2.1.2"
    ),
    resolvers ++= commonResolvers
  )
}

