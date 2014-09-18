package com.gravitydev.sbt.closurejs

import java.io.{File, InputStreamReader, StringWriter}
import scala.collection.JavaConverters._
import scalax.file.Path
import com.google.javascript.rhino.Node
import com.google.javascript.jscomp.{Compiler, CompilerOptions, ProcessCommonJSModules, CompilationLevel, CommandLineRunner, SourceFile,
  DependencyOptions, CheckLevel, DiagnosticGroups, SourceMap, ErrorManager}
import scala.io.Source
import sbt._

import java.util.concurrent.ConcurrentHashMap

object ClosureJsCompiler {
  // TODO: fine-grained dependency caching
  val dependencyCache = new ConcurrentHashMap[File,Seq[File]]()

  def depInfo (source: File): (List[String], List[String]) = {
    println("depInfo for: " + source)
    val code = Path(source).string.replaceAll( "//.*|(\"(?:\\\\[^\"]|\\\\\"|.)*?\")|(?s)/\\*.*?\\*/", "$1 " )

    val pro = """goog\.provide\(\s*['\"]([^'\"]+)['\"]\s*\);""".r.findAllIn(code).matchData.map(_ group 1).toList
    val req = """goog\.require\(\s*['\"]([^'\"]+)['\"]\s*\);""".r.findAllIn(code).matchData.map(_ group 1).toList

    (pro, req)
  }

  def dependencies (source: File, sources: PathFinder): Seq[File] = {
    println("Finding dependencies for: " + source)
    Option(dependencyCache.get(source)) getOrElse {
      val jsFiles = Seq(source) ++ sources.get
  
      // file to modules
      val requires = jsFiles map {
        f => f.getAbsolutePath -> depInfo(f)._2
      } toMap
     
      // module to file
      val provides = jsFiles flatMap {
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
  
      var res = getFileDeps(sourcePath).toList map (x => new File(x))
      
      // cache if it's part of closure lib
      // very hacky, TODO: better caching
      //if (source.absolutePath.contains("closure-library")) dependencyCache.put(source, res)
      dependencyCache.put(source, res)
      
      res
    }
  }

  /**
   * Compile a JS file with its dependencies
   * @return a triple containing the original source code, the minified source code, the list of dependencies (including the input file)
   * @param source
   * @param fullCompilerOptions user supplied full blown CompilerOptions instance
   */
  def compile (
    source: File, 
    sources: PathFinder, 
    closureLibraryDir: File, 
    locationMappings: List[(String,String)],
    prettyPrint: Boolean,
    pseudoNames: Boolean
  ): (String, Seq[File], ErrorManager) = {
    import scala.util.control.Exception._

    val origin = Path(source).string

    val all = Seq(
      closureLibraryDir / "closure" / "goog" / "base.js",
      closureLibraryDir / "closure" / "goog" / "deps.js"
    ) ++ dependencies(source, sources) 

    val input = all map (x => SourceFile.fromFile(x))

    val options = {
      val o = new CompilerOptions()
     
      CompilationLevel.ADVANCED_OPTIMIZATIONS.setOptionsForCompilationLevel(o)
      CompilationLevel.ADVANCED_OPTIMIZATIONS.setTypeBasedOptimizationOptions(o)
      o setPrettyPrint                  prettyPrint
      o setGeneratePseudoNames          pseudoNames
      o setAggressiveVarCheck           CheckLevel.ERROR
      //o setTightenTypes                 true // crashes compiler
      o setReportMissingOverride        CheckLevel.ERROR
      //o setReportUnknownTypes           CheckLevel.ERROR
      o setCheckRequires                CheckLevel.ERROR
      o setCheckProvides                CheckLevel.ERROR
      o setCheckSuspiciousCode          true
      o setCheckSymbols                 true
      //o setCheckUnreachableCode         CheckLevel.ERROR
      o setDeadAssignmentElimination    true
      o setRemoveDeadCode               true
      o setRemoveUnusedPrototypeProperties true
      o setRemoveUnusedVars             true
      
      o setCheckGlobalNamesLevel        CheckLevel.ERROR
      o setBrokenClosureRequiresLevel   CheckLevel.ERROR
      o setCheckGlobalThisLevel         CheckLevel.ERROR
      //o setCheckCaja                    false
      o setCheckMissingReturn           CheckLevel.ERROR
      o setCheckTypes                   true

      o setWarningLevel(DiagnosticGroups.CHECK_TYPES,       CheckLevel.ERROR)
      //o setWarningLevel(DiagnosticGroups.CHECK_VARS,        CheckLevel.ERROR)
      o setWarningLevel(DiagnosticGroups.CONST ,            CheckLevel.ERROR)
      o setWarningLevel(DiagnosticGroups.CONSTANT_PROPERTY, CheckLevel.ERROR)
      o setWarningLevel(DiagnosticGroups.DEPRECATED,        CheckLevel.WARNING)
      //o setWarningLevel(DiagnosticGroups.DUPLICATE,         CheckLevel.WARNING)
      o setWarningLevel(DiagnosticGroups.GLOBAL_THIS,       CheckLevel.ERROR)
      o setWarningLevel(DiagnosticGroups.INVALID_CASTS,     CheckLevel.ERROR)
      o setWarningLevel(DiagnosticGroups.MISSING_PROPERTIES, CheckLevel.ERROR)
      o setWarningLevel(DiagnosticGroups.SUSPICIOUS_CODE,   CheckLevel.WARNING)
      o setWarningLevel(DiagnosticGroups.UNDEFINED_NAMES,   CheckLevel.ERROR)
      //o setWarningLevel(DiagnosticGroups.UNDEFINED_VARS,    CheckLevel.ERROR)
      o setWarningLevel(DiagnosticGroups.VISIBILITY,        CheckLevel.ERROR)
      o setSourceMapFormat SourceMap.Format.V3
      o setSourceMapOutputPath "gravity.map" // this is not actually used, but it needs to be set
      o setSourceMapDetailLevel SourceMap.DetailLevel.ALL
      o setSourceMapLocationMappings(
        locationMappings map {case (k,v) => new SourceMap.LocationMapping(k, v)} asJava
      )

      o.setManageClosureDependencies(Seq(toModuleName(source.getName())).asJava)
      o
    }

    val compiler = new Compiler()
    // gears externs crash the compiler
    val externs = CommandLineRunner.getDefaultExterns().asScala.filter(_.toString != "externs.zip//gears_symbols.js")

    val res = compiler.compile(externs.asJava, input.asJava, options) 

    if (res.success) {
      res.sourceMap.reset();
      val compiledSource = compiler.toSource
      
      /*
      val w = new StringWriter();
      res.sourceMap.appendTo(w, "/assets/javascripts/designer.min.js")
      */
      (compiledSource, all, compiler.getErrorManager)
    } else {
      ("", Nil, compiler.getErrorManager)
    }    
  }

  /**
   * Turns a filename into a JS identifier that is used for moduleNames in
   * rewritten code. Removes leading ./, replaces / with $, removes trailing .js
   * and replaces - with _. All moduleNames get a "module$" prefix.
   */
  private def toModuleName(filename: String) = {
    //"module$" + filename.replaceAll("^\\./", "").replaceAll("/", "\\$").replaceAll("\\.js$", "").replaceAll("-", "_");
    "module." + filename.stripSuffix(".module.js").replaceAll("^\\./", "").replaceAll("/", "\\$").replaceAll("-", "_");
  }

}
