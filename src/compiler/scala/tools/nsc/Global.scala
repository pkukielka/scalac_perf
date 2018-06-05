/* NSC -- new Scala compiler
 * Copyright 2005-2013 LAMP/EPFL
 * @author  Martin Odersky
 */

package scala
package tools
package nsc

import java.io.{File, FileNotFoundException, IOException}
import java.net.URL
import java.nio.charset.{Charset, CharsetDecoder, IllegalCharsetNameException, UnsupportedCharsetException}

import scala.collection.{immutable, mutable}
import io.{AbstractFile, Path, SourceReader}
import reporters.{BufferedReporter, Reporter}
import util.{ClassPath, returning}
import scala.reflect.ClassTag
import scala.reflect.internal.util.{BatchSourceFile, NoSourceFile, Parallel, ScalaClassLoader, ScriptSourceFile, SourceFile, StatisticsStatics}
import scala.reflect.internal.util.Parallel._
import scala.reflect.internal.pickling.PickleBuffer
import symtab.{Flags, SymbolTable, SymbolTrackers}
import symtab.classfile.Pickler
import plugins.Plugins
import ast._
import ast.parser._
import typechecker._
import transform.patmat.PatternMatching
import transform._
import backend.{JavaPlatform, ScalaPrimitives}
import backend.jvm.{BackendStats, GenBCode}
import scala.concurrent.duration.Duration
import scala.concurrent._
import scala.language.postfixOps
import scala.tools.nsc.ast.{TreeGen => AstTreeGen}
import scala.tools.nsc.classpath._
import scala.tools.nsc.profile.{Profiler, ThreadPoolFactory}

class Global(var currentSettings: Settings, reporter0: Reporter)
    extends SymbolTable
    with CompilationUnits
    with Plugins
    with PhaseAssembly
    with Trees
    with Printers
    with DocComments
    with Positions
    with Reporting
    with Parsing { self =>

  // the mirror --------------------------------------------------

  override def isCompilerUniverse = true
  override val useOffsetPositions = !currentSettings.Yrangepos

  type RuntimeClass = java.lang.Class[_]
  implicit val RuntimeClassTag: ClassTag[RuntimeClass] = ClassTag[RuntimeClass](classOf[RuntimeClass])

  class GlobalMirror extends Roots(NoSymbol) {
    val universe: self.type = self
    def rootLoader: LazyType = new loaders.PackageLoader(ClassPath.RootPackage, classPath)
    override def toString = "compiler mirror"
  }
  implicit val MirrorTag: ClassTag[Mirror] = ClassTag[Mirror](classOf[GlobalMirror])

  lazy val rootMirror: Mirror = {
    val rm = new GlobalMirror
    rm.init()
    rm.asInstanceOf[Mirror]
  }
  def RootClass: ClassSymbol = rootMirror.RootClass
  def EmptyPackageClass: ClassSymbol = rootMirror.EmptyPackageClass

  import definitions.findNamedMember
  def findMemberFromRoot(fullName: Name): Symbol = rootMirror.findMemberFromRoot(fullName)

  // alternate constructors ------------------------------------------

  override def settings = currentSettings

  // Umad reported violation on: `scala.reflect.internal.SymbolTable.scala$reflect$internal$Names$$nc_$eq(int)`
  // To synchronize access we can re-use mechanism from `scala.reflect.internal.Names` so far used for runtime mirror.
  // For efficiency want to enable it only for phases which are parallelized.
  override protected def synchronizeNames: Boolean = _synchronizeNames
  private[this] var _synchronizeNames = false

  /* `currentReporter` is used on both main thread as well as worker threads.
   * On worker threads for every unit we are creating new `BufferedReporter`
   * which at the end of the unit processing is dumped to the main reporter.
   * We need to do it if we want to retain the same messages order as in case of single threaded execution.
   */
  private[this] val currentReporter: WorkerOrMainThreadLocal[Reporter] = WorkerThreadLocal(reporter0, reporter0)
  def reporter: Reporter =  currentReporter.get
  def reporter_=(newReporter: Reporter): Unit =
    currentReporter.set(newReporter match {
      case _: reporters.ConsoleReporter | _: reporters.LimitingReporter => newReporter
      case _ if settings.maxerrs.isSetByUser && settings.maxerrs.value < settings.maxerrs.default =>
        new reporters.LimitingReporter(settings, newReporter)
      case _ => newReporter
    })

  /** Switch to turn on detailed type logs */
  var printTypings = settings.Ytyperdebug.value

  def this(reporter: Reporter) =
    this(new Settings(err => reporter.error(null, err)), reporter)

  def this(settings: Settings) =
    this(settings, Global.reporter(settings))

  def picklerPhase: Phase = if (currentRun.isDefined) currentRun.picklerPhase else NoPhase

  def erasurePhase: Phase = if (currentRun.isDefined) currentRun.erasurePhase else NoPhase

  /* Override `newStubSymbol` defined in `SymbolTable` to provide us access
   * to the last tree to typer, whose position is the trigger of stub errors. */
  override def newStubSymbol(owner: Symbol,
                             name: Name,
                             missingMessage: String): Symbol = {
    val stubSymbol = super.newStubSymbol(owner, name, missingMessage)
    val stubErrorPosition = {
      val lastTreeToTyper = analyzer.lastTreeToTyper
      if (lastTreeToTyper != EmptyTree) lastTreeToTyper.pos else stubSymbol.pos
    }
    stubSymbol.setPos(stubErrorPosition)
  }

  // platform specific elements

  protected class GlobalPlatform extends {
    val global: Global.this.type = Global.this
    val settings: Settings = Global.this.settings
  } with JavaPlatform

  type ThisPlatform = JavaPlatform { val global: Global.this.type }
  lazy val platform: ThisPlatform  = new GlobalPlatform
  /* A hook for the REPL to add a classpath entry containing products of previous runs to inliner's bytecode repository*/
  // Fixes scala/bug#8779
  def optimizerClassPath(base: ClassPath): ClassPath = base

  def classPath: ClassPath = platform.classPath

  // sub-components --------------------------------------------------

  /** Tree generation, usually based on existing symbols. */
  override object gen extends {
    val global: Global.this.type = Global.this
  } with AstTreeGen {
    def mkAttributedCast(tree: Tree, pt: Type): Tree =
      typer.typed(mkCast(tree, pt))
  }

  /** A spare instance of TreeBuilder left for backwards compatibility. */
  lazy val treeBuilder: TreeBuilder { val global: Global.this.type } = new TreeBuilder {
    val global: Global.this.type = Global.this

    def unit = currentUnit
    def source = currentUnit.source
  }

  /** Fold constants */
  object constfold extends {
    val global: Global.this.type = Global.this
  } with ConstantFolder

  /** For sbt compatibility (https://github.com/scala/scala/pull/4588) */
  object icodes {
    class IClass(val symbol: Symbol)
  }

  // TODO: move to the backend, make it a component
  /** Scala primitives, used the backend */
  object scalaPrimitives extends {
    val global: Global.this.type = Global.this
  } with ScalaPrimitives

  /** Computing pairs of overriding/overridden symbols */
  object overridingPairs extends {
    val global: Global.this.type = Global.this
  } with OverridingPairs

  type SymbolPair = overridingPairs.SymbolPair

  // Components for collecting and generating output

  import scala.reflect.internal.util.Statistics
  import scala.tools.nsc.transform.patmat.PatternMatchingStats
  trait GlobalStats extends ReflectStats
                       with TypersStats
                       with ImplicitsStats
                       with MacrosStats
                       with BackendStats
                       with PatternMatchingStats { self: Statistics => }

  /** Redefine statistics to include all known global + reflect stats. */
  final object statistics extends Statistics(Global.this, settings) with GlobalStats

  // Components for collecting and generating output

  /** Print tree in detailed form */
  object nodePrinters extends {
    val global: Global.this.type = Global.this
  } with NodePrinters {
    var lastPrintedPhase: Phase = NoPhase
    var lastPrintedSource: String = ""
    infolevel = InfoLevel.Verbose

    def showUnit(unit: CompilationUnit): Unit = {
      print(" // " + unit.source)
      if (unit.body == null) println(": tree is null")
      else {
        val source = util.stringFromWriter(w => newTreePrinter(w) print unit.body)

        // treePrinter show unit.body
        if (lastPrintedSource == source)
          println(": tree is unchanged since " + lastPrintedPhase)
        else {
          lastPrintedPhase = phase.prev // since we're running inside "exitingPhase"
          lastPrintedSource = source
          println("")
          println(source)
          println("")
        }
      }
    }
  }

  def withInfoLevel[T](infolevel: nodePrinters.InfoLevel.Value)(op: => T) = {
    val saved = nodePrinters.infolevel
    try {
      nodePrinters.infolevel = infolevel
      op
    } finally {
      nodePrinters.infolevel = saved
    }
  }

  private var propCnt = 0
  @inline final def withPropagateCyclicReferences[T](t: => T): T = {
    try {
      propCnt = propCnt+1
      t
    } finally {
      propCnt = propCnt-1
      assert(propCnt >= 0)
    }
  }

  def propagateCyclicReferences: Boolean = propCnt > 0

  /** Representing ASTs as graphs */
  object treeBrowsers extends {
    val global: Global.this.type = Global.this
  } with TreeBrowsers

  val nodeToString = nodePrinters.nodeToString
  val treeBrowser = treeBrowsers.create()

  // ------------ Hooks for interactive mode-------------------------

  /** Called every time an AST node is successfully typechecked in typerPhase.
   */
  def signalDone(context: analyzer.Context, old: Tree, result: Tree): Unit = {}

  /** Called from parser, which signals hereby that a method definition has been parsed. */
  def signalParseProgress(pos: Position): Unit = {}

  /** Called by ScaladocAnalyzer when a doc comment has been parsed. */
  def signalParsedDocComment(comment: String, pos: Position) = {
    // TODO: this is all very broken (only works for scaladoc comments, not regular ones)
    //       --> add hooks to parser and refactor Interactive global to handle comments directly
    //       in any case don't use reporter for parser hooks
    reporter.comment(pos, comment)
  }

  /** Register new context; called for every created context
   */
  def registerContext(c: analyzer.Context): Unit = {
    lastSeenContext = c
  }

  /** Register top level class (called on entering the class)
   */
  def registerTopLevelSym(sym: Symbol): Unit = {}

// ------------------ Debugging -------------------------------------

  @inline final def ifDebug(body: => Unit): Unit = {
    if (settings.debug)
      body
  }

  override def isDeveloper = settings.developer || super.isDeveloper

  /** This is for WARNINGS which should reach the ears of scala developers
   *  whenever they occur, but are not useful for normal users. They should
   *  be precise, explanatory, and infrequent. Please don't use this as a
   *  logging mechanism. !!! is prefixed to all messages issued via this route
   *  to make them visually distinct.
   */
  @inline final override def devWarning(msg: => String): Unit = devWarning(NoPosition, msg)
  @inline final def devWarning(pos: Position, msg: => String): Unit = {
    def pos_s = if (pos eq NoPosition) "" else s" [@ $pos]"
    if (isDeveloper)
      warning(pos, "!!! " + msg)
    else
      log(s"!!!$pos_s $msg") // such warnings always at least logged
  }

  def logError(msg: String, t: Throwable): Unit = ()

  override def shouldLogAtThisPhase = settings.log.isSetByUser && (
    (settings.log containsPhase globalPhase) || (settings.log containsPhase phase)
  )
  // Over 200 closure objects are eliminated by inlining this.
  @inline final def log(msg: => AnyRef): Unit = {
    if (shouldLogAtThisPhase)
      inform(s"[log $globalPhase$atPhaseStackMessage] $msg")
  }

  @inline final override def debuglog(msg: => String): Unit = {
    if (settings.debug)
      log(msg)
  }

  @deprecated("Renamed to reportThrowable", "2.10.1")
  def logThrowable(t: Throwable): Unit = reportThrowable(t)
  def reportThrowable(t: Throwable): Unit = globalError(throwableAsString(t))
  override def throwableAsString(t: Throwable) = util.stackTraceString(t)

// ------------ File interface -----------------------------------------

  private val reader: SourceReader = {
    val defaultEncoding = Properties.sourceEncoding

    def loadCharset(name: String) =
      try Some(Charset.forName(name))
      catch {
        case _: IllegalCharsetNameException =>
          globalError(s"illegal charset name '$name'")
          None
        case _: UnsupportedCharsetException =>
          globalError(s"unsupported charset '$name'")
          None
      }

    val charset = settings.encoding.valueSetByUser flatMap loadCharset getOrElse {
      settings.encoding.value = defaultEncoding // A mandatory charset
      Charset.forName(defaultEncoding)
    }

    def loadReader(name: String): Option[SourceReader] = {
      def ccon = Class.forName(name).getConstructor(classOf[CharsetDecoder], classOf[Reporter])

      try Some(ccon.newInstance(charset.newDecoder(), reporter).asInstanceOf[SourceReader])
      catch { case ex: Throwable =>
        globalError("exception while trying to instantiate source reader '" + name + "'")
        None
      }
    }

    settings.sourceReader.valueSetByUser flatMap loadReader getOrElse {
      new SourceReader(charset.newDecoder(), reporter)
    }
  }

  if (settings.verbose || settings.Ylogcp)
    reporter.echo(
      s"[search path for source files: ${classPath.asSourcePathString}]\n" +
      s"[search path for class files: ${classPath.asClassPathString}]"
    )

  def getSourceFile(f: AbstractFile): BatchSourceFile = new BatchSourceFile(f, reader read f)

  def getSourceFile(name: String): SourceFile = {
    val f = AbstractFile.getFile(name)
    if (f eq null) throw new FileNotFoundException(
      "source file '" + name + "' could not be found")
    getSourceFile(f)
  }

  lazy val loaders = new {
    val global: Global.this.type = Global.this
    val platform: Global.this.platform.type = Global.this.platform
  } with GlobalSymbolLoaders

  /** Returns the mirror that loaded given symbol */
  def mirrorThatLoaded(sym: Symbol): Mirror = rootMirror

// ------------ Phases -------------------------------------------}

  var globalPhase: Phase = NoPhase

  val MaxPhases = 64

  val phaseWithId: Array[Phase] = Array.fill(MaxPhases)(NoPhase)

  abstract class GlobalPhase(prev: Phase) extends Phase(prev) {
    phaseWithId(id) = this

    def apply(unit: CompilationUnit): Unit

    /** Is current phase cancelled on this unit? */
    def cancelled(unit: CompilationUnit) = {
      // run the typer only if in `createJavadoc` mode
      val maxJavaPhase = if (createJavadoc) currentRun.typerPhase.id else currentRun.namerPhase.id
      reporter.cancelled || unit.isJava && this.id > maxJavaPhase
    }

    // Cleanup method needed for some phases, for example typer
    def afterUnit(unit: CompilationUnit): Unit = {}

    def run(): Unit = {
      assertOnMain()

      if (isDebugPrintEnabled) inform("[running phase " + name + " on " + currentRun.size +  " compilation units]")
      implicit val ec: ExecutionContextExecutor = createExecutionContext()

      try {
        _synchronizeNames = isParallel

        /* Every unit is now run in separate `Future`. If given phase is not ran as parallel one
         * (which is indicated by `isParallel`) it's swill run on the main thread. This is accomplished by
         * properly modified `ExecutionContext` returned by `createExecutionContext`.
         */
        val futures = currentRun.units.toList.collect {
          case unit if !cancelled(unit) =>
            Future {
              asWorkerThread {
                processUnit(unit)
                afterUnit(unit)
                reporter
              }
            }
        }

        /* Dumping messages from unit's `BufferedReporter` to main reporter.
         * Since we are awaiting for previous units this allows us to retain messages order.
         */
        futures.foreach { future =>
          val workerReporter = Await.result(future, Duration.Inf)
          if (isParallel) workerReporter.asInstanceOf[BufferedReporter].flushTo(reporter)
        }
      } finally {
        _synchronizeNames = false

        ec match {
          case ecxs: ExecutionContextExecutorService => ecxs.shutdown()
          case _ =>
        }
      }
    }

    // Used in methods like `compileLate`
    final def applyPhase(unit: CompilationUnit): Unit = {
      assertOnWorker()
      if (!cancelled(unit)) processUnit(unit)
    }

    private def processUnit(unit: CompilationUnit): Unit = {
      assertOnWorker()

      /* In worker threads if we are processing units in parallel we want to use temporary `BufferedReporter` for every unit.
       * Then later we can then keep it until all previous units are processed and then dump all messages to main reporter.
       */
      if (isParallel) reporter = new BufferedReporter

      if (isDebugPrintEnabled) inform("[running phase " + name + " on " + unit + "]")

      val unit0 = currentUnit

      try {
        if ((unit ne null) && unit.exists) lastSeenSourceFile = unit.source
        currentRun.currentUnit = unit
        apply(unit)
      } finally {
        currentRun.currentUnit = unit0
        currentRun.advanceUnit()
      }
    }

    /* Only output a summary message under debug if we aren't echoing each file. */
    private def isDebugPrintEnabled: Boolean = settings.debug && !(settings.verbose || currentRun.size < 5)

    private def isParallel = settings.YparallelPhases.containsPhase(this)

    /* Depending if we are in the parallel phase or not it creates executor with fixed thread pool size or
     * executor which runs everything on the current thread.
     */
    private def createExecutionContext(): ExecutionContextExecutor = {
      if (isParallel) {
        val parallelThreads = settings.YparallelThreads.value
        val threadPoolFactory = ThreadPoolFactory(Global.this, this)
        val javaExecutor = threadPoolFactory.newUnboundedQueueFixedThreadPool(parallelThreads, "worker")
        ExecutionContext.fromExecutorService(javaExecutor, _ => ())
      } else ExecutionContext.fromExecutor((task: Runnable) => task.run())
    }
  }

  // phaseName = "parser"
  lazy val syntaxAnalyzer = new {
    val global: Global.this.type = Global.this
  } with SyntaxAnalyzer {
    val runsAfter = List[String]()
    val runsRightAfter = None
    override val initial = true
  }

  import syntaxAnalyzer.{ UnitScanner, UnitParser, JavaUnitParser }

  // !!! I think we're overdue for all these phase objects being lazy vals.
  // There's no way for a Global subclass to provide a custom typer
  // despite the existence of a "def newTyper(context: Context): Typer"
  // which is clearly designed for that, because it's defined in
  // Analyzer and Global's "object analyzer" allows no override. For now
  // I only changed analyzer.
  //
  // factory for phases: namer, packageobjects, typer
  lazy val analyzer =
    if (settings.YmacroAnnotations) new { val global: Global.this.type = Global.this } with Analyzer with MacroAnnotationNamers
    else new { val global: Global.this.type = Global.this } with Analyzer

  // phaseName = "patmat"
  object patmat extends {
    val global: Global.this.type = Global.this
    // patmat does not need to run before the superaccessors phase, because
    // patmat never emits `this.x` where `x` is a ParamAccessor.
    // (However, patmat does need to run before outer accessors generation).
    val runsAfter = List("superaccessors")
    val runsRightAfter = None
  } with PatternMatching

  // phaseName = "superaccessors"
  object superAccessors extends {
    val global: Global.this.type = Global.this
    val runsAfter = List("typer")
    // sbt needs to run right after typer, so don't conflict
    val runsRightAfter = None
  } with SuperAccessors

  // phaseName = "extmethods"
  object extensionMethods extends {
    val global: Global.this.type = Global.this
    val runsAfter = List("patmat")
    val runsRightAfter = None
  } with ExtensionMethods

  // phaseName = "pickler"
  object pickler extends {
    val global: Global.this.type = Global.this
    val runsAfter = List("extmethods")
    val runsRightAfter = None
  } with Pickler

  // phaseName = "refchecks"
  object refChecks extends {
    val global: Global.this.type = Global.this
    val runsAfter = List("pickler")
    val runsRightAfter = None
  } with RefChecks

  // phaseName = "uncurry"
  override object uncurry extends {
    val global: Global.this.type = Global.this
    val runsAfter = List("refchecks")
    val runsRightAfter = None
  } with UnCurry

  // phaseName = "tailcalls"
  object tailCalls extends {
    val global: Global.this.type = Global.this
    val runsAfter = List("uncurry")
    val runsRightAfter = None
  } with TailCalls

  // phaseName = "fields"
  object fields extends {
    val global: Global.this.type = Global.this
    // after refchecks, so it doesn't have to make weird exceptions for synthetic accessors
    // after uncurry as it produces more work for the fields phase as well as being confused by it:
    //   - sam expansion synthesizes classes, which may need trait fields mixed in
    //   - the fields phase adds synthetic abstract methods to traits that should not disqualify them from being a SAM type
    // before erasure: correct signatures & bridges for accessors
    val runsAfter = List("uncurry")
    val runsRightAfter = None
  } with Fields

  // phaseName = "explicitouter"
  object explicitOuter extends {
    val global: Global.this.type = Global.this
    val runsAfter = List("fields")
    val runsRightAfter = None
  } with ExplicitOuter

  // phaseName = "specialize"
  object specializeTypes extends {
    val global: Global.this.type = Global.this
    val runsAfter = List("")
    val runsRightAfter = Some("tailcalls")
  } with SpecializeTypes

  // phaseName = "erasure"
  override object erasure extends {
    val global: Global.this.type = Global.this
    val runsAfter = List("explicitouter")
    val runsRightAfter = Some("explicitouter")
  } with Erasure

  // phaseName = "posterasure"
  override object postErasure extends {
    val global: Global.this.type = Global.this
    val runsAfter = List("erasure")
    val runsRightAfter = Some("erasure")
  } with PostErasure


  // phaseName = "lambdalift"
  object lambdaLift extends {
    val global: Global.this.type = Global.this
    val runsAfter = List("erasure")
    val runsRightAfter = None
  } with LambdaLift

  // phaseName = "constructors"
  object constructors extends {
    val global: Global.this.type = Global.this
    val runsAfter = List("lambdalift")
    val runsRightAfter = None
  } with Constructors

  // phaseName = "flatten"
  object flatten extends {
    val global: Global.this.type = Global.this
    val runsAfter = List("constructors")
    val runsRightAfter = None
  } with Flatten

  // phaseName = "mixin"
  object mixer extends {
    val global: Global.this.type = Global.this
    val runsAfter = List("flatten", "constructors")
    val runsRightAfter = None
  } with Mixin

  // phaseName = "cleanup"
  object cleanup extends {
    val global: Global.this.type = Global.this
    val runsAfter = List("mixin")
    val runsRightAfter = None
  } with CleanUp

  // phaseName = "delambdafy"
  object delambdafy extends {
    val global: Global.this.type = Global.this
    val runsAfter = List("cleanup")
    val runsRightAfter = None
  } with Delambdafy

  // phaseName = "jvm"
  object genBCode extends {
    val global: Global.this.type = Global.this
    val runsAfter = List("cleanup")
    val runsRightAfter = None
  } with GenBCode

  // phaseName = "terminal"
  object terminal extends {
    val global: Global.this.type = Global.this
  } with SubComponent {
    val phaseName = "terminal"
    val runsAfter = List("jvm")
    val runsRightAfter = None
    override val terminal = true

    def newPhase(prev: Phase): GlobalPhase = {
      new TerminalPhase(prev)
    }
    private class TerminalPhase(prev: Phase) extends GlobalPhase(prev) {
      def name = phaseName
      def apply(unit: CompilationUnit): Unit = {}
    }
  }

  /** The checkers are for validating the compiler data structures
   *  at phase boundaries.
   */

  /** Tree checker */
  object treeChecker extends {
    val global: Global.this.type = Global.this
  } with TreeCheckers

  object typer extends analyzer.Typer(
    analyzer.NoContext.make(EmptyTree, RootClass, newScope)
  )

  /** Add the internal compiler phases to the phases set.
   *  This implementation creates a description map at the same time.
   */
  protected def computeInternalPhases(): Unit = {
    // Note: this fits -Xshow-phases into 80 column width, which is
    // desirable to preserve.
    val phs = List(
      syntaxAnalyzer          -> "parse source into ASTs, perform simple desugaring",
      analyzer.namerFactory   -> "resolve names, attach symbols to named trees",
      analyzer.packageObjects -> "load package objects",
      analyzer.typerFactory   -> "the meat and potatoes: type the trees",
      superAccessors          -> "add super accessors in traits and nested classes",
      patmat                  -> "translate match expressions",
      extensionMethods        -> "add extension methods for inline classes",
      pickler                 -> "serialize symbol tables",
      refChecks               -> "reference/override checking, translate nested objects",
      uncurry                 -> "uncurry, translate function values to anonymous classes",
      fields                  -> "synthesize accessors and fields, add bitmaps for lazy vals",
      tailCalls               -> "replace tail calls by jumps",
      specializeTypes         -> "@specialized-driven class and method specialization",
      explicitOuter           -> "this refs to outer pointers",
      erasure                 -> "erase types, add interfaces for traits",
      postErasure             -> "clean up erased inline classes",
      lambdaLift              -> "move nested functions to top level",
      constructors            -> "move field definitions into constructors",
      mixer                   -> "mixin composition",
      delambdafy              -> "remove lambdas",
      cleanup                 -> "platform-specific cleanups, generate reflective calls",
      terminal                -> "the last phase during a compilation run"
    )

    phs foreach (addToPhasesSet _).tupled
  }
  // This is slightly inelegant but it avoids adding a new member to SubComponent,
  // and attractive -Xshow-phases output is unlikely if the descs span 20 files anyway.
  private val otherPhaseDescriptions = Map(
    "flatten"  -> "eliminate inner classes",
    "jvm"      -> "generate JVM bytecode"
  ) withDefaultValue ""

  protected def computePlatformPhases() = platform.platformPhases foreach { sub =>
    addToPhasesSet(sub, otherPhaseDescriptions(sub.phaseName))
  }

  // sequences the phase assembly
  protected def computePhaseDescriptors: List[SubComponent] = {
    /** Allow phases to opt out of the phase assembly. */
    def cullPhases(phases: List[SubComponent]) = {
      val enabled = if (settings.debug && settings.isInfo) phases else phases filter (_.enabled)
      def isEnabled(q: String) = enabled exists (_.phaseName == q)
      val (satisfied, unhappy) = enabled partition (_.requires forall isEnabled)
      unhappy foreach (u => globalError(s"Phase '${u.phaseName}' requires: ${u.requires filterNot isEnabled}"))
      satisfied   // they're happy now, but they may need an unhappy phase that was booted
    }
    computeInternalPhases()             // Global.scala
    computePlatformPhases()             // backend/Platform.scala
    computePluginPhases()               // plugins/Plugins.scala
    cullPhases(computePhaseAssembly())  // PhaseAssembly.scala
  }

  /* The phase descriptor list. Components that are phase factories. */
  lazy val phaseDescriptors: List[SubComponent] = computePhaseDescriptors

  /* The set of phase objects that is the basis for the compiler phase chain */
  protected lazy val phasesSet     = new mutable.HashSet[SubComponent]
  protected lazy val phasesDescMap = new mutable.HashMap[SubComponent, String] withDefaultValue ""

  protected def addToPhasesSet(sub: SubComponent, descr: String): Unit = {
    phasesSet += sub
    phasesDescMap(sub) = descr
  }

  /** The names of the phases. */
  lazy val phaseNames = {
    new Run // force some initialization
    phaseDescriptors map (_.phaseName)
  }

  /** A description of the phases that will run in this configuration, or all if -Ydebug. */
  def phaseDescriptions: String = phaseHelp("description", elliptically = !settings.debug, phasesDescMap)

  /** Summary of the per-phase values of nextFlags and newFlags, shown under -Xshow-phases -Ydebug. */
  def phaseFlagDescriptions: String = {
    def fmt(ph: SubComponent) = {
      def fstr1 = if (ph.phaseNewFlags == 0L) "" else "[START] " + Flags.flagsToString(ph.phaseNewFlags)
      def fstr2 = if (ph.phaseNextFlags == 0L) "" else "[END] " + Flags.flagsToString(ph.phaseNextFlags)
      if (ph.initial) Flags.flagsToString(Flags.InitialFlags)
      else if (ph.phaseNewFlags != 0L && ph.phaseNextFlags != 0L) fstr1 + " " + fstr2
      else fstr1 + fstr2
    }
    phaseHelp("new flags", elliptically = !settings.debug, fmt)
  }

  /** Emit a verbose phase table.
   *  The table includes the phase id in the current assembly,
   *  or "oo" to indicate a skipped phase, or "xx" to indicate
   *  a disabled phase.
   *
   *  @param title descriptive header
   *  @param elliptically whether to truncate the description with an ellipsis (...)
   *  @param describe how to describe a component
   */
  private def phaseHelp(title: String, elliptically: Boolean, describe: SubComponent => String): String = {
    val Limit   = 16    // phase names should not be absurdly long
    val MaxCol  = 80    // because some of us edit on green screens
    val maxName = phaseNames map (_.length) max
    val width   = maxName min Limit
    val maxDesc = MaxCol - (width + 6)  // descriptions not novels
    val fmt     = if (settings.verbose || !elliptically) s"%${maxName}s  %2s  %s%n"
                  else s"%${width}.${width}s  %2s  %.${maxDesc}s%n"

    val line1 = fmt.format("phase name", "id", title)
    val line2 = fmt.format("----------", "--", "-" * title.length)

    // built-in string precision merely truncates
    import java.util.{ Formattable, FormattableFlags, Formatter }
    def dotfmt(s: String) = new Formattable {
      def foreshortened(s: String, max: Int) = (
        if (max < 0 || s.length <= max) s
        else if (max < 4) s.take(max)
        else s.take(max - 3) + "..."
      )
      override def formatTo(formatter: Formatter, flags: Int, width: Int, precision: Int): Unit = {
        val p = foreshortened(s, precision)
        val w = if (width > 0 && p.length < width) {
          import FormattableFlags.LEFT_JUSTIFY
          val leftly = (flags & LEFT_JUSTIFY) == LEFT_JUSTIFY
          val sb = new StringBuilder
          def pad() = 1 to width - p.length foreach (_ => sb.append(' '))
          if (!leftly) pad()
          sb.append(p)
          if (leftly) pad()
          sb.toString
        } else p
        formatter.out.append(w)
      }
    }

    // phase id in run, or suitable icon
    def idOf(p: SubComponent) = (
      if (settings.skip contains p.phaseName) "oo"   // (currentRun skipPhase p.phaseName)
      else if (!p.enabled) "xx"
      else p.ownPhase.id.toString
    )
    def mkText(p: SubComponent) = {
      val (name, text) = if (elliptically) (dotfmt(p.phaseName), dotfmt(describe(p)))
                         else (p.phaseName, describe(p))
      fmt.format(name, idOf(p), text)
    }
    (line1 :: line2 :: (phaseDescriptors map mkText)).mkString
  }

  /** Returns List of (phase, value) pairs, including only those
   *  where the value compares unequal to the previous phase's value.
   */
  def afterEachPhase[T](op: => T): List[(Phase, T)] = { // used in tests
    phaseDescriptors.map(_.ownPhase).filterNot(_ eq NoPhase).foldLeft(List[(Phase, T)]()) { (res, ph) =>
      val value = exitingPhase(ph)(op)
      if (res.nonEmpty && res.head._2 == value) res
      else ((ph, value)) :: res
    } reverse
  }

  // ------------ REPL utilities ---------------------------------

  /** Extend classpath of `platform` and rescan updated packages. */
  def extendCompilerClassPath(urls: URL*): Unit = {
    val urlClasspaths = urls.map(u => ClassPathFactory.newClassPath(AbstractFile.getURL(u), settings))
    val newClassPath = AggregateClassPath.createAggregate(platform.classPath +: urlClasspaths : _*)
    platform.currentClassPath = Some(newClassPath)
    invalidateClassPathEntries(urls.map(_.getPath): _*)
  }

  // ------------ Invalidations ---------------------------------

  /** Is given package class a system package class that cannot be invalidated?
   */
  private def isSystemPackageClass(pkg: Symbol) =
    pkg == RootClass || (pkg.hasTransOwner(definitions.ScalaPackageClass) && !pkg.hasTransOwner(this.rootMirror.staticPackage("scala.tools").moduleClass.asClass))

  /** Invalidates packages that contain classes defined in a classpath entry, and
   *  rescans that entry.
   *
   *  First, the classpath entry referred to by one of the `paths` is rescanned,
   *  so that any new files or changes in subpackages are picked up.
   *  Second, any packages for which one of the following conditions is met is invalidated:
   *   - the classpath entry contained during the last compilation run now contains classfiles
   *     that represent a member in the package;
   *   - the classpath entry now contains classfiles that represent a member in the package;
   *   - the set of subpackages has changed.
   *
   *  The invalidated packages are reset in their entirety; all member classes and member packages
   *  are re-accessed using the new classpath.
   *
   *  System packages that the compiler needs to access as part of standard definitions
   *  are not invalidated. A system package is:
   *  Any package rooted in "scala", with the exception of packages rooted in "scala.tools".
   *
   *  @param paths  Fully-qualified names that refer to directories or jar files that are
   *                entries on the classpath.
   */
  def invalidateClassPathEntries(paths: String*): Unit = {
    implicit object ClassPathOrdering extends Ordering[ClassPath] {
      def compare(a: ClassPath, b: ClassPath): Int = a.asClassPathString compareTo b.asClassPathString
    }
    val invalidated, failed = new mutable.ListBuffer[ClassSymbol]

    def assoc(path: String): Option[(ClassPath, ClassPath)] = {
      def origin(lookup: ClassPath): Option[String] = lookup match {
        case cp: JFileDirectoryLookup[_] => Some(cp.dir.getPath)
        case cp: ZipArchiveFileLookup[_] => Some(cp.zipFile.getPath)
        case _ => None
      }

      def entries(lookup: ClassPath): Seq[ClassPath] = lookup match {
        case cp: AggregateClassPath => cp.aggregates
        case cp: ClassPath => Seq(cp)
      }

      val dir = AbstractFile.getDirectory(path) // if path is a `jar`, this is a FileZipArchive (isDirectory is true)
      val canonical = dir.canonicalPath         // this is the canonical path of the .jar
      def matchesCanonical(e: ClassPath) = origin(e) match {
        case Some(opath) =>
          AbstractFile.getDirectory(opath).canonicalPath == canonical
        case None =>
          false
      }
      entries(classPath) find matchesCanonical match {
        case Some(oldEntry) =>
          Some(oldEntry -> ClassPathFactory.newClassPath(dir, settings))
        case None =>
          error(s"Error adding entry to classpath. During invalidation, no entry named $path in classpath $classPath")
          None
      }
    }
    val subst = immutable.TreeMap(paths flatMap assoc: _*)
    if (subst.nonEmpty) {
      platform updateClassPath subst
      informProgress(s"classpath updated on entries [${subst.keys mkString ","}]")
      def mkClassPath(elems: Iterable[ClassPath]): ClassPath =
        if (elems.size == 1) elems.head
        else AggregateClassPath.createAggregate(elems.toSeq: _*)
      val oldEntries = mkClassPath(subst.keys)
      val newEntries = mkClassPath(subst.values)
      classPath match {
        case cp: ClassPath => mergeNewEntries(
          RootClass, "",
          oldEntries, newEntries, cp,
          invalidated, failed)
      }
    }
    def show(msg: String, syms: scala.collection.Iterable[Symbol]) =
      if (syms.nonEmpty)
        informProgress(s"$msg: ${syms map (_.fullName) mkString ","}")
    show("invalidated packages", invalidated)
    show("could not invalidate system packages", failed)
  }

  /**
   * Merges new classpath entries into the symbol table
   *
   * @param packageClass    The ClassSymbol for the package being updated
   * @param fullPackageName The full name of the package being updated
   * @param oldEntries      The classpath that was removed, it is no longer part of fullClasspath
   * @param newEntries      The classpath that was added, it is already part of fullClasspath
   * @param fullClasspath   The full classpath, equivalent to global.classPath
   * @param invalidated     A ListBuffer collecting the invalidated package classes
   * @param failed          A ListBuffer collecting system package classes which could not be invalidated
   *
   * If either oldEntries or newEntries contains classes in the current package, the package symbol
   * is re-initialized to a fresh package loader, provided that a corresponding package exists in
   * fullClasspath. Otherwise it is removed.
   *
   * Otherwise, sub-packages in newEntries are looked up in the symbol table (created if
   * non-existent) and the merge function is called recursively.
   */
  private def mergeNewEntries(packageClass: ClassSymbol, fullPackageName: String,
                              oldEntries: ClassPath, newEntries: ClassPath, fullClasspath: ClassPath,
                              invalidated: mutable.ListBuffer[ClassSymbol], failed: mutable.ListBuffer[ClassSymbol]): Unit = {
    ifDebug(informProgress(s"syncing $packageClass, $oldEntries -> $newEntries"))

    def packageExists(cp: ClassPath): Boolean = {
      val (parent, _) = PackageNameUtils.separatePkgAndClassNames(fullPackageName)
      cp.packages(parent).exists(_.name == fullPackageName)
    }

    def invalidateOrRemove(pkg: ClassSymbol) = {
      if (packageExists(fullClasspath))
        pkg setInfo new loaders.PackageLoader(fullPackageName, fullClasspath)
      else
        pkg.owner.info.decls unlink pkg.sourceModule
      invalidated += pkg
    }

    val classesFound = oldEntries.classes(fullPackageName).nonEmpty || newEntries.classes(fullPackageName).nonEmpty
    if (classesFound) {
      // if the package contains classes either in oldEntries or newEntries, the package is invalidated (or removed if there are no more classes in it)
      if (!isSystemPackageClass(packageClass)) invalidateOrRemove(packageClass)
      else if (packageClass.isRoot) invalidateOrRemove(EmptyPackageClass)
      else failed += packageClass
    } else {
      // no new or removed classes in the current package
      for (p <- newEntries.packages(fullPackageName)) {
        val (_, subPackageName) = PackageNameUtils.separatePkgAndClassNames(p.name)
        val subPackage = packageClass.info.decl(newTermName(subPackageName)) orElse {
          // package does not exist in symbol table, create a new symbol
          loaders.enterPackage(packageClass, subPackageName, new loaders.PackageLoader(p.name, fullClasspath))
        }
        mergeNewEntries(
          subPackage.moduleClass.asClass, p.name,
          oldEntries, newEntries, fullClasspath,
          invalidated, failed)
      }
    }
  }

  // ----------- Runs ---------------------------------------

  private var curRun: Run = null
  private var curRunId = 0

  object typeDeconstruct extends {
    val global: Global.this.type = Global.this
  } with typechecker.StructuredTypeStrings

  /** There are common error conditions where when the exception hits
    * here, currentRun.currentUnit is null.  This robs us of the knowledge
    * of what file was being compiled when it broke.  Since I really
    * really want to know, this hack.
    *
    * pkukielka: I'm not sure if I believe above comment but nevertheless
    * if we want to keep that design we need to keep one `lastSeenSourceFile` per thread.
    * Othervise in case of failure we would be reporting last unit/file which started to be processing
    * which may or may not be the same as the one which failed.
   */
  private[this] final val _lastSeenSourceFile: WorkerThreadLocal[SourceFile] = WorkerThreadLocal(NoSourceFile)
  @inline protected def lastSeenSourceFile: SourceFile = _lastSeenSourceFile.get
  @inline protected def lastSeenSourceFile_=(source: SourceFile): Unit = _lastSeenSourceFile.set(source)

  /** Let's share a lot more about why we crash all over the place.
   *  People will be very grateful.
   */
  protected var lastSeenContext: analyzer.Context = analyzer.NoContext

  /** The currently active run
   */
  def currentRun: Run              = curRun
  def currentUnit: CompilationUnit = if (currentRun eq null) NoCompilationUnit else currentRun.currentUnit
  def currentSource: SourceFile    = if (currentUnit.exists) currentUnit.source else lastSeenSourceFile
  def currentFreshNameCreator      = currentUnit.fresh

  def isGlobalInitialized = (
       definitions.isDefinitionsInitialized
    && rootMirror.isMirrorInitialized
  )
  override def isPastTyper = isPast(currentRun.typerPhase)
  def isPast(phase: Phase) = (
       (curRun ne null)
    && isGlobalInitialized // defense against init order issues
    && (globalPhase.id > phase.id)
  )

  // TODO - trim these to the absolute minimum.
  @inline final def exitingErasure[T](op: => T): T        = exitingPhase(currentRun.erasurePhase)(op)
  @inline final def exitingPostErasure[T](op: => T): T    = exitingPhase(currentRun.posterasurePhase)(op)
  @inline final def exitingExplicitOuter[T](op: => T): T  = exitingPhase(currentRun.explicitouterPhase)(op)
  @inline final def exitingFlatten[T](op: => T): T        = exitingPhase(currentRun.flattenPhase)(op)
  @inline final def exitingMixin[T](op: => T): T          = exitingPhase(currentRun.mixinPhase)(op)
  @inline final def exitingDelambdafy[T](op: => T): T     = exitingPhase(currentRun.delambdafyPhase)(op)
  @inline final def exitingPickler[T](op: => T): T        = exitingPhase(currentRun.picklerPhase)(op)
  @inline final def exitingRefchecks[T](op: => T): T      = exitingPhase(currentRun.refchecksPhase)(op)
  @inline final def exitingSpecialize[T](op: => T): T     = exitingPhase(currentRun.specializePhase)(op)
  @inline final def exitingTyper[T](op: => T): T          = exitingPhase(currentRun.typerPhase)(op)
  @inline final def exitingUncurry[T](op: => T): T        = exitingPhase(currentRun.uncurryPhase)(op)
  @inline final def enteringErasure[T](op: => T): T       = enteringPhase(currentRun.erasurePhase)(op)
  @inline final def enteringExplicitOuter[T](op: => T): T = enteringPhase(currentRun.explicitouterPhase)(op)
  @inline final def enteringFlatten[T](op: => T): T       = enteringPhase(currentRun.flattenPhase)(op)
  @inline final def enteringMixin[T](op: => T): T         = enteringPhase(currentRun.mixinPhase)(op)
  @inline final def enteringDelambdafy[T](op: => T): T    = enteringPhase(currentRun.delambdafyPhase)(op)
  @inline final def enteringJVM[T](op: => T): T           = enteringPhase(currentRun.jvmPhase)(op)
  @inline final def enteringPickler[T](op: => T): T       = enteringPhase(currentRun.picklerPhase)(op)
  @inline final def enteringSpecialize[T](op: => T): T    = enteringPhase(currentRun.specializePhase)(op)
  @inline final def enteringTyper[T](op: => T): T         = enteringPhase(currentRun.typerPhase)(op)
  @inline final def enteringUncurry[T](op: => T): T       = enteringPhase(currentRun.uncurryPhase)(op)

  // Owners which aren't package classes.
  private def ownerChainString(sym: Symbol): String = (
    if (sym == null) ""
    else sym.ownerChain takeWhile (!_.isPackageClass) mkString " -> "
  )


  /** Don't want to introduce new errors trying to report errors,
   *  so swallow exceptions.
   */
  override def supplementTyperState(errorMessage: String): String = try {
    def formatExplain(pairs: List[(String, Any)]): String =
      pairs collect { case (k, v) if v != null => f"$k%20s: $v" } mkString "\n"

    val tree      = analyzer.lastTreeToTyper
    val sym       = tree.symbol
    val tpe       = tree.tpe
    val site      = lastSeenContext.enclClassOrMethod.owner
    val pos_s     = if (tree.pos.isDefined) s"line ${tree.pos.line} of ${tree.pos.source.file}" else "<unknown>"
    val context_s = try {
      // Taking 3 before, 3 after the fingered line.
      val start = 0 max (tree.pos.line - 4)
      val xs = tree.pos.source.lines(start, start + 7)
      val strs = xs.zipWithIndex map { case (line, idx) => f"${start + idx + 1}%6d $line" }
      strs.mkString("== Source file context for tree position ==\n\n", "\n", "")
    }
    catch { case t: Exception => devWarning("" + t) ; "<Cannot read source file>" }

    val info1 = formatExplain(List(
      "while compiling"    -> currentSource.path,
      "during phase"       -> ( if (globalPhase eq phase) phase else "globalPhase=%s, enteringPhase=%s".format(globalPhase, phase) ),
      "library version"    -> scala.util.Properties.versionString,
      "compiler version"   -> scala.tools.nsc.Properties.versionString,
      "reconstructed args" -> settings.recreateArgs.mkString(" ")
    ))
    // useful things to know if we have a sym
    val symbolInfos = if (sym eq null) List("symbol" -> "null") else List(
      "symbol"             -> sym.debugLocationString,
      "symbol definition"  -> s"${sym.defString} (a ${sym.shortSymbolClass})",
      "symbol package"     -> sym.enclosingPackage.fullName,
      "symbol owners"      -> ownerChainString(sym),
    )
    val info2 = formatExplain(List(
      "last tree to typer" -> tree.summaryString,
      "tree position"      -> pos_s,
      "tree tpe"           -> tpe
    ) ::: symbolInfos ::: List(
      "call site"          -> (site.fullLocationString + " in " + site.enclosingPackage)
    ))
    ("\n  " + errorMessage + "\n" + info1) :: info2 :: context_s :: Nil mkString "\n\n"
  } catch { case _: Exception | _: TypeError => errorMessage }


  /** The id of the currently active run
   */
  override def currentRunId = curRunId

  def newSourceFile(code: String, filename: String = "<console>") =
    new BatchSourceFile(filename, code)

  def newCompilationUnit(code: String, filename: String = "<console>") =
    new CompilationUnit(newSourceFile(code, filename))

  def newUnitScanner(unit: CompilationUnit): UnitScanner =
    new UnitScanner(unit)

  def newUnitParser(unit: CompilationUnit): UnitParser =
    new UnitParser(unit)

  def newUnitParser(code: String, filename: String = "<console>"): UnitParser =
    newUnitParser(newCompilationUnit(code, filename))

  def newJavaUnitParser(unit: CompilationUnit): JavaUnitParser = new JavaUnitParser(unit)

  /** A Run is a single execution of the compiler on a set of units.
   */
  class Run extends RunContextApi with RunReporting with RunParsing {
    /** Have been running into too many init order issues with Run
     *  during erroneous conditions.  Moved all these vals up to the
     *  top of the file so at least they're not trivially null.
     */
    var isDefined = false
    /** The currently compiled unit; set from GlobalPhase */
    private[this] final val _currentUnit: WorkerOrMainThreadLocal[CompilationUnit] = WorkerThreadLocal(NoCompilationUnit)
    def currentUnit: CompilationUnit = _currentUnit.get
    def currentUnit_=(unit: CompilationUnit): Unit = _currentUnit.set(unit)

    val profiler: Profiler = Profiler(settings)
    keepPhaseStack = settings.log.isSetByUser

    // used in sbt
    def uncheckedWarnings: List[(Position, String)]   = reporting.uncheckedWarnings.map{case (pos, (msg, since)) => (pos, msg)}
    // used in sbt
    def deprecationWarnings: List[(Position, String)] = reporting.deprecationWarnings.map{case (pos, (msg, since)) => (pos, msg)}

    private class SyncedCompilationBuffer { self =>
      private val underlying = new mutable.ArrayBuffer[CompilationUnit]
      def size = synchronized { underlying.size }
      def +=(cu: CompilationUnit): this.type = { synchronized { underlying += cu }; this }
      def head: CompilationUnit = synchronized{ underlying.head }
      def apply(i: Int): CompilationUnit = synchronized { underlying(i) }
      def iterator: Iterator[CompilationUnit] = new collection.AbstractIterator[CompilationUnit] {
        private var used = 0
        def hasNext = self.synchronized{ used < underlying.size }
        def next = self.synchronized {
          if (!hasNext) throw new NoSuchElementException("next on empty Iterator")
          used += 1
          underlying(used-1)
        }
      }
      def toList: List[CompilationUnit] = synchronized{ underlying.toList }
    }

    private val unitbuf = new SyncedCompilationBuffer

    val compiledFiles   = new mutable.HashSet[String]

    /** A map from compiled top-level symbols to their source files */
    val symSource = new mutable.AnyRefMap[Symbol, AbstractFile]

    /** A map from compiled top-level symbols to their picklers */
    val symData = new mutable.AnyRefMap[Symbol, PickleBuffer]

    private var phasec: Int = 0                     // phases completed
    private final val unitc: Counter = new Counter  // units completed this phase

    def size = unitbuf.size
    override def toString = "scalac Run for:\n  " + compiledFiles.toList.sorted.mkString("\n  ")

    // Calculate where to stop based on settings -Ystop-before or -Ystop-after.
    // The result is the phase to stop at BEFORE running it.
    private lazy val stopPhaseSetting = {
      def isBefore(pd: SubComponent) = settings.stopBefore contains pd.phaseName
      phaseDescriptors sliding 2 collectFirst {
        case xs if xs exists isBefore
                => (xs find isBefore).get
        case xs if settings.stopAfter contains xs.head.phaseName
                => xs.last
      }
    }
    /** Should we stop right before entering the given phase? */
    protected def stopPhase(name: String) = stopPhaseSetting exists (_.phaseName == name)
    /** Should we skip the given phase? */
    protected def skipPhase(name: String) = settings.skip contains name

    private val firstPhase = {
      // Initialization.  definitions.init requires phase != NoPhase
      import scala.reflect.internal.SomePhase
      curRunId += 1
      curRun = this
      phase = SomePhase
      phaseWithId(phase.id) = phase
      definitions.init()

      // the components to use, omitting those named by -Yskip and stopping at the -Ystop phase
      val components = {
        // stop on a dime, but this test fails if pd is after the stop phase
        def unstoppable(pd: SubComponent) = {
          val stoppable = stopPhase(pd.phaseName)
          if (stoppable && pd.initial) {
            globalError(s"Cannot stop before initial phase '${pd.phaseName}'.")
            true
          } else
            !stoppable
        }
        // skip a component for -Yskip or if not enabled
        def skippable(pd: SubComponent) = {
          val skippable = skipPhase(pd.phaseName)
          if (skippable && (pd.initial || pd.terminal)) {
            globalError(s"Cannot skip an initial or terminal phase '${pd.phaseName}'.")
            false
          } else
            skippable || !pd.enabled
        }
        val phs = phaseDescriptors takeWhile unstoppable filterNot skippable
        // Ensure there is a terminal phase at the end, since -Ystop may have limited the phases.
        if (phs.isEmpty || !phs.last.terminal) {
          val t = if (phaseDescriptors.last.terminal) phaseDescriptors.last else terminal
          phs :+ t
        } else phs
      }
      // Create phases and link them together. We supply the previous, and the ctor sets prev.next.
      val last  = components.foldLeft(NoPhase: Phase)((prev, c) => c newPhase prev)
      // rewind (Iterator.iterate(last)(_.prev) dropWhile (_.prev ne NoPhase)).next
      val first = { var p = last ; while (p.prev ne NoPhase) p = p.prev ; p }
      val ss    = settings

      // As a final courtesy, see if the settings make any sense at all.
      // If a setting selects no phase, it's a mistake. If a name prefix
      // doesn't select a unique phase, that might be surprising too.
      def checkPhaseSettings(including: Boolean, specs: Seq[String]*) = {
        def isRange(s: String) = s.forall(c => c.isDigit || c == '-')
        def isSpecial(s: String) = (s == "all" || isRange(s))
        val setting = new ss.PhasesSetting("fake","fake")
        for (p <- specs.flatten.to(Set)) {
          setting.value = List(p)
          val count = (
            if (including) first.iterator count (setting containsPhase _)
            else phaseDescriptors count (setting contains _.phaseName)
          )
          if (count == 0) warning(s"'$p' specifies no phase")
          if (count > 1 && !isSpecial(p)) warning(s"'$p' selects $count phases")
          if (!including && isSpecial(p)) globalError(s"-Yskip and -Ystop values must name phases: '$p'")
          setting.clear()
        }
      }
      // phases that are excluded; for historical reasons, these settings only select by phase name
      val exclusions = List(ss.stopBefore, ss.stopAfter, ss.skip)
      val inclusions = ss.visibleSettings collect {
        case s: ss.PhasesSetting if !(exclusions contains s) => s.value
      }
      checkPhaseSettings(including = true, inclusions.toSeq: _*)
      checkPhaseSettings(including = false, exclusions map (_.value): _*)

      // Enable or disable depending on the current setting -- useful for interactive behaviour
      statistics.initFromSettings(settings)

      // Report the overhead of statistics measurements per every run
      if (statistics.areStatisticsLocallyEnabled)
        statistics.reportStatisticsOverhead(reporter)

      phase = first   //parserPhase
      first
    }

    // --------------- Miscellanea -------------------------------

    /** Progress tracking.  Measured in "progress units" which are 1 per
     *  compilation unit per phase completed.
     *
     *  @param    current   number of "progress units" completed
     *  @param    total     total number of "progress units" in run
     */
    def progress(current: Int, total: Int): Unit = {}

    /**
     * For subclasses to override. Called when `phase` is about to be run on `unit`.
     * Variables are passed explicitly to indicate that `globalPhase` and `currentUnit` have been set.
     */
    def informUnitStarting(phase: Phase, unit: CompilationUnit): Unit = { }

    /** take note that phase is completed
     *  (for progress reporting)
     */
    def advancePhase(): Unit = {
      unitc.reset()
      phasec += 1
      refreshProgress()
    }
    /** take note that a phase on a unit is completed
     *  (for progress reporting)
     */
    def advanceUnit(): Unit = {
      unitc.incrementAndGet()
      refreshProgress()
    }

    // for sbt
    def cancel(): Unit = { reporter.cancelled = true }

    private def currentProgress   = (phasec * size) + unitc.get
    private def totalProgress     = (phaseDescriptors.size - 1) * size // -1: drops terminal phase
    private def refreshProgress() = if (size > 0) progress(currentProgress, totalProgress)

    // ----- finding phases --------------------------------------------

    def phaseNamed(name: String): Phase =
      findOrElse(firstPhase.iterator)(_.name == name)(NoPhase)

    /** All phases as of 3/2012 here for handiness; the ones in
     *  active use uncommented.
     */
    val parserPhase                  = phaseNamed("parser")
    val namerPhase                   = phaseNamed("namer")
    // val packageobjectsPhase          = phaseNamed("packageobjects")
    val typerPhase                   = phaseNamed("typer")
    // val inlineclassesPhase           = phaseNamed("inlineclasses")
    // val superaccessorsPhase          = phaseNamed("superaccessors")
    val picklerPhase                 = phaseNamed("pickler")
    val refchecksPhase               = phaseNamed("refchecks")
    val uncurryPhase                 = phaseNamed("uncurry")
    // val fieldsPhase                  = phaseNamed("fields")
    // val tailcallsPhase               = phaseNamed("tailcalls")
    val specializePhase              = phaseNamed("specialize")
    val explicitouterPhase           = phaseNamed("explicitouter")
    val erasurePhase                 = phaseNamed("erasure")
    val posterasurePhase             = phaseNamed("posterasure")
    val lambdaliftPhase              = phaseNamed("lambdalift")
    // val constructorsPhase            = phaseNamed("constructors")
    val flattenPhase                 = phaseNamed("flatten")
    val mixinPhase                   = phaseNamed("mixin")
    val delambdafyPhase              = phaseNamed("delambdafy")
    val cleanupPhase                 = phaseNamed("cleanup")
    val jvmPhase                     = phaseNamed("jvm")

    def runIsAt(ph: Phase)   = globalPhase.id == ph.id
    def runIsAtOptimiz       = runIsAt(jvmPhase)

    isDefined = true

    // ----------- Units and top-level classes and objects --------


    /** add unit to be compiled in this run */
    private def addUnit(unit: CompilationUnit): Unit = {
      unitbuf += unit
      compiledFiles += unit.source.file.path
    }
    private def warnDeprecatedAndConflictingSettings(): Unit = {
      // issue warnings for any usage of deprecated settings
      settings.userSetSettings filter (_.isDeprecated) foreach { s =>
        currentRun.reporting.deprecationWarning(NoPosition, s.name + " is deprecated: " + s.deprecationMessage.get, "")
      }
      val supportedTarget = "jvm-1.8"
      if (settings.target.value != supportedTarget) {
        currentRun.reporting.deprecationWarning(NoPosition, settings.target.name + ":" + settings.target.value + " is deprecated and has no effect, setting to " + supportedTarget, "2.12.0")
        settings.target.value = supportedTarget
      }
      settings.conflictWarning.foreach(reporter.warning(NoPosition, _))
    }

    /* An iterator returning all the units being compiled in this run */
    /* !!! Note: changing this to unitbuf.toList.iterator breaks a bunch
       of tests in tests/res.  This is bad, it means the resident compiler
       relies on an iterator of a mutable data structure reflecting changes
       made to the underlying structure.
     */
    def units: Iterator[CompilationUnit] = unitbuf.iterator

    def registerPickle(sym: Symbol): Unit = ()

    /** does this run compile given class, module, or case factory? */
    // NOTE: Early initialized members temporarily typechecked before the enclosing class, see typedPrimaryConstrBody!
    //       Here we work around that wrinkle by claiming that a pre-initialized member is compiled in
    //       *every* run. This approximation works because this method is exclusively called with `this` == `currentRun`.
    def compiles(sym: Symbol): Boolean =
      if (sym == NoSymbol) false
      else if (symSource.isDefinedAt(sym)) true
      else if (!sym.isTopLevel) compiles(sym.originalEnclosingTopLevelClassOrDummy)
      else if (sym.isModuleClass) compiles(sym.sourceModule)
      else false

    /** Is this run allowed to redefine the given symbol? Usually this is true
     *  if the run does not already compile `sym`, but for interactive mode
     *  we have a more liberal interpretation.
     */
    def canRedefine(sym: Symbol) = !compiles(sym)

    // --------------- Compilation methods ----------------------------

    protected def runCheckers(): Unit = {
      val toCheck  = globalPhase.prev
      val canCheck = toCheck.checkable
      val fmt      = if (canCheck) "[Now checking: %s]" else "[Not checkable: %s]"

      inform(fmt format toCheck.name)

      if (canCheck) {
        phase = globalPhase
        if (globalPhase.id <= cleanupPhase.id)
          treeChecker.checkTrees()
      }
    }

    private def showMembers() = {
      // Allows for syntax like scalac -Xshow-class Random@erasure,typer
      def splitClassAndPhase(str: String, term: Boolean): Name = {
        def mkName(s: String) = if (term) newTermName(s) else newTypeName(s)
        (str indexOf '@') match {
          case -1   => mkName(str)
          case idx  =>
            val phasePart = str drop (idx + 1)
            settings.Yshow.tryToSetColon(phasePart split ',' toList)
            mkName(str take idx)
        }
      }
      if (settings.Xshowcls.isSetByUser)
        showDef(splitClassAndPhase(settings.Xshowcls.value, term = false), declsOnly = false, globalPhase)

      if (settings.Xshowobj.isSetByUser)
        showDef(splitClassAndPhase(settings.Xshowobj.value, term = true), declsOnly = false, globalPhase)
    }

    // Similarly, this will only be created under -Yshow-syms.
    object trackerFactory extends SymbolTrackers {
      val global: Global.this.type = Global.this
      lazy val trackers = currentRun.units.toList map (x => SymbolTracker(x))
      def snapshot() = {
        inform("\n[[symbol layout at end of " + phase + "]]")
        exitingPhase(phase) {
          trackers foreach { t =>
            t.snapshot()
            inform(t.show("Heading from " + phase.prev.name + " to " + phase.name))
          }
        }
      }
    }


    /** Caching member symbols that are def-s in Definitions because they might change from Run to Run. */
    val runDefinitions: definitions.RunDefinitions = new definitions.RunDefinitions

    /** Compile list of source files,
     *  unless there is a problem already,
     *  such as a plugin was passed a bad option.
     */
    def compileSources(sources: List[SourceFile]): Unit = if (!reporter.hasErrors) {

      def checkDeprecations() = {
        warnDeprecatedAndConflictingSettings()
        reporting.summarizeErrors()
      }

      val units = sources map scripted map (new CompilationUnit(_))

      units match {
        case Nil => checkDeprecations()   // nothing to compile, report deprecated options
        case _   => compileUnits(units)
      }
    }

    private final val GlobalPhaseName = "global (synthetic)"
    protected final val totalCompileTime = statistics.newTimer("#total compile time", GlobalPhaseName)

    /* We assumes that everything that is done while compiling unit is done on the worker thread.
     * Most work is done (and most code is run) in the workers thread, comparing to main.
     * Because of that we make design decision that every thread is marked as worker by default.
     * It makes our life easier when dealing with tests, toolbox, and virtually everything which not goes through `Global`.
     * But now we need to somehow mark the main thread. That is done by `Parallel.asMainThread`.
     */
    def compileUnits(units: List[CompilationUnit], fromPhase: Phase = firstPhase): Unit = Parallel.asMainThread {
      compileUnitsInternal(units, fromPhase)
    }

    private def compileUnitsInternal(units: List[CompilationUnit], fromPhase: Phase): Unit = {
      units foreach addUnit
      reporter.reset()
      warnDeprecatedAndConflictingSettings()
      globalPhase = fromPhase

      val timePhases = statistics.areStatisticsLocallyEnabled
      val startTotal = if (timePhases) statistics.startTimer(totalCompileTime) else null

      while (globalPhase.hasNext && !reporter.hasErrors) {
        phase = globalPhase
        val phaseTimer = if (timePhases) statistics.newSubTimer(s"  ${phase.name}", totalCompileTime) else null
        val startPhase = if (timePhases) statistics.startTimer(phaseTimer) else null

        val profileBefore=profiler.beforePhase(phase)
        try globalPhase.run()
        finally if (timePhases) statistics.stopTimer(phaseTimer, startPhase) else ()
        profiler.afterPhase(phase, profileBefore)

        if (timePhases)
          informTime(globalPhase.description, phaseTimer.nanos)

        // progress update
        if ((settings.Xprint containsPhase globalPhase) || settings.printLate && runIsAt(cleanupPhase)) {
          // print trees
          if (settings.Xshowtrees || settings.XshowtreesCompact || settings.XshowtreesStringified) nodePrinters.printAll()
          else printAllUnits()
        }

        // print the symbols presently attached to AST nodes
        if (settings.Yshowsyms)
          trackerFactory.snapshot()

        // print members
        if (settings.Yshow containsPhase globalPhase)
          showMembers()

        // browse trees with swing tree viewer
        if (settings.browse containsPhase globalPhase)
          treeBrowser browse (phase.name, units)

        // move the pointer
        globalPhase = globalPhase.next

        // run tree checkers
        if (settings.check containsPhase globalPhase.prev)
          runCheckers()

        // output collected statistics
        if (settings.YstatisticsEnabled && settings.Ystatistics.contains(phase.name))
          printStatisticsFor(phase)

        advancePhase()
      }
      profiler.finished()

      reporting.summarizeErrors()

      if (traceSymbolActivity)
        units map (_.body) foreach (traceSymbols recordSymbolsInTree _)

      // In case no phase was specified for -Xshow-class/object, show it now for sure.
      if (settings.Yshow.isDefault)
        showMembers()

      if (reporter.hasErrors) {
        for ((sym, file) <- symSource.iterator) {
          if (file != null)
            sym.reset(new loaders.SourcefileLoader(file))
          if (sym.isTerm)
            sym.moduleClass reset loaders.moduleClassLoader
        }
      }
      symSource.keys foreach (x => resetPackageClass(x.owner))

      if (timePhases) {
        statistics.stopTimer(totalCompileTime, startTotal)
        informTime("total", totalCompileTime.nanos)
        inform("*** Cumulative timers for phases")
        for (q <- statistics.allQuantities if q.phases == List(GlobalPhaseName))
          inform(q.line)
      }

      // Clear any sets or maps created via perRunCaches.
      perRunCaches.clearAll()
    }

    /** Compile list of abstract files. */
    def compileFiles(files: List[AbstractFile]): Unit = {
      try {
        val snap = profiler.beforePhase(Global.InitPhase)
        val sources = files map getSourceFile
        profiler.afterPhase(Global.InitPhase, snap)
        compileSources(sources)
      }
      catch { case ex: IOException => globalError(ex.getMessage()) }
    }

    /** Compile list of files given by their names */
    def compile(filenames: List[String]): Unit = {
      try {
        val snap = profiler.beforePhase(Global.InitPhase)

        val sources: List[SourceFile] =
          if (settings.script.isSetByUser && filenames.size > 1) returning(Nil)(_ => globalError("can only compile one script at a time"))
          else filenames map getSourceFile

        profiler.afterPhase(Global.InitPhase, snap)
        compileSources(sources)
      }
      catch { case ex: IOException => globalError(ex.getMessage()) }
    }

    /** If this compilation is scripted, convert the source to a script source. */
    private def scripted(s: SourceFile) = s match {
      case b: BatchSourceFile if settings.script.isSetByUser => ScriptSourceFile(b)
      case _ => s
    }

    /** Compile abstract file until `globalPhase`, but at least
     *  to phase "namer".
     */
    def compileLate(file: AbstractFile): Unit = {
      if (!compiledFiles(file.path))
        compileLate(new CompilationUnit(scripted(getSourceFile(file))))
    }

    /** Compile abstract file until `globalPhase`, but at least to phase "namer".
     */
    def compileLate(unit: CompilationUnit): Unit = {
      addUnit(unit)

      if (firstPhase ne null) { // we might get here during initialization, is a source is newer than the binary
        val maxId = math.max(globalPhase.id, typerPhase.id)
        firstPhase.iterator takeWhile (_.id < maxId) foreach (ph =>
          enteringPhase(ph)(ph.asInstanceOf[GlobalPhase] applyPhase unit))
        refreshProgress()
      }
    }

    /** Reset package class to state at typer (not sure what this is needed for?)
     */
    private def resetPackageClass(pclazz: Symbol): Unit = if (typerPhase != NoPhase) {
      enteringPhase(firstPhase) {
        pclazz.setInfo(enteringPhase(typerPhase)(pclazz.info))
      }
      if (!pclazz.isRoot) resetPackageClass(pclazz.owner)
    }

    private val hotCounters =
      List(statistics.retainedCount, statistics.retainedByType, statistics.nodeByType)
    private val parserStats = {
      import statistics.treeNodeCount
      if (settings.YhotStatisticsEnabled) treeNodeCount :: hotCounters
      else List(treeNodeCount)
    }

    final def printStatisticsFor(phase: Phase) = {
      inform("*** Cumulative statistics at phase " + phase)

      if (settings.YhotStatisticsEnabled) {
        // High overhead, only enable retained stats under hot stats
        statistics.retainedCount.value = 0
        for (c <- statistics.retainedByType.keys)
          statistics.retainedByType(c).value = 0
        for (u <- currentRun.units; t <- u.body) {
          statistics.retainedCount.value += 1
          statistics.retainedByType(t.getClass).value += 1
        }
      }

      val quants: Iterable[statistics.Quantity] =
        if (phase.name == "parser") parserStats
        else if (settings.YhotStatisticsEnabled) statistics.allQuantities
        else statistics.allQuantities.filterNot(q => hotCounters.contains(q))
      for (q <- quants if q.showAt(phase.name)) inform(q.line)
    }
  } // class Run

  def printAllUnits(): Unit = {
    print("[[syntax trees at end of %25s]]".format(phase))
    exitingPhase(phase)(currentRun.units foreach { unit =>
      nodePrinters showUnit unit
    })
  }

  /** We resolve the class/object ambiguity by passing a type/term name.
   */
  def showDef(fullName: Name, declsOnly: Boolean, ph: Phase): Unit = {
    val boringOwners = Set[Symbol](definitions.AnyClass, definitions.AnyRefClass, definitions.ObjectClass)
    def phased[T](body: => T): T = exitingPhase(ph)(body)
    def boringMember(sym: Symbol) = boringOwners(sym.owner)
    def symString(sym: Symbol) = if (sym.isTerm) sym.defString else sym.toString

    def members(sym: Symbol) = phased(sym.info.members filterNot boringMember map symString)
    def decls(sym: Symbol)   = phased(sym.info.decls.toList map symString)
    def bases(sym: Symbol)   = phased(sym.info.baseClasses map (x => x.kindString + " " + x.fullName))

    // make the type/term selections walking from the root.
    val syms = findMemberFromRoot(fullName) match {
      // The name as given was not found, so we'll sift through every symbol in
      // the run looking for plausible matches.
      case NoSymbol => phased(currentRun.symSource.keys map (sym => findNamedMember(fullName, sym)) filterNot (_ == NoSymbol) toList)
      // The name as given matched, so show only that.
      case sym      => List(sym)
    }

    syms foreach { sym =>
      val name        = "\n<<-- %s %s after phase '%s' -->>".format(sym.kindString, sym.fullName, ph.name)
      val baseClasses = bases(sym).mkString("Base classes:\n  ", "\n  ", "")
      val contents =
        if (declsOnly) decls(sym).mkString("Declarations:\n  ", "\n  ", "")
        else members(sym).mkString("Members (excluding Any/AnyRef unless overridden):\n  ", "\n  ", "")

      inform(List(name, baseClasses, contents) mkString "\n\n")
    }
  }

  def createJavadoc    = false
}

object Global {
  def apply(settings: Settings, reporter: Reporter): Global = new Global(settings, reporter)

  def apply(settings: Settings): Global = new Global(settings, reporter(settings))

  private def reporter(settings: Settings): Reporter = {
    //val loader = ScalaClassLoader(getClass.getClassLoader)  // apply does not make delegate
    val loader = new ClassLoader(getClass.getClassLoader) with ScalaClassLoader
    loader.create[Reporter](settings.reporter.value, settings.errorFn)(settings)
  }
  private object InitPhase extends Phase(null) {
    def name = "<init phase>"
    override def keepsTypeParams = false
    def run(): Unit = { throw new Error("InitPhase.run") }
  }
}
