package scala.reflect.internal.util

import java.util.concurrent.atomic.AtomicInteger

object Parallel {

  class IndexedThread(val index: Int, group: ThreadGroup, target: Runnable, namePrefix: String)
    extends Thread(group, target, namePrefix + index, 0)

  var isParallel = false

  class AtomicCounter(initial: Int = 0) {
    private val count = new AtomicInteger
    count.set(initial)

    @inline final def get: Int = count.get()

    @inline final def reset(): Unit = {
      assertOnMain()
      count.set(0)
    }

    @inline final def incrementAndGet(): Int = count.incrementAndGet

    @inline final def getAndIncrement(): Int = count.getAndIncrement

    @inline final def set(v: Int): Int = count.getAndSet(v)

    @inline final override def toString: String = s"Counter[$count]"
  }

  object AtomicCounter {
    @inline final def apply(initial: Int = 0): AtomicCounter = new AtomicCounter(initial)
  }

  class ThreadLocalCounter(initial: Int = 0) {
    final val arr: Array[Int] = Array.fill[Int](1000)(initial)

    @inline def index = Thread.currentThread() match {
      case thread: IndexedThread => thread.index
      case _ => 0
    }

    @inline final def get: Int = arr(index)

    @inline final def reset(): Unit = arr(index) = 0

    @inline final def incrementAndGet(): Int = {
      val newValue = arr(index) + 1
      arr(index) = newValue
      newValue
    }

    @inline final def getAndIncrement(): Int = {
      val oldValue = arr(index)
      arr(index) = oldValue + 1
      oldValue
    }

    @inline final def set(v: Int): Unit = arr(index) = v

    @inline final override def toString: String = s"Counter[${arr(index)}]"
  }

  object ThreadLocalCounter {
    def apply(initial: Int = 0): ThreadLocalCounter = new ThreadLocalCounter(initial)
  }

  // Wrapper for `synchronized` method. In future could provide additional logging, safety checks, etc.
  @inline def synchronizeAccess[T <: Object, U](obj: T)(block: => U): U = {
    if (isParallel) obj.synchronized[U](block) else block
  }

  class Lock {
    @inline final def apply[T](op: => T) = synchronizeAccess(this)(op)
  }

  class AbstractThreadLocal[T](initial: => T, shouldFailOnMain: Boolean) {
    private[this] val worker: ThreadLocal[T] = new ThreadLocal[T] {
      override def initialValue(): T = initial
    }

    private[this] def throwNotAllowedOnMainException() = throw new IllegalStateException("not allowed on main thread")

    @inline final def get: T = {
      if (isParallel && shouldFailOnMain && !isWorker.get()) throwNotAllowedOnMainException()
      worker.get()
    }

    @inline final def set(value: T): Unit = {
      if (isParallel && shouldFailOnMain && !isWorker.get()) throwNotAllowedOnMainException()
      worker.set(value)
    }

    @inline final def reset(): Unit = {
      worker.remove()
    }
  }


  type WorkerThreadLocal[T] = AbstractThreadLocal[T]
  // `WorkerThreadLocal` detects reads/writes of given value on the main thread and
  // and report such violations by throwing exception.
  @inline final def WorkerThreadLocal[T](valueOnWorker: => T) = new AbstractThreadLocal(valueOnWorker, true)

  // `WorkerOrMainThreadLocal` allows us to have different type of values on main and worker threads.
  // It's useful in cases like reporter, when on workers we want to just store messages and on main we want to print them,
  type WorkerOrMainThreadLocal[T] = AbstractThreadLocal[T]
  @inline final def WorkerOrMainThreadLocal[T](valueOnWorker: => T) = new AbstractThreadLocal(valueOnWorker, false)

  // Asserts that current execution happens on the main thread
  @inline final def assertOnMain(): Unit = {
    if (ParallelSettings.areAssertionsEnabled && isParallel) assert(!isWorker.get())
  }

  // Asserts that current execution happens on the worker thread
  @inline final def assertOnWorker(): Unit = {
    if (ParallelSettings.areAssertionsEnabled && isParallel) assert(isWorker.get())
  }

  // Runs block of the code in the 'worker thread' mode
  // All unit processing should always happen in the worker thread
  @inline final def asWorkerThread[T](fn: => T): T = {
    val previous = isWorker.get()
    isWorker.set(true)
    try fn finally isWorker.set(previous)
  }

  // Runs block of the code in the 'main thread' mode.
  // In 'main' mode we mostly sets/resets global variables, initialize contexts,
  // and orchestrate processing of phases/units
  @inline final def asMainThread[T](fn: => T): T = {
    val previous = isWorker.get()
    isWorker.set(false)
    try fn finally isWorker.set(previous)
  }

  // ThreadLocal variable which allows us to mark current thread as main or worker.
  // This is important because real main thread is not necessarily always running 'main' code.
  // Good example may be tests which all runs in one main thread, although often processes units
  // (what conceptually should always happen in workers).
  // Because there is much more entry points to unit processing than to Global,
  // it's much easier to start with assuming everything is initially worker thread
  // and just mark main accordingly when needed.
  val isWorker: ThreadLocal[Boolean] = new ThreadLocal[Boolean] {
    override def initialValue(): Boolean = true
  }
}