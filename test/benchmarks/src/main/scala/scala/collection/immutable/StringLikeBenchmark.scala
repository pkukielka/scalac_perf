package scala.collection.immutable

import java.util.concurrent.TimeUnit

import org.openjdk.jmh.annotations._

import scala.collection.{AbstractSeq, mutable}
import scala.collection.mutable.StringBuilder
import scala.util.Random

@BenchmarkMode(Array(Mode.AverageTime))
@Fork(2)
@Threads(1)
@Warmup(iterations = 10)
@Measurement(iterations = 20)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@State(Scope.Benchmark)
class StringLikeBenchmark {
  @Param(Array("0", "1", "10", "100", "1000"))
  var size: Int = _

  var prefix: String = _
  var suffix: String = _

  // Used when we want to test stripPrefix etc with a string which is definately not present
  var impossibleString: String = _

  var testObject: StringBuilder = _

  @Setup(Level.Trial) def initKeys(): Unit = {
    val randomChars = (0 to size) map (_ => Random.nextPrintableChar())

    testObject = StringBuilder.newBuilder.appendAll(randomChars)

    val str = testObject.toString
    val prefixAndSuffixLength = size / 10
    prefix = str.substring(0, prefixAndSuffixLength)
    suffix = str.reverse.substring(0, prefixAndSuffixLength)

    impossibleString = str * 2

  }

  @Benchmark def linesWithSeparators: Any = {
    testObject.linesWithSeparators
  }

  @Benchmark def lines: Any = {
    testObject.lines
  }

  @Benchmark def capitalize: Any = {
    testObject.capitalize
  }

  @Benchmark def stripPrefix_present: Any = {
    testObject.stripPrefix(prefix)
  }

  @Benchmark def stripPrefix_notPresent: Any = {
    testObject.stripPrefix(impossibleString)
  }

  @Benchmark def stripSuffix_present: Any = {
    testObject.stripSuffix(prefix)
  }

  @Benchmark def stripSuffix_notPresent: Any = {
    testObject.stripSuffix(impossibleString)
  }

  @Benchmark def replaceAllLiterally: Any = {
    testObject.replaceAllLiterally("A", "B")
  }

  @Benchmark def stripMargin: Any = {
    testObject.stripMargin
  }

  @Benchmark def split: Any = {
    testObject.split('A')
  }
}
