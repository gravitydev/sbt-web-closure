name := "sample"

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

ClosureJsKeys.closureJsSourceMapLocationMappings := List(
  ((baseDirectory in Compile).value / "public").getAbsolutePath -> "/assets", // strip root path
  (sourceDirectory in Compile).value.getAbsolutePath -> ""
)

ClosureJsKeys.closureLibraryDirectory := (baseDirectory in Compile).value / "public" / "closure-library"

includeFilter in ClosureJsKeys.closureJs in Assets := "*.page.js"

mappings in Assets := (mappings in resources in Assets).value ++ (mappings in WebKeys.webModules in Assets).value
