name := """sample"""

version := "1.0-SNAPSHOT"

lazy val root = (project in file(".")).enablePlugins(PlayScala)

scalaVersion := "2.11.1"

excludeFilter in GssKeys.gss in Assets := new SimpleFileFilter(_.getParentFile.getName == "includes")

libraryDependencies ++= Seq(
  jdbc,
  anorm,
  cache,
  ws
)
