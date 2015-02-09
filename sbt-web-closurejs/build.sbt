name := "sbt-web-closurejs"

organization := "com.gravitydev"

version := "0.0.3-es6-SNAPSHOT"

sbtPlugin := true

scalaVersion := "2.10.4"

offline := true

publishTo := Some("devstack" at "https://devstack.io/repo/gravitydev/public")

credentials += Credentials(Path.userHome / ".ivy2" / ".credentials")

publishArtifact in (Compile, packageDoc) := false

addSbtPlugin("com.typesafe.sbt" % "sbt-web" % "1.1.0")

scalacOptions ++= Seq(
  "-deprecation", 
  "-unchecked", 
  "-Xcheckinit", 
  "-encoding", 
  "utf8", 
  "-feature", 
  "-language:postfixOps",
  "-language:implicitConversions"
)

libraryDependencies ++= Seq(
  "com.typesafe.scala-logging" %% "scala-logging-slf4j" % "2.1.2",
  "com.github.scala-incubator.io" %% "scala-io-file" % "0.4.2",
  "com.typesafe.play" %% "play-ws" % "2.3.7",
  "args4j" % "args4j" % "2.0.16",
  "com.google.guava" % "guava" % "18.0",
  "com.google.code.gson" % "gson" % "2.2.4",
  "com.google.code.findbugs" % "jsr305" % "1.3.9",
  "com.google.javascript" % "closure-compiler" % "v20141023",
  "org.scalatest" %% "scalatest" % "2.2.1" % "test"
)

resolvers ++= Seq(
  "devstack" at "https://devstack.io/repo/gravitydev/public",
  "Typesafe Repository" at "http://repo.typesafe.com/typesafe/releases/",
  Classpaths.sbtPluginSnapshots,
  "Sonatype OSS Releases" at "http://oss.sonatype.org/content/repositories/releases/",
  "Sonatype OSS Snapshots" at "http://oss.sonatype.org/content/repositories/snapshots/"
)

