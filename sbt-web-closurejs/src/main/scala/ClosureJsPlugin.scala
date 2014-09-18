package com.gravitydev.sbt.closurejs

import sbt._, Keys._

import com.typesafe.sbt.web._
import java.io._
import com.google.javascript.jscomp.CompilerOptions
import com.typesafe.sbt.web.incremental.{syncIncremental, OpResult, OpSuccess, OpFailure, toStringInputHasher, OpInputHasher, OpInputHash}
import scala.collection.JavaConverters._
import xsbti.{Severity, Problem}
import sbinary.{Input, Output, Format}

object Import {
  object ClosureJsKeys {
    val closureJs = taskKey[Seq[File]]("Invoke the closure-stylesheets compiler.")
    val closureLibraryDirectory = settingKey[File]("Path to the root of the closure library")
    val closureJsSourceMapLocationMappings = settingKey[List[(String,String)]]("Mappings to use for source maps")
    val closureJsPrettyPrint = settingKey[Boolean]("Pretty print")
    val closureJsPseudoNames = settingKey[Boolean]("Pseudo names")
  }
}
  
object ClosureJsPlugin extends AutoPlugin {
  override def requires = SbtWeb
  override def trigger = AllRequirements
  val autoImport = Import
  
  import SbtWeb.autoImport._
  import WebKeys._
  import autoImport.ClosureJsKeys._

  val closureJsUnscopedSettings = Seq(
    includeFilter := "*.js"
  )

  override def projectSettings = 
    Seq(
      // task
      closureJs in Assets := compileClosureJs.value,

      // adding generated resources to the managedResources
      managedResourceDirectories in Assets += (resourceManaged in closureJs in Assets).value,
      resourceManaged in closureJs in Assets := webTarget.value / closureJs.key.label / "main",
      (resourceGenerators in Assets) <+= closureJs
    ) ++
    inTask(closureJs)(
      inConfig(Assets)(closureJsUnscopedSettings) ++
      Seq(
        moduleName := "closure-js"
      )
    ) ++ 
    Seq(
      closureJs := (closureJs in Assets).value,
      closureJs in Assets := (closureJs in Assets).dependsOn(webModules in Assets).value,
      closureJsPrettyPrint := false,
      closureJsPseudoNames := false
    )
  
  /*
   * For reading/writing binary representations of files.
   */
  private implicit object FileFormat extends Format[File] {
    import sbinary.DefaultProtocol._

    def reads(in: Input): File = file(StringFormat.reads(in))

    def writes(out: Output, fh: File) = StringFormat.writes(out, fh.getAbsolutePath)
  }
  
  def compileClosureJs: Def.Initialize[Task[Seq[File]]] = Def.task {
    val log = streams.value.log
    val sourceDir = (unmanagedSources in closureJs in Assets).value
    
    // all js files must be included,
    // imports must be excluded later
    val entryPoints = sourceDir ** (includeFilter in closureJs in Assets).value
    val librarySources = (sourceDir ** "*.js") --- entryPoints
    val sources = (
      entryPoints +++
      librarySources +++
      (closureLibraryDirectory.value ** ("*.js" -- "*_test.js"))
    ).get

    val target = (resourceManaged in closureJs in Assets).value

    log.info("Compiling Closure JS files: " + sources.size)

    val hasher = OpInputHasher[File] {op =>
      def hashFile (f: File) = f.getAbsolutePath + ":" + f.lastModified

      OpInputHash.hashString(hashFile(op) + "," + ClosureJsCompiler.dependencies(op, sources).map(hashFile _).mkString(","))
    }

    val res = syncIncremental[File, (List[File], List[Problem])]((streams in Assets).value.cacheDirectory / "run", entryPoints.get) {ops =>
      val pathsMap = ops map (x => 
        (x.absolutePath.stripPrefix(sourceDir.head.absolutePath).stripSuffix(".js") + ".compiled.js") -> x
      )
      log.info("Compiling " + ops.size + " Closure JS files")

      val errs = java.util.Collections.synchronizedList(new java.util.ArrayList[Problem]())

      val resx = (pathsMap.par.map {
        case (path, source) if (excludeFilter in closureJs in Assets).value.accept(source) => source -> OpSuccess(Set.empty, Set.empty)
        case (path, source) => source -> {
          val (compiled: String, deps: List[File], errorManager) = ClosureJsCompiler.compile(
            source, 
            ClosureJsCompiler.dependencies(source, sources),
            closureLibraryDirectory.value,
            closureJsSourceMapLocationMappings.value,
            closureJsPrettyPrint.value,
            closureJsPseudoNames.value
          )
          val result = target / path
          IO.write(target / path, compiled)

          for ((err, isError) <- errorManager.getErrors().map(x => (x,true)) ++ errorManager.getWarnings().map(x => (x,false))) {
            // sometimes errorManager returns null
            if (err != null) {
              errs.add( 
                new LineBasedProblem(
                  err.description, 
                  if (isError) Severity.Error else Severity.Warn,
                  err.getLineNumber+1,
                  err.getCharno,
                  "TODO: line content", 
                  //new File(err.sourceName)
                  source
                )
              )
            }
          }
          if (errorManager.getErrorCount > 0) OpFailure
          else OpSuccess(Set(source), Set(result))
        }
      }.seq.toMap)

      resx -> resx.toList.map{
        case (_,OpSuccess(filesRead, filesWritten)) => (filesWritten, errs.asScala.toList)
        case _ => (Nil, errs.asScala.toList)
      }.foldLeft((List[File](), List[Problem]()))((a,b) => (a._1 ++ b._1, a._2 ++ b._2))
    }(hasher)

    CompileProblems.report((reporter in closureJs).value, res._2._2)

    import Cache.seqFormat

    (closureJs in Assets).previous.getOrElse(Nil) ++ res._1
  }

}

