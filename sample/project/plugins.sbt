resolvers += "Typesafe repository" at "http://repo.typesafe.com/typesafe/releases/"

resolvers += "gravity" at "https://devstack.io/repo/gravitydev/public"

// The Play plugin
addSbtPlugin("com.typesafe.play" % "sbt-plugin" % "2.3.4")

// web plugins



addSbtPlugin("com.typesafe.sbt" % "sbt-twirl" % "1.0.2")

addSbtPlugin("com.gravitydev" % "sbt-web-gss" % "0.0.2-SNAPSHOT")

addSbtPlugin("com.gravitydev" % "sbt-web-closurejs" % "0.0.1-SNAPSHOT")

