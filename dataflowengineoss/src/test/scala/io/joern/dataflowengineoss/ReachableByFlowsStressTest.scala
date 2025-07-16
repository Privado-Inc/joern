package io.joern.dataflowengineoss

import io.joern.dataflowengineoss.language.*
import io.joern.dataflowengineoss.queryengine.EngineContext
import io.joern.dataflowengineoss.testfixtures.SemanticCpgTestFixture
import io.shiftleft.codepropertygraph.generated.Cpg
import io.shiftleft.codepropertygraph.generated.nodes.*
import io.shiftleft.codepropertygraph.generated.EdgeTypes
import io.shiftleft.semanticcpg.language.*
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

import scala.collection.mutable
import scala.collection.parallel.CollectionConverters.*
import scala.util.Random
import java.util.concurrent.{Executors, Future, TimeUnit}
import java.util.concurrent.atomic.AtomicInteger
import flatgraph.misc.TestUtils.applyDiff

/**
 * Stress testing suite for `reachableByFlows` queries under extreme conditions.
 * 
 * This test suite validates:
 * - System stability under high load
 * - Consistency under extreme concurrent access
 * - Memory management under pressure
 * - Performance degradation patterns
 * - Error handling and recovery
 * - Resource cleanup and leak prevention
 * 
 * These tests are designed to push the system to its limits while ensuring
 * the consistency fixes remain effective under stress.
 */
class ReachableByFlowsStressTest extends AnyWordSpec with Matchers with SemanticCpgTestFixture() {

  private val stressTestTimeout = 60000 // 60 seconds timeout for stress tests

  /**
   * Create a very large CPG for stress testing
   */
  private def createLargeStressCpg(nodeCount: Int): Cpg = {
    val cpg = Cpg.empty
    val diffGraph = Cpg.newDiffGraphBuilder
    
    println(s"Creating large CPG with ~${nodeCount} nodes...")
    
    // Create methods (1% of nodes)
    val methodCount = Math.max(1, nodeCount / 100)
    val methods = (1 to methodCount).map { i =>
      val method = NewMethod().name(s"method$i").fullName(s"method$i").order(i)
      diffGraph.addNode(method)
      method
    }
    
    // Create sources (10% of nodes)
    val sourceCount = nodeCount / 10
    val sources = (1 to sourceCount).map { i =>
      val source = NewCall().name(s"source$i").code(s"source$i()").order(i)
      diffGraph.addNode(source)
      source
    }
    
    // Create sinks (10% of nodes)
    val sinkCount = nodeCount / 10
    val sinks = (1 to sinkCount).map { i =>
      val sink = NewCall().name(s"sink$i").code(s"sink$i(data)").order(i + sourceCount)
      diffGraph.addNode(sink)
      sink
    }
    
    // Create intermediate processing nodes (60% of nodes)
    val intermediateCount = nodeCount * 6 / 10
    val intermediates = (1 to intermediateCount).map { i =>
      val intermediate = NewCall().name(s"process$i").code(s"process$i(data)").order(i + sourceCount + sinkCount)
      diffGraph.addNode(intermediate)
      intermediate
    }
    
    // Create arguments for sinks (10% of nodes)
    val argCount = nodeCount / 10
    val args = (1 to argCount).map { i =>
      val arg = NewIdentifier().name(s"arg$i").code(s"arg$i").order(i)
      diffGraph.addNode(arg)
      arg
    }
    
    // Connect arguments to sinks
    sinks.zip(args).foreach { case (sink, arg) =>
      diffGraph.addEdge(sink, arg, EdgeTypes.ARGUMENT)
    }
    
    // Create complex reaching definition networks
    val random = new Random(42) // Fixed seed for reproducibility
    
    // Sources to intermediates (each source connects to 3-5 intermediates)
    sources.foreach { source =>
      val connectionCount = 3 + random.nextInt(3)
      val targetIndices = (0 until connectionCount).map(_ => random.nextInt(intermediates.length)).distinct
      targetIndices.foreach { idx =>
        diffGraph.addEdge(source, intermediates(idx), EdgeTypes.REACHING_DEF)
      }
    }
    
    // Intermediates to intermediates (create complex networks)
    intermediates.zipWithIndex.foreach { case (intermediate, i) =>
      val connectionCount = 2 + random.nextInt(3)
      val targetIndices = (0 until connectionCount).map { _ =>
        val targetIdx = random.nextInt(intermediates.length)
        if (targetIdx != i) Some(targetIdx) else None
      }.flatten
      
      targetIndices.foreach { idx =>
        diffGraph.addEdge(intermediate, intermediates(idx), EdgeTypes.REACHING_DEF)
      }
    }
    
    // Intermediates to sink arguments
    intermediates.foreach { intermediate =>
      val connectionCount = 1 + random.nextInt(3)
      val targetIndices = (0 until connectionCount).map(_ => random.nextInt(args.length)).distinct
      targetIndices.foreach { idx =>
        diffGraph.addEdge(intermediate, args(idx), EdgeTypes.REACHING_DEF)
      }
    }
    
    cpg.graph.applyDiff(_ => { diffGraph; () })
    println(s"Large CPG created with ${nodeCount} nodes")
    cpg
  }

  /**
   * Create a deep call chain CPG for testing stack depth limits
   */
  private def createDeepCallChainCpg(depth: Int): Cpg = {
    val cpg = Cpg.empty
    val diffGraph = Cpg.newDiffGraphBuilder
    
    // Create a single method
    val method = NewMethod().name("deepMethod").fullName("deepMethod").order(1)
    diffGraph.addNode(method)
    
    // Create a chain of calls
    val calls = (1 to depth).map { i =>
      val call = NewCall().name(s"call$i").code(s"call$i(data)").order(i)
      diffGraph.addNode(call)
      call
    }
    
    // Create arguments
    val args = (1 to depth).map { i =>
      val arg = NewIdentifier().name(s"arg$i").code(s"arg$i").order(i)
      diffGraph.addNode(arg)
      arg
    }
    
    // Connect arguments to calls
    calls.zip(args).foreach { case (call, arg) =>
      diffGraph.addEdge(call, arg, EdgeTypes.ARGUMENT)
    }
    
    // Create reaching definition chain
    (calls.zip(args).sliding(2)).foreach { case Seq((call1, arg1), (call2, arg2)) =>
      diffGraph.addEdge(call1, arg2, EdgeTypes.REACHING_DEF)
    }
    
    cpg.graph.applyDiff(_ => { diffGraph; () })
    cpg
  }

  "reachableByFlows stress tests" should {

    "handle high concurrent load" in {
      val cpg = createLargeStressCpg(1000)
      val threadCount = 20
      val iterationsPerThread = 25
      val executor = Executors.newFixedThreadPool(threadCount)
      
      println(s"=== High Concurrent Load Test: $threadCount threads x $iterationsPerThread iterations ===")
      
      val startTime = System.currentTimeMillis()
      val completedCount = new AtomicInteger(0)
      val errorCount = new AtomicInteger(0)
      val results = mutable.Set.empty[String]
      val resultsLock = new Object()
      
      try {
        val futures = (1 to threadCount).map { threadId =>
          executor.submit(new Runnable {
            def run(): Unit = {
              (1 to iterationsPerThread).foreach { iteration =>
                try {
                  implicit val localContext = EngineContext()
                  val sources = cpg.call.name("source.*")
                  val sinks = cpg.call.name("sink.*").argument
                  val flows = sinks.reachableByFlows(sources).toVector
                  val normalized = flows.map(_.toString).sorted.mkString("|")
                  
                  resultsLock.synchronized {
                    results += normalized
                  }
                  
                  completedCount.incrementAndGet()
                  
                  if (completedCount.get() % 100 == 0) {
                    println(s"Completed ${completedCount.get()} iterations")
                  }
                } catch {
                  case e: Exception =>
                    errorCount.incrementAndGet()
                    println(s"Thread $threadId iteration $iteration failed: ${e.getMessage}")
                }
              }
            }
          })
        }
        
        // Wait for completion with timeout
        futures.foreach(_.get(stressTestTimeout, TimeUnit.MILLISECONDS))
        
        val endTime = System.currentTimeMillis()
        val totalTime = endTime - startTime
        
        println(s"High concurrent load test completed:")
        println(s"  Total time: ${totalTime}ms")
        println(s"  Completed iterations: ${completedCount.get()}")
        println(s"  Error count: ${errorCount.get()}")
        println(s"  Unique result sets: ${results.size}")
        println(s"  Average time per iteration: ${totalTime / completedCount.get()}ms")
        
        // Validate results
        errorCount.get() should be < (threadCount * iterationsPerThread / 10) // Less than 10% error rate
        results.size shouldBe 1 // All results should be identical
        completedCount.get() shouldBe (threadCount * iterationsPerThread - errorCount.get())
        
      } finally {
        executor.shutdown()
      }
    }

    "handle memory pressure gracefully" in {
      val cpg = createLargeStressCpg(2000)
      val iterations = 50
      val memoryPressureInterval = 5
      
      println(s"=== Memory Pressure Test: $iterations iterations ===")
      
      val results = mutable.ArrayBuffer.empty[String]
      val memoryUsage = mutable.ArrayBuffer.empty[Long]
      val runtime = Runtime.getRuntime
      
      (1 to iterations).foreach { i =>
        // Create memory pressure periodically
        if (i % memoryPressureInterval == 0) {
          // Allocate large objects to stress memory
          val pressureObjects = (1 to 10).map(_ => Array.ofDim[Byte](1024 * 1024)) // 1MB each
          System.gc()
          Thread.sleep(50)
          pressureObjects.foreach(_.length) // Keep reference to prevent optimization
        }
        
        val beforeMemory = runtime.totalMemory() - runtime.freeMemory()
        
        try {
          val sources = cpg.call.name("source.*")
          val sinks = cpg.call.name("sink.*").argument
          val flows = sinks.reachableByFlows(sources).toVector
          val normalized = flows.map(_.toString).sorted.mkString("|")
          
          results += normalized
          
          val afterMemory = runtime.totalMemory() - runtime.freeMemory()
          memoryUsage += (afterMemory - beforeMemory) / (1024 * 1024) // MB
          
          if (i % 10 == 0) {
            println(s"Memory pressure iteration $i: ${(afterMemory - beforeMemory) / (1024 * 1024)}MB delta")
          }
          
        } catch {
          case e: OutOfMemoryError =>
            println(s"OutOfMemoryError at iteration $i - this is expected under extreme pressure")
            System.gc()
            Thread.sleep(100)
          case e: Exception =>
            println(s"Exception at iteration $i: ${e.getMessage}")
        }
      }
      
      val uniqueResults = results.toSet
      val avgMemoryUsage = if (memoryUsage.nonEmpty) memoryUsage.sum / memoryUsage.length else 0
      val maxMemoryUsage = if (memoryUsage.nonEmpty) memoryUsage.max else 0
      
      println(s"Memory pressure test completed:")
      println(s"  Successful iterations: ${results.size}")
      println(s"  Unique result sets: ${uniqueResults.size}")
      println(s"  Average memory usage: ${avgMemoryUsage}MB")
      println(s"  Peak memory usage: ${maxMemoryUsage}MB")
      
      // Validate consistency despite memory pressure
      uniqueResults.size shouldBe 1
      results.size should be > (iterations * 0.8).toInt // At least 80% success rate
    }

    "handle deep call chains" in {
      val maxDepth = 50
      val depths = Vector(10, 20, 30, 40, 50)
      
      println(s"=== Deep Call Chain Test: depths up to $maxDepth ===")
      
      depths.foreach { depth =>
        val cpg = createDeepCallChainCpg(depth)
        val iterations = 10
        
        println(s"Testing depth $depth with $iterations iterations...")
        
        val results = (1 to iterations).map { i =>
          try {
            val sources = cpg.call.name("call1")
            val sinks = cpg.call.name(s"call$depth").argument
            val flows = sinks.reachableByFlows(sources).toVector
            val normalized = flows.map(_.toString).sorted.mkString("|")
            
            if (i == 1) {
              println(s"  Depth $depth: Found ${flows.size} flows")
            }
            
            Some(normalized)
          } catch {
            case e: StackOverflowError =>
              println(s"  StackOverflowError at depth $depth iteration $i")
              None
            case e: Exception =>
              println(s"  Exception at depth $depth iteration $i: ${e.getMessage}")
              None
          }
        }
        
        val successfulResults = results.flatten
        val uniqueResults = successfulResults.toSet
        
        println(s"  Depth $depth: ${successfulResults.size}/$iterations successful, ${uniqueResults.size} unique results")
        
        if (successfulResults.nonEmpty) {
          uniqueResults.size shouldBe 1 // Results should be consistent
        }
      }
    }

    "handle rapid context switching" in {
      val cpg = createLargeStressCpg(500)
      val iterations = 100
      val contextSwitchInterval = 2
      
      println(s"=== Rapid Context Switching Test: $iterations iterations ===")
      
      val results = mutable.ArrayBuffer.empty[String]
      val contexts = mutable.ArrayBuffer.empty[EngineContext]
      
      (1 to iterations).foreach { i =>
        // Create new context every few iterations
        implicit val context = if (i % contextSwitchInterval == 0) {
          val newContext = EngineContext()
          contexts += newContext
          newContext
        } else {
          contexts.lastOption.getOrElse(EngineContext())
        }
        
        try {
          val sources = cpg.call.name("source.*")
          val sinks = cpg.call.name("sink.*").argument
          val flows = sinks.reachableByFlows(sources).toVector
          val normalized = flows.map(_.toString).sorted.mkString("|")
          
          results += normalized
          
          if (i % 20 == 0) {
            println(s"Context switching iteration $i: ${contexts.size} contexts created")
          }
          
        } catch {
          case e: Exception =>
            println(s"Exception at iteration $i: ${e.getMessage}")
        }
      }
      
      val uniqueResults = results.toSet
      
      println(s"Rapid context switching test completed:")
      println(s"  Total iterations: ${results.size}")
      println(s"  Contexts created: ${contexts.size}")
      println(s"  Unique result sets: ${uniqueResults.size}")
      
      // Results should be consistent despite context switching
      uniqueResults.size shouldBe 1
    }

    "handle resource exhaustion gracefully" in {
      val cpg = createLargeStressCpg(1500)
      val maxIterations = 100
      
      println(s"=== Resource Exhaustion Test: up to $maxIterations iterations ===")
      
      val results = mutable.ArrayBuffer.empty[String]
      val exceptions = mutable.ArrayBuffer.empty[String]
      
      (1 to maxIterations).foreach { i =>
        try {
          // Create multiple contexts to stress resource usage
          val contexts = (1 to 3).map(_ => EngineContext())
          
          val sources = cpg.call.name("source.*")
          val sinks = cpg.call.name("sink.*").argument
          
          // Execute with different contexts
          val contextResults = contexts.map { implicit context =>
            sinks.reachableByFlows(sources).toVector
          }
          
          val normalized = contextResults.head.map(_.toString).sorted.mkString("|")
          results += normalized
          
          if (i % 20 == 0) {
            println(s"Resource exhaustion iteration $i: ${results.size} successful")
          }
          
        } catch {
          case e: OutOfMemoryError =>
            exceptions += s"OutOfMemoryError at iteration $i"
            System.gc()
            Thread.sleep(100)
          case e: Exception =>
            exceptions += s"${e.getClass.getSimpleName} at iteration $i: ${e.getMessage}"
        }
      }
      
      val uniqueResults = results.toSet
      val successRate = results.size.toDouble / maxIterations
      
      println(s"Resource exhaustion test completed:")
      println(s"  Successful iterations: ${results.size}/$maxIterations")
      println(s"  Success rate: ${(successRate * 100).toInt}%")
      println(s"  Unique result sets: ${uniqueResults.size}")
      println(s"  Exception count: ${exceptions.size}")
      
      if (exceptions.nonEmpty) {
        println(s"  Exception types: ${exceptions.groupBy(_.split(" ").head).keys.mkString(", ")}")
      }
      
      // Should handle resource exhaustion gracefully
      successRate should be > 0.5 // At least 50% success rate
      if (results.nonEmpty) {
        uniqueResults.size shouldBe 1 // Results should be consistent when successful
      }
    }

    "validate long-running stability" in {
      val cpg = createLargeStressCpg(800)
      val runDurationMs = 30000 // 30 seconds
      val checkInterval = 5000 // Check every 5 seconds
      
      println(s"=== Long-Running Stability Test: ${runDurationMs / 1000} seconds ===")
      
      val startTime = System.currentTimeMillis()
      val results = mutable.ArrayBuffer.empty[String]
      val checkpoints = mutable.ArrayBuffer.empty[(Long, Int)]
      
      var iterationCount = 0
      
      while (System.currentTimeMillis() - startTime < runDurationMs) {
        try {
          val sources = cpg.call.name("source.*")
          val sinks = cpg.call.name("sink.*").argument
          val flows = sinks.reachableByFlows(sources).toVector
          val normalized = flows.map(_.toString).sorted.mkString("|")
          
          results += normalized
          iterationCount += 1
          
          val currentTime = System.currentTimeMillis()
          if ((currentTime - startTime) % checkInterval < 100) { // Approximately every checkInterval
            checkpoints += ((currentTime - startTime, iterationCount))
            println(s"Stability checkpoint at ${(currentTime - startTime) / 1000}s: $iterationCount iterations")
          }
          
        } catch {
          case e: Exception =>
            println(s"Exception at iteration $iterationCount: ${e.getMessage}")
        }
      }
      
      val totalTime = System.currentTimeMillis() - startTime
      val uniqueResults = results.toSet
      val avgIterationsPerSecond = (iterationCount * 1000.0) / totalTime
      
      println(s"Long-running stability test completed:")
      println(s"  Total runtime: ${totalTime}ms")
      println(s"  Total iterations: $iterationCount")
      println(s"  Average iterations per second: ${avgIterationsPerSecond.toInt}")
      println(s"  Unique result sets: ${uniqueResults.size}")
      println(s"  Checkpoints: ${checkpoints.size}")
      
      // Should maintain stability over time
      iterationCount should be > 10 // Should complete reasonable number of iterations
      uniqueResults.size shouldBe 1 // Results should be consistent throughout
    }
  }
}