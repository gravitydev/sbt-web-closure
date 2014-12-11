package com.gravitydev.sbt.closurejs

import java.io.{File, InputStreamReader, StringWriter}
import scala.collection.JavaConverters._
import scalax.file.Path
import com.google.javascript.rhino.Node
import com.google.javascript.jscomp.{Compiler, CompilerOptions, ProcessCommonJSModules, CompilationLevel, CommandLineRunner, SourceFile,
  DependencyOptions, CheckLevel, DiagnosticGroups, SourceMap, ErrorManager}
import scala.io.Source
import scala.concurrent.Await
import scala.concurrent.duration._
import sbt._

import java.util.concurrent.ConcurrentHashMap

import play.api.libs.ws.{DefaultWSClientConfig, WS}
import play.api.libs.ws.ning.NingAsyncHttpClientConfigBuilder
import play.api.libs.json.Json

import scalax.io.Codec
import scalax.io.JavaConverters._

object ClosureJsCompiler2 {
  // TODO: fine-grained dependency caching
  val dependencyCache = new ConcurrentHashMap[File,Seq[File]]()

  def depInfo (source: File): (List[String], List[String]) = {
    val code = Path(source).string.replaceAll( "//.*|(\"(?:\\\\[^\"]|\\\\\"|.)*?\")|(?s)/\\*.*?\\*/", "$1 " )

    val pro = """goog\.provide\(\s*['\"]([^'\"]+)['\"]\s*\);""".r.findAllIn(code).matchData.map(_ group 1).toList
    val req = """goog\.require\(\s*['\"]([^'\"]+)['\"]\s*\);""".r.findAllIn(code).matchData.map(_ group 1).toList

    (pro, req)
  }

  def dependencies (source: File, librarySources: Seq[File]): Seq[File] = {
    println("Finding dependencies for: " + source)
    Option(dependencyCache.get(source)) getOrElse {
 
      val sources = Seq(source) ++ librarySources 

      // file to modules
      val requires = sources map {f => 
        f.getAbsolutePath -> depInfo(f)._2.filter(x => !x.startsWith("goog."))
      } toMap
     
      // module to file
      val provides = sources flatMap {
        f => depInfo(f)._1 map (_ -> f.getAbsolutePath)
      } toMap
  
      val sourcePath = source.getAbsolutePath
  
      def getFileDeps (file: String, deps: Set[String] = Set()): Set[String] = {
        if (deps contains file) deps
        else {
          val deps2 = deps + file 
          
          deps2 ++ (requires(file) flatMap {r =>
            getFileDeps(provides(r), deps2) 
          })
        }
      }
  
      var res = getFileDeps(sourcePath).toList map (x => new File(x)) filterNot (_ == source)
      
      // cache if it's part of closure lib
      // very hacky, TODO: better caching
      //if (source.absolutePath.contains("closure-library")) dependencyCache.put(source, res)
      dependencyCache.put(source, res)
      
      res
    }
  }

  /**
   * Compile a JS file with its dependencies
   * @param source
   * @param sources Library sources to include in compilation
   * @param fullCompilerOptions user supplied full blown CompilerOptions instance
   * @return a triple containing the  code, the list of dependencies (including the input file)
   */
  def compile (
    source: File, 
    librarySources: Seq[File], 
    prettyPrint: Boolean,
    pseudoNames: Boolean
  ): (String, Seq[File], List[LineError]) = {

    println("ClosureJsCompiler2.compile: library sources: " + librarySources)

    val deps = dependencies(source, librarySources) 

    println("ClosureJsCompiler2.compile: dependencies found: " + deps) 

    implicit val httpClient = {
      val clientConfig = new DefaultWSClientConfig()
      val secureDefaults:com.ning.http.client.AsyncHttpClientConfig = new NingAsyncHttpClientConfigBuilder(clientConfig).build()
      val builder = new com.ning.http.client.AsyncHttpClientConfig.Builder(secureDefaults)
      builder.setCompressionEnabled(true)
      val secureDefaultsWithSpecificOptions:com.ning.http.client.AsyncHttpClientConfig = builder.build()
      new play.api.libs.ws.ning.NingWSClient(secureDefaultsWithSpecificOptions)
    }

    val comp = Await.result(
      WS.clientUrl("http://closure-compiler.appspot.com/compile")
        .withHeaders("Content-Type" -> "application/x-www-form-urlencoded")
        .post(
          Map(
            "js_code" -> (deps.map(_.asInput.string(Codec.UTF8)) ++ Seq(source.asInput.string(Codec.UTF8))),
            "compilation_level" -> Seq("ADVANCED_OPTIMIZATIONS"),
            "output_format" -> Seq("json"),
            "output_info" -> Seq("compiled_code", "warnings", "errors", "statistics"),
            "use_closure_library" -> Seq("true"),
            "formatting" -> Seq("pretty_print", "print_input_delimiter"),
            "warning_level" -> Seq("VERBOSE")
          )
        ),
      60.seconds
    )
    val res = Json.parse(comp.body)

    implicit val lineFatalF = Json.format[LineFatal]
    implicit val lineWarningF = Json.format[LineWarning]

    (res \ "errors").validate[List[LineFatal]].fold(
      _ => ((res \ "compiledCode").as[String], deps, (res \ "warnings").validate[List[LineWarning]].fold(x => Nil, identity)),
      errors => ("", Nil, errors)
    )
   
    /* 
    if (res.success) {
      res.sourceMap.reset();
      val compiledSource = compiler.toSource
      
      (compiledSource, all, compiler.getErrorManager)
    } else {
      ("", Nil, compiler.getErrorManager)
    }
    */
  }

}

sealed trait LineError {
  def charno: Int
  def msg: String
  def lineno: Int
  def file: String
  def line: String  
}
case class LineFatal(
  charno: Int,
  error: String,
  lineno: Int,
  file: String,
  `type`: String,
  line: String
) extends LineError {
  def msg = error
}
case class LineWarning(
  charno: Int,
  warning: String,
  lineno: Int,
  file: String,
  `type`: String,
  line: String
) extends LineError {
  def msg = warning
}

