package gss

import com.google.common.css.compiler.gssfunctions.DefaultGssFunctionMapProvider
import com.google.common.css.compiler.ast.{GssParser, AccessibleErrorManager}

import java.io.File
import scalax.io.Resource
import scala.collection.JavaConversions._
import com.google.common.css.SourceCode

import sbt._
import sbt.Keys._
import xsbti.{Severity, Problem}
import com.typesafe.sbt.web._
import com.typesafe.sbt.web.pipeline.Pipeline
import com.typesafe.sbt.web.incremental.{runIncremental, OpResult, OpSuccess, OpFailure, toStringInputHasher, OpInputHasher, OpInputHash}
import sbinary.{Input, Output, Format}

object Import {
  object GssKeys {
    val gss = TaskKey[Seq[File]]("gss", "Invoke the closure-stylesheets compiler.")
  }
}

object SbtGss extends AutoPlugin {
  override def requires = SbtWeb
  override def trigger = AllRequirements
  val autoImport = Import
  
  import SbtWeb.autoImport._
  import WebKeys._
  import autoImport.GssKeys._

  val gssUnscopedSettings = Seq(
    includeFilter := "*.gss"//,
    /*excludeFilter := "_*.gss"*/
  )

  override def projectSettings = 
    Seq(
      // task
      gss in Assets := gssGenerate.value,

      // adding generated resources to the managedResources
      managedResourceDirectories in Assets += (resourceManaged in gss in Assets).value,
      resourceManaged in gss in Assets := webTarget.value / gss.key.label / "main",
      (resourceGenerators in Assets) <+= gss
    ) ++
    inTask(gss)(
      inConfig(Assets)(gssUnscopedSettings) ++
      Seq(
        moduleName := "gss"
      )
    ) ++ 
    Seq(
      gss := (gss in Assets).value,
      gss in Assets := (gss in Assets).dependsOn(webModules in Assets).value
    )

  /*
   * For reading/writing binary representations of files.
   */
  private implicit object FileFormat extends Format[File] {
    import sbinary.DefaultProtocol._

    def reads(in: Input): File = file(StringFormat.reads(in))

    def writes(out: Output, fh: File) = StringFormat.writes(out, fh.getAbsolutePath)
  }

  def gssGenerate: Def.Initialize[Task[Seq[File]]] = Def.task {
    val log = streams.value.log
    val sourceDir = (unmanagedSources in gss in Assets).value

    val sources = (
      sourceDir ** (
        (includeFilter in gss in Assets).value //-- 
        //(excludeFilter in gss in Assets).value --
        //"_*.gss"
      )
    ).get

    val target = (resourceManaged in gss in Assets).value

    log.info("Compiling GSS files")
    log.info("GSS files: " + sources.size)

    val hasher = OpInputHasher[File] {op =>
      def hashFile (f: File) = f.getAbsolutePath + ":" + f.lastModified

      OpInputHash.hashString(hashFile(op) + "," + GssCompiler.dependencies(op).map(hashFile _).mkString(","))
    }

    val res = runIncremental[File, (List[File], List[Problem])]((streams in Assets).value.cacheDirectory / "run", sources) {ops =>
      log.info("Ops: " + ops)
      val pathsMap = ops map (x => 
        (x.absolutePath.stripPrefix(sourceDir.head.absolutePath).stripSuffix(".gss") + ".css") -> x
      )
      log.info("Paths: " + pathsMap)

      val errs = new scala.collection.mutable.ListBuffer[Problem]()

      val resx = (pathsMap map {
        case (path, source) if source.getName startsWith "_" => source -> OpSuccess(Set.empty, Set.empty)
        case (path, source) => source -> {
          val (compiled, deps, errors) = GssCompiler.compile(source, GssCompiler.dependencies(source), pretty=false)
          val result = target / path
          IO.write(target / path, compiled)

          for ((err, isError) <- errors.getErrors().map(x => (x,true)) ++ errors.getWarnings().map(x => (x,false))) {
            errs.add( 
              new LineBasedProblem(
                err.getMessage, 
                if (isError) Severity.Error else Severity.Warn, 
                err.getLocation.getLineNumber+1, 
                err.getLocation.getBeginIndexInLine, 
                err.getLine, 
                new File(err.getLocation.getSourceCode.getFileName)
              ) 
            )
          }
          if (errors.getErrorCount > 0) OpFailure
          else OpSuccess(Set(source), Set(result))
        }
      } toMap)

      resx -> resx.toList.map{
        case (_,OpSuccess(filesRead, filesWritten)) => (filesWritten, errs.toList)
        case _ => (Nil, errs.toList)
      }.foldLeft((List[File](), List[Problem]()))((a,b) => (a._1 ++ b._1, a._2 ++ b._2))
    }(hasher)

    CompileProblems.report((reporter in gss).value, res._2)

    import Cache.seqFormat

    val prev = (gss in Assets).previous

    (gss in Assets).previous.getOrElse(Nil) ++ res._1
  }
}

object GssCompiler {
  private val isImport = (s: String) => s startsWith "@import url"

  private def getImport (s: String) = 
    s.stripPrefix("@import ").stripPrefix("url(").stripPrefix("'").stripPrefix("\"").stripSuffix(";").stripSuffix(")").stripSuffix("\"").stripSuffix("'")

  def dependencies (f: File): List[File] = {
    val isImport = (s: String) => s startsWith "@import"

    def deps (f: File): List[File] = {
      val src = Resource.fromFile(f)
      val imports = src.lines().takeWhile(isImport).map(getImport)

      (for { 
        df <- imports.toList
        dep <- {
          val depfile = new File(f.getParentFile.getAbsolutePath+"/"+df)
          deps(depfile) ++ List(depfile)
        }
      } yield dep)
    }
    deps(f).map(_.getCanonicalFile).distinct
  }

  private def getCode (file: File) = {
    val src = Resource.fromFile(file)
    val imports = src.lines().takeWhile(isImport).map(getImport)
    new SourceCode(file.getAbsolutePath, src.lines().dropWhile(isImport).mkString("\n"))
  }

  def compile (file: File, deps: List[File], pretty: Boolean): (String, List[File], AccessibleErrorManager) = {
    import com.google.common.css.JobDescriptionBuilder
    import com.google.common.css.compiler.passes.{PassRunner, CompactPrinter, PrettyPrinter}

    val inputs = deps.map(getCode) ++ List(getCode(file))

    val job = new JobDescriptionBuilder()
      .setInputs(inputs)
      .setAllowUnrecognizedFunctions(false)
      .setAllowUnrecognizedProperties(false)
      .setProcessDependencies(true)
      .setGssFunctionMapProvider(new DefaultGssFunctionMapProvider/*new GravityGssFunctionMapProvider(vs staticUrl _)*/)
      .setAllowedNonStandardFunctions(List(
          "color-stop",
          "progid:DXImageTransform.Microsoft.gradient",
          "progid:DXImageTransform.Microsoft.Shadow"
      ))
      .getJobDescription()

    val em = new AccessibleErrorManager

    val passRunner = new PassRunner(job, em)

    val parser = new GssParser(job.inputs)
    val tree = parser.parse()

    passRunner.runPasses(tree)

    val code = if (pretty) {
      val printer = new PrettyPrinter(tree.getVisitController())
      printer.runPass()
      em.generateReport()
      printer.getPrettyPrintedString()
    } else {
      val printer = new CompactPrinter(tree)
      printer.runPass()
      em.generateReport()
      printer.getCompactPrintedString()
    }

    (code, deps, em)
  }

  /*
  def compile (file: File, pretty: Boolean): (String, List[File], AccessibleErrorManager) = {
    import com.google.common.css.JobDescriptionBuilder
    import com.google.common.css.compiler.passes.{PassRunner, CompactPrinter, PrettyPrinter}

    val deps = dependencies(file).distinct
    val inputs = deps.map(getCode) ++ List(getCode(file))

    val job = new JobDescriptionBuilder()
      .setInputs(inputs)
      .setAllowUnrecognizedFunctions(false)
      .setAllowUnrecognizedProperties(false)
      .setProcessDependencies(true)
      .setGssFunctionMapProvider(new DefaultGssFunctionMapProvider/*new GravityGssFunctionMapProvider(vs staticUrl _)*/)
      .setAllowedNonStandardFunctions(List(
          "color-stop",
          "progid:DXImageTransform.Microsoft.gradient",
          "progid:DXImageTransform.Microsoft.Shadow"
      ))
      .getJobDescription()

    val em = new AccessibleErrorManager

    val passRunner = new PassRunner(job, em)

    val parser = new GssParser(job.inputs)
    val tree = parser.parse()

    passRunner.runPasses(tree)

    val code = if (pretty) {
      val printer = new PrettyPrinter(tree.getVisitController())
      printer.runPass()
      em.generateReport()
      printer.getPrettyPrintedString()
    } else {
      val printer = new CompactPrinter(tree)
      printer.runPass()
      em.generateReport()
      printer.getCompactPrintedString()
    }

    (code, deps, em)
  }
  */
}

