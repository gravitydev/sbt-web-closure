package gss

import com.google.common.css.compiler.gssfunctions.DefaultGssFunctionMapProvider
import com.google.common.css.GssFunctionMapProvider
import com.google.common.css.compiler.ast.{GssParser, AccessibleErrorManager, GssError, GssParserException}

import java.io.File
import scalax.io.Resource
import scala.collection.JavaConverters._
import com.google.common.css.SourceCode

import sbt._
import sbt.Keys._
import xsbti.{Severity, Problem}
import com.typesafe.sbt.web._
import com.typesafe.sbt.web.pipeline.Pipeline
import com.typesafe.sbt.web.incremental.{syncIncremental, OpResult, OpSuccess, OpFailure, toStringInputHasher, OpInputHasher, OpInputHash}
import sbinary.{Input, Output, Format}

object Import {
  object GssKeys {
    val gss = taskKey[Seq[File]]("Invoke the closure-stylesheets compiler.")
    val gssPrettyPrint = settingKey[Boolean]("Generate pretty gss output.")
    val gssAllowUnrecognizedFunctions = settingKey[Boolean]("Allow unrecognized functions.")
    val gssAllowUnrecognizedProperties = settingKey[Boolean]("Allow unrecognized properties.")
    val gssFunctionMapProvider = settingKey[GssFunctionMapProvider]("Function map provider.")
    val gssAllowedNonStandardFunctions = settingKey[List[String]]("Allowed non standard functions.")
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
    includeFilter := "*.gss",
    excludeFilter := "_*.gss"
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
      gss in Assets := (gss in Assets).dependsOn(webModules in Assets).value,
      gssPrettyPrint := false,
      gssAllowUnrecognizedFunctions := false,
      gssAllowUnrecognizedProperties := false,
      gssFunctionMapProvider := new DefaultGssFunctionMapProvider,
      gssAllowedNonStandardFunctions := Nil
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
    
    val settings = GssCompilerSettings(
      prettyPrint = gssPrettyPrint.value,
      allowUnrecognizedFunctions = gssAllowUnrecognizedFunctions.value,
      allowUnrecognizedProperties = gssAllowUnrecognizedProperties.value,
      gssFunctionMapProvider = gssFunctionMapProvider.value,
      allowedNonStandardFunctions = gssAllowedNonStandardFunctions.value
    )

    // all gss files must be included,
    // imports must be excluded later
    val sources = (
      sourceDir ** (
        (includeFilter in gss in Assets).value
      )
    ).get

    val target = (resourceManaged in gss in Assets).value

    log.info("Compiling GSS files")
    log.info("GSS files: " + sources.size)

    val hasher = OpInputHasher[File] {op =>
      def hashFile (f: File) = f.getAbsolutePath + ":" + f.lastModified

      OpInputHash.hashString(hashFile(op) + "," + GssCompiler.dependencies(op).map(hashFile _).mkString(","))
    }

    val res = syncIncremental[File, (List[File], List[Problem])]((streams in Assets).value.cacheDirectory / "run", sources) {ops =>
      val pathsMap = ops map (x => 
        (x.absolutePath.stripPrefix(sourceDir.head.absolutePath).stripSuffix(".gss") + ".css") -> x
      )
      log.info("Compiling " + ops.size + " gss files")

      val errs = java.util.Collections.synchronizedList(new java.util.ArrayList[Problem]())

      val resx = (pathsMap.par.map {
        case (path, source) if (excludeFilter in gss in Assets).value.accept(source) => source -> OpSuccess(Set.empty, Set.empty)
        case (path, source) => source -> {
          val (compiled, deps, errors) = GssCompiler.compile(source, GssCompiler.dependencies(source), settings)
          val result = target / path
          IO.write(target / path, compiled)

          for ((err, isError) <- errors.getErrors().asScala.map(x => (x,true)) ++ errors.getWarnings().asScala.map(x => (x,false))) {
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
      }.seq.toMap)

      resx -> resx.toList.map{
        case (_,OpSuccess(filesRead, filesWritten)) => (filesWritten, errs.asScala.toList)
        case _ => (Nil, errs.asScala.toList)
      }.foldLeft((List[File](), List[Problem]()))((a,b) => (a._1 ++ b._1, a._2 ++ b._2))
    }(hasher)

    CompileProblems.report((reporter in gss).value, res._2._2)

    import Cache.seqFormat

    (gss in Assets).previous.getOrElse(Nil) ++ res._1
  }
}

case class GssCompilerSettings(
  prettyPrint: Boolean,
  allowUnrecognizedFunctions: Boolean,
  allowUnrecognizedProperties: Boolean,
  gssFunctionMapProvider: GssFunctionMapProvider,
  allowedNonStandardFunctions: List[String]
)

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

  def compile (file: File, deps: List[File], settings: GssCompilerSettings): (String, List[File], AccessibleErrorManager) = {
    import com.google.common.css.JobDescriptionBuilder
    import com.google.common.css.compiler.passes.{PassRunner, CompactPrinter, PrettyPrinter}

    val inputs = deps.map(getCode) ++ List(getCode(file))

    val job = new JobDescriptionBuilder()
      .setInputs(inputs.asJava)
      .setAllowUnrecognizedFunctions(settings.allowUnrecognizedFunctions)
      .setAllowUnrecognizedProperties(settings.allowUnrecognizedProperties)
      .setProcessDependencies(true)
      .setGssFunctionMapProvider(settings.gssFunctionMapProvider)
      .setAllowedNonStandardFunctions(settings.allowedNonStandardFunctions.asJava)
      .getJobDescription()

    val em = new AccessibleErrorManager

    val passRunner = new PassRunner(job, em)

    try {
      val parser = new GssParser(job.inputs)
      val tree = parser.parse()
  
      passRunner.runPasses(tree)
  
      val code = if (settings.prettyPrint) {
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
    } catch {
      case e: GssParserException => {
        val err = new GssError(e.getCause.getMessage(), e.getGssError.getLocation())
        em.report(err)
        ("", Nil,em)
      }
    }
  }
}

