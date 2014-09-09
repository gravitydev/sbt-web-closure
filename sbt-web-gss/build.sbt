name := "sbt-web-gss"

organization := "com.gravitydev"

version := "0.0.2-SNAPSHOT"

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
  "com.google.closure-stylesheets" % "closure-stylesheets" % "20140426",
  "args4j" % "args4j" % "2.0.16",
  "com.google.guava" % "guava" % "12.0",
  "com.google.code.gson" % "gson" % "1.7.1",
  "com.google.code.findbugs" % "jsr305" % "1.3.9"
)

resolvers ++= Seq(
  Resolver.file("Local ivy Repository", file("/home/alvaro/.ivy2/local/"))(Resolver.ivyStylePatterns),
  "devstack" at "https://devstack.io/repo/gravitydev/public",
  "Typesafe Repository" at "http://repo.typesafe.com/typesafe/releases/",
  Classpaths.sbtPluginSnapshots,
  "Sonatype OSS Releases" at "http://oss.sonatype.org/content/repositories/releases/",
  "Sonatype OSS Snapshots" at "http://oss.sonatype.org/content/repositories/snapshots/"
)

