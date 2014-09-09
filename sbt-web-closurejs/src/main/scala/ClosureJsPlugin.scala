import sbt._, Keys._

import java.io._
import com.google.javascript.jscomp.CompilerOptions

object ClosureJsPlugin {

  val closureLibraryDir = settingKey[File]("Path to the root of the closure library")
  val sourceMapLocationMappings = settingKey[List[(String,String)]]("Mappings to use for source maps")
  val javascriptSources = settingKey[PathFinder]("Folders to search for javascript")

  def ClosureJsAdvancedCompiler = (
    state, 
    sourceDirectory in Compile, 
    resourceManaged in Compile, 
    cacheDirectory, 
    closureCompilerOptions, 
    javascriptEntryPoints,
    javascriptSources,
    closureLibraryDir,
    sourceMapLocationMappings
  ) map {(state, src, resources, cache, options, entryPoints, sources, closureLibraryDir, sourceMapLocationMappings) =>
    val cacheFile = cache / "closurejs"

    val currentInfos = ((src ** "*.js") +++ (sources ** "*.js")).get.map(f => f -> FileInfo.lastModified(f)).toMap

    val naming: (String, Boolean) => String = {(name, min) => 
      name.replace(".js", if (min) ".min.js" else ".js") 
    }

    val (previousRelation, previousInfo) = Sync.readInfo(cacheFile)(FileInfo.lastModified.format)

    if (previousInfo != currentInfos) {

      //a changed file can be either a new file, a deleted file or a modified one
      lazy val changedFiles: Seq[File] = currentInfos.filter(e => !previousInfo.get(e._1).isDefined || previousInfo(e._1).lastModified < e._2.lastModified).map(_._1).toSeq ++ previousInfo.filter(e => !currentInfos.get(e._1).isDefined).map(_._1).toSeq

      //erease dependencies that belong to changed files
      val dependencies = previousRelation.filter((original, compiled) => changedFiles.contains(original))._2s
      dependencies.foreach(IO.delete)

      /**
       * If the given file was changed or
       * if the given file was a dependency,
       * otherwise calculate dependencies based on previous relation graph
       */
      val generated: Seq[(File, java.io.File)] = (entryPoints x relativeTo(Seq(src / "assets"))).flatMap {
        case (sourceFile, name) => {
          if (changedFiles.contains(sourceFile) || dependencies.contains(new File(resources, "public/" + naming(name, false)))) {
            val (debug, min, dependencies, sourceMapCode) = try {
              ClosureJsCompiler.compile(sourceFile, sources, closureLibraryDir, sourceMapLocationMappings) 
            } catch {
              case e: AssetCompilationException => throw PlaySourceGenerators.reportCompilationError(state, e)
            }
            val out = new File(resources, "public/" + naming(name, false))
            IO.write(out, debug)
            (dependencies ++ Seq(sourceFile)).toSet[File].map(_ -> out) ++ min.map { minified =>
              val outMin = new File(resources, "public/" + naming(name, true))
              IO.write(outMin, minified + "\n//@ sourceMappingURL=/assets/javascripts/"+ outMin.getName + ".map")
              val sourceMap = new File(resources, "public/" + naming(name, true) + ".map")
              IO.write(sourceMap, sourceMapCode)
              (dependencies ++ Seq(sourceFile)).map(_ -> outMin) ++
              (dependencies ++ Seq(sourceFile)).map(_ -> sourceMap) ++
              (Seq(sourceFile)).map(_ -> out)
            }.getOrElse(Nil)
          } else {
            previousRelation.filter((original, compiled) => original == sourceFile)._2s.map(sourceFile -> _)
          }
        }
      }

      //write object graph to cache file 
      Sync.writeInfo(cacheFile,
        Relation.empty[File, File] ++ generated,
        currentInfos)(FileInfo.lastModified.format)

      // Return new files
      generated.map(_._2).distinct.toList

    } else {
      // Return previously generated files
      previousRelation._2s.toSeq
    }

  }

  def settings = Seq(
    sourceMapLocationMappings := List(
      ((baseDirectory in Compile).value / "public").getAbsolutePath -> "/assets", // strip root path
      (sourceDirectory in Compile).value.getAbsolutePath -> ""
    ),
    resourceGenerators in Compile <+= ClosureJsAdvancedCompiler,
    closureLibraryDir := (baseDirectory in Compile).value / "public" / "closure-library",
    javascriptEntryPoints := javascriptEntryPoints.value +++ ((sourceDirectory in Compile).value / "assets" / "javascripts" * "*.js"),
    javascriptSources := {
      var public = (baseDirectory in Compile).value / "public"
      (
        (public / "closure-library" ** "*.js") +++
        (public / "javascripts" / "gravity" ** "*.js") +++
        (public / "javascripts" / "devstack" ** "*.js")
      ) --- (public ** "*_test.js") // skip tests
    }
  )

}

