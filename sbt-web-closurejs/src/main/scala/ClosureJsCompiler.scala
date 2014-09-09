import java.io.{File, InputStreamReader, StringWriter}
import scala.collection.JavaConverters._
import scalax.file.Path
import com.google.javascript.rhino.Node
import com.google.javascript.jscomp.{Compiler, CompilerOptions, ProcessCommonJSModules, CompilationLevel, CommandLineRunner, SourceFile,
  DependencyOptions, CheckLevel, DiagnosticGroups, SourceMap}
import scala.io.Source
import sbt._

object ClosureJsCompiler {

  def depInfo (source: File): (List[String], List[String]) = {
    val code = Path(source).string.replaceAll( "//.*|(\"(?:\\\\[^\"]|\\\\\"|.)*?\")|(?s)/\\*.*?\\*/", "$1 " )

    val pro = """goog\.provide\(\s*['\"]([^'\"]+)['\"]\s*\);""".r.findAllIn(code).matchData.map(_ group 1).toList
    val req = """goog\.require\(\s*['\"]([^'\"]+)['\"]\s*\);""".r.findAllIn(code).matchData.map(_ group 1).toList

    (pro, req)
  }

  def dependencies (source: File, sources: PathFinder): Seq[File] = {
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

    var res = getFileDeps(sourcePath)

    res.toList map (x => new File(x))
  }

  /**
   * Compile a JS file with its dependencies
   * @return a triple containing the original source code, the minified source code, the list of dependencies (including the input file)
   * @param source
   * @param fullCompilerOptions user supplied full blown CompilerOptions instance
   */
  def compile (source: File, sources: PathFinder, closureLibraryDir: File, locationMappings: List[(String,String)]): (String, Option[String], Seq[File], String) = {
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
      o setPrettyPrint                  true
      o setGeneratePseudoNames          true
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

    try {
      val res = compiler.compile(externs.asJava, input.asJava, options) 
  
      if (res.success) {
        res.sourceMap.reset();
        val compiledSource = compiler.toSource
        
        val w = new StringWriter();
        res.sourceMap.appendTo(w, "/assets/javascripts/designer.min.js")
        
        (origin, Some(compiledSource), all, w.toString)
      } else {
        val error = compiler.getErrors().head
        val errorFile = all.find(f => f.getAbsolutePath() == error.sourceName)
	???
        //throw AssetCompilationException(errorFile, error.description, Some(error.lineNumber), None)
      }  
    } catch {
      case ex: Exception => {
        throw ex
        //ex.printStackTrace()
        //throw AssetCompilationException(Some(source), "Internal Closure Compiler error (see logs)", None, None)
      }
    }   
  }

  /**
   * Turns a filename into a JS identifier that is used for moduleNames in
   * rewritten code. Removes leading ./, replaces / with $, removes trailing .js
   * and replaces - with _. All moduleNames get a "module$" prefix.
   */
  private def toModuleName(filename: String) = {
    //"module$" + filename.replaceAll("^\\./", "").replaceAll("/", "\\$").replaceAll("\\.js$", "").replaceAll("-", "_");
    "module." + filename.replaceAll("^\\./", "").replaceAll("/", "\\$").replaceAll("\\.js$", "").replaceAll("-", "_");
  }

}
