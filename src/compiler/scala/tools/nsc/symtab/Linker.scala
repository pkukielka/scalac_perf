package scala.tools.nsc

import scala.collection.mutable
import scala.reflect.internal.pickling.PickleBuffer
import scala.tools.linker._


/**
  * Create Linker information for the compiled code
  * The linker info contains the minimal data set to compile a dependent module
  *
  * @author Mike Skells
  * @author Rory Graves
  * @version 1.0
  */
abstract class Linker  extends SubComponent {

  import global._

  val phaseName = "linker"

  def newPhase(prev: Phase): Phase = new LinkerPhase(prev)

  class LinkerPhase(prev: Phase) extends global.GlobalPhase(prev) {
    def name = phaseName

    override def run(): Unit = {
      val enabled: Boolean = global.settings.linker
      if (global.settings.debug) inform("[phase " + name + " - enabled: " + enabled + "]")
      if (enabled) {
        val visited = new mutable.HashSet[Symbol]()
        //TODO linkerData should b synced with current classes for incremental compilation
        val linkerData = new RootLinkerSymbolWriter
        currentRun.symData foreach {
          //only process elements and companion pairs once
          case (sym, pickleBuffer) => if (sym != NoSymbol && visited.add(sym)) {
            debuglog(s"Linker processing $sym $pickleBuffer")
            debuglog(s"Linker companion ${sym.companion}")
            if (sym.companion != NoSymbol && currentRun.symData.contains(sym.companion)) {
              assert(currentRun.symData(sym.companion) eq pickleBuffer,
                s"""${sym.companion}
                   |currentRun.symData(sym.companion) = ${currentRun.symData(sym.companion)}
                   |pickleBuffer =                      $pickleBuffer""".stripMargin)
              visited.add(sym.companion)
              debuglog(s"Linker companion -- processed")
            }
            //not sure if this is right - do we want the BinaryName or the Class name etc
            val binaryClassName = sym match {
              case c: ClassSymbol => sym.javaBinaryNameString
              case m: ModuleSymbol if currentRun.symData.contains(sym.companion) => sym.companion.javaBinaryNameString
              case m: ModuleSymbol => sym.javaBinaryNameString
            }

            linkerData.addClassRef(ScalaLinkerClassInfo(binaryClassName, pickleBuffer))
          }
        }

        //TODO : consider use a Future for all of this method?
        currentRun.linkerData = Some(linkerData)
      }
    }

    //apply should not be called
    def apply(unit: CompilationUnit): Unit = ???

  }

  def getFiles : Map[String,Array[Byte]] = {
    currentRun.linkerData match {
      case None =>
        assert(!global.settings.linker)
        Map.empty
      case Some(linkerData) =>
        Map(
          LinkerSymbols.fileName(true) -> linkerData.toBytes(true),
          LinkerSymbols.fileName(false)-> linkerData.toBytes(false)
        )
    }
  }
}
  object ScalaLinkerClassInfo {
    def apply(binaryClassName:String, pickleBuffer:PickleBuffer): ScalaLinkerClassInfo = {
      val signature = ScalaClassSignature(pickleBuffer)
      new ScalaLinkerClassInfo(binaryClassName, signature)
    }
  }
  case class ScalaLinkerClassInfo(binaryName:String, sig:ScalaClassSignature) extends ClassInfo {
    override def javaClassName: String = binaryName.replace('/','.')

    override def internalClassName: String = binaryName

    override def outerJavaClassName: Option[String] = None

    override def scalaSignature: Option[ScalaClassSignature] = Some(sig)

    override def entryName: String = binaryName + ".class"
  }


