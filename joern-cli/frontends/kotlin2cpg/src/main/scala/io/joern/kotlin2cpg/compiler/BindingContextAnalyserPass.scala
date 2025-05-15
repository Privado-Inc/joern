package io.joern.kotlin2cpg.compiler

import io.joern.kotlin2cpg.Config
import org.jetbrains.kotlin.cli.jvm.compiler.{
  KotlinCoreEnvironment,
  KotlinToJVMBytecodeCompiler,
  NoScopeRecordCliBindingTrace
}
import org.jetbrains.kotlin.com.intellij.util.keyFMap.KeyFMap
import org.jetbrains.kotlin.resolve.{BindingContext, BindingTraceContext}
import org.jetbrains.kotlin.util.slicedMap.WritableSlice
import org.slf4j.LoggerFactory

import java.util.concurrent.LinkedBlockingQueue
import scala.concurrent.duration.Duration
import scala.concurrent.{Await, Future}

class BindingContextAnalyserPass(environments: List[KotlinCoreEnvironment], config: Config) {
  private val logger                                   = LoggerFactory.getLogger(getClass)
  private val bindingTraceContext: BindingTraceContext = BindingTraceContext(true)
  private var originalBindingContext: BindingContext   = null

  def getBindingContext: BindingContext = {
    val bindingContext = bindingTraceContext.getBindingContext
    println("before merging bindingContext")
    printBindingContextData(originalBindingContext)

    println("final bindingContext")
    printBindingContextData(bindingContext)
    bindingContext
  }

  def printBindingContextData(bindingContext: BindingContext): Unit = {
    try {
      if (bindingContext != null) {
        val thisField = bindingContext.getClass.getDeclaredField("this$0")
        thisField.setAccessible(true)
        val (bindingTrace, mapField) = thisField.get(bindingContext) match {
          case bindingTrace: NoScopeRecordCliBindingTrace =>
            (bindingTrace, bindingTrace.getClass.getSuperclass.getSuperclass.getDeclaredField("map"))
          case bindingTrace: BindingTraceContext =>
            (bindingTrace, bindingTrace.getClass.getDeclaredField("map"))
        }
        mapField.setAccessible(true)
        val map = mapField.get(bindingTrace)

        val mapMapField = map.getClass.getDeclaredField("map")
        mapMapField.setAccessible(true)
        val mapfinalFiled = mapMapField.get(map).asInstanceOf[java.util.Map[Object, KeyFMap]]
        if (mapfinalFiled == null) {
          println("Map is null")
        } else {
          println(s"Map size: ${mapfinalFiled.size()}")
        }
        val collectiveSliceKeysField = map.getClass.getDeclaredField("collectiveSliceKeys")
        collectiveSliceKeysField.setAccessible(true)
        collectiveSliceKeysField
          .get(map) match {
          case field: com.google.common.collect.ArrayListMultimap[WritableSlice[Any, Any], Object] =>
            if (field == null) {
              println("com.google.common.collect.ArrayListMultimap[WritableSlice[Any, Any], Object] is null")
            } else {
              println(
                s"com.google.common.collect.ArrayListMultimap[WritableSlice[Any, Any], Object] size: ${field.size()}"
              )
            }
          case field: org.jetbrains.kotlin.com.google.common.collect.ArrayListMultimap[WritableSlice[
                Any,
                Any
              ], Object] =>
            if (field == null) {
              println(
                "org.jetbrains.kotlin.com.google.common.collect.ArrayListMultimap[WritableSlice[Any, Any], Object] is null"
              )
            } else {
              println(
                s"org.jetbrains.kotlin.com.google.common.collect.ArrayListMultimap[WritableSlice[Any, Any], Object] size: ${field.size()}"
              )
            }
          case _ =>
            println("collectiveSliceKeysField Unknown type")
        }
      }
    } catch {
      case to: Throwable =>
        to.printStackTrace()
    }
  }

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
              originalBindingContext = bindingContext
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
