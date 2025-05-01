package io.joern.kotlin2cpg.compiler

import io.joern.kotlin2cpg.Config
import io.joern.kotlin2cpg.ast.BindingContextUtils
import org.jetbrains.kotlin.cli.jvm.compiler.{KotlinCoreEnvironment, KotlinToJVMBytecodeCompiler}
import org.jetbrains.kotlin.resolve.{BindingContext, BindingTraceContext}
import org.slf4j.LoggerFactory

import java.util.concurrent.LinkedBlockingQueue
import scala.concurrent.duration.Duration
import scala.concurrent.{Await, Future}

class BindingContextAnalyserPass(environments: List[KotlinCoreEnvironment], config: Config) {
  private val logger                                   = LoggerFactory.getLogger(getClass)
  private val bindingTraceContext: BindingTraceContext = BindingTraceContext(true)

  def getBindingContext: BindingContext = bindingTraceContext.getBindingContext

  def apply(): BindingContext = {
    val writer       = Writer()
    val writerThread = new Thread(writer)
    writerThread.start()
    implicit val ec: scala.concurrent.ExecutionContext = scala.concurrent.ExecutionContext.global
    val futures: List[Future[Unit]] = environments.map { environment =>
      val future = Future {
        writer.queue.put(Some(createBindingContext(environment, config)))
      }
      future
    }
    Await.result(Future.sequence(futures), Duration.Inf)
    logger.debug("Adding None to queue")
    writer.queue.put(None)
    if writer.raisedException != null then
      logger.error("WriterThread raised exception", writer.raisedException)
      throw writer.raisedException
    getBindingContext
  }

  private def createBindingContext(environment: KotlinCoreEnvironment, config: Config): BindingContext = {
    try {
      if (!config.resolveTypes) {
        logger.info("Skipped Running Kotlin compiler analysis... due to no resolve types flag")
        BindingContext.EMPTY
      } else {
        logger.info("Running Kotlin compiler analysis...")
        val t0             = System.nanoTime()
        val analysisResult = KotlinToJVMBytecodeCompiler.INSTANCE.analyze(environment)
        val t1             = System.nanoTime()
        logger.info(s"Kotlin compiler analysis finished in `${(t1 - t0) / 1000000}` ms.")
        analysisResult.getBindingContext
      }

    } catch {
      case exc: Exception =>
        logger.error(s"Kotlin compiler analysis failed with exception `${exc.toString}`:`${exc.getMessage}`.", exc)
        BindingContext.EMPTY
    }
  }
  private class Writer() extends Runnable {
    val queue                                = LinkedBlockingQueue[Option[BindingContext]]()
    @volatile var raisedException: Throwable = null
    override def run(): Unit = {
      try {
        var terminate  = false
        var index: Int = 0
        while (!terminate) {
          queue.take() match {
            case None =>
              logger.debug("Shutting down WriterThread")
              terminate = true
            case Some(bindingContext: BindingContext) =>
              logger.info("Processing BindingContext")
              bindingContext.addOwnDataTo(bindingTraceContext, true)
          }
        }
      } catch {
        case exception: InterruptedException => logger.warn("Interrupted WriterThread", exception)
        case exc: Throwable =>
          logger.info("WriterThread raised exception", exc)
          raisedException = exc
          queue.clear()
      }
    }
  }
}
