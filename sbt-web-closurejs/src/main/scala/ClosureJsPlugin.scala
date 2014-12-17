package com.gravitydev.sbt.closurejs

import sbt._, Keys._

import com.typesafe.sbt.web._
import java.io.File
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

object UncompiledJsFileFilter extends FileFilter {
  override def accept (file: File): Boolean = !HiddenFileFilter.accept(file) && file.getName.endsWith(".js")
}
  
object ClosureJsPlugin extends AutoPlugin {
  override def requires = SbtWeb
  override def trigger = AllRequirements
  val autoImport = Import
  
  import SbtWeb.autoImport._
  import WebKeys._
  import autoImport.ClosureJsKeys._

  override def projectSettings = Seq(
    closureJs := compileClosureJs.value,
    managedResourceDirectories in Assets += (resourceManaged in closureJs in Assets).value,
    resourceManaged in closureJs in Assets := webTarget.value / closureJs.key.label / "main",
    (sourceGenerators in Assets) <+= closureJs,
    (includeFilter in closureJs in Assets) := UncompiledJsFileFilter,
    (excludeFilter in closureJs in Assets) := HiddenFileFilter,
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
    val entryPointsFinder = sourceDir ** (includeFilter in closureJs in Assets).value
    val librarySourcesFinder = (sourceDir ** "*.js") --- entryPointsFinder

    val entryPoints = entryPointsFinder.get
    val librarySources = librarySourcesFinder.get

    log.info("EntryPoints: " + entryPoints)
    log.info("LibrarySources: " + librarySources)

    val target = (resourceManaged in closureJs in Assets).value

    log.info("Conditionally compiling " + entryPoints.size + " js files, including " + librarySources.size + " library files")

    val hasher = OpInputHasher[File] {op =>
      def hashFile (f: File) = f.getAbsolutePath + ":" + f.lastModified

      OpInputHash.hashString(hashFile(op) + "," + ClosureJsCompiler2.dependencies(op, librarySources).map(hashFile _).mkString(","))
    }

    // (files produced, (files produced, problems))
    val res: (Set[File], (List[File], List[Problem])) = syncIncremental[File, (List[File], List[Problem])]((streams in Assets).value.cacheDirectory / "run", entryPoints.get) {ops =>

      log.info("Ops: " + ops)
      
      val pathsMap: Seq[(String,File)] = ops map (x => 
        //(x.absolutePath.stripPrefix(sourceDir.head.absolutePath).stripSuffix(".js") + ".compiled.js") -> x
        x.absolutePath.stripPrefix(sourceDir.head.absolutePath) -> x
      )
      
      log.info("Actually compiling " + ops.size + " js files, including " + librarySources.get.size + " library files")

      val errs = java.util.Collections.synchronizedList(new java.util.ArrayList[Problem]())

      val resx: Map[File,OpResult] = (pathsMap.par.map {
        case (path, source) if (excludeFilter in closureJs in Assets).value.accept(source) => source -> OpSuccess(Set.empty, Set.empty)
        case (path, source) => source -> {
          val (compiled: String, deps: List[File], errors) = ClosureJsCompiler2.compile(
            source, 
            ClosureJsCompiler2.dependencies(source, librarySources),
            closureJsPrettyPrint.value,
            closureJsPseudoNames.value
          )
          val result = target / path
          IO.write(target / path, compiled)

          val fatalErrors = errors.collect{case x:LineFatal => x}
          val warnings = errors.collect{case x:LineWarning => x} 

          for ((err, isError) <- fatalErrors.map(x => (x,true)) ++ warnings.map(x => (x,false))) {
            errs.add( 
              new LineBasedProblem(
                err.msg, 
                if (isError) Severity.Error else Severity.Warn,
                err.lineno+1,
                err.charno,
                err.line, 
                source
              )
            )
          }
          if (errors.nonEmpty) OpFailure
          else OpSuccess(Set(source) ++ librarySources.toSet, Set(result))
        }
      }.seq.toMap)

      val resx2: (Map[File,OpResult], (List[File], List[Problem])) = resx -> resx.toList.map{
        case (_,OpSuccess(filesRead, filesWritten)) => (filesWritten, errs.asScala.toList)
        case _ => (Nil, errs.asScala.toList)
      }.foldLeft((List[File](), List[Problem]()))((a,b) => (a._1 ++ b._1, a._2 ++ b._2))

      resx2
    }(hasher)

    CompileProblems.report((reporter in closureJs).value, res._2._2)

    import Cache.seqFormat

    closureJs.previous.getOrElse(Nil) ++ res._1
  }

}

