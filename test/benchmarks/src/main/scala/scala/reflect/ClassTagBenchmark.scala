package scala.reflect

import java.util.concurrent.TimeUnit

import org.openjdk.jmh.annotations._
import org.openjdk.jmh.infra.Blackhole


@BenchmarkMode(Array(Mode.AverageTime))
@Fork(2)
@Threads(1)
@Warmup(iterations = 10)
@Measurement(iterations = 10)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@State(Scope.Benchmark)
class ClassTagBenchmark {
  @OperationsPerInvocation(10000000)
  @Benchmark def doubleClassTagUnapply(bh: Blackhole): Any = {
    val ct = classTag[Double]
    for (1 <- 1 to 10000000) {
      bh.consume(ct.unapply(1.0))
    }
//    for (1 <- 1 to 10000000) {
//      ct match {
//        case DoubleManifest(x) => bh.consume(x)
//        case x => bh.consume(x)
//      }
//    }
  }
}
