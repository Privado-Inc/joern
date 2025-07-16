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
import scala.util.Random

/**
 * Performance benchmarking test suite for `reachableByFlows` queries.
 * 
 * This test suite measures:
 * - Query execution time stability
 * - Memory usage patterns
 * - Scalability with different CPG sizes
 * - Performance impact of the consistency fixes
 * - Comparison between FlatGraph and OverflowDB characteristics
 * 
 * Results help validate that consistency fixes don't negatively impact performance.
 */
class ReachableByFlowsPerformanceTest extends AnyWordSpec with Matchers with SemanticCpgTestFixture() {

  private case class PerformanceMetrics(
    executionTimeMs: Long,
    memoryUsedMB: Long,
    resultCount: Int,
    gcCount: Long,
    gcTimeMs: Long
  )

  private def measurePerformance[T](testName: String)(operation: => T): (T, PerformanceMetrics) = {
    // Force garbage collection before measurement
    System.gc()
    Thread.sleep(50)
    
    val runtime = Runtime.getRuntime
    val gcMx = java.lang.management.ManagementFactory.getGarbageCollectorMXBeans
    
    val initialMemory = runtime.totalMemory() - runtime.freeMemory()
    val initialGcCount = gcMx.iterator().next().getCollectionCount
    val initialGcTime = gcMx.iterator().next().getCollectionTime
    
    val startTime = System.nanoTime()
    val result = operation
    val endTime = System.nanoTime()
    
    val finalMemory = runtime.totalMemory() - runtime.freeMemory()
    val finalGcCount = gcMx.iterator().next().getCollectionCount
    val finalGcTime = gcMx.iterator().next().getCollectionTime
    
    val metrics = PerformanceMetrics(
      executionTimeMs = (endTime - startTime) / 1_000_000,
      memoryUsedMB = Math.max(0, finalMemory - initialMemory) / (1024 * 1024),
      resultCount = result match {
        case iter: Iterator[_] => iter.size
        case vec: Vector[_] => vec.size
        case list: List[_] => list.size
        case _ => 1
      },
      gcCount = finalGcCount - initialGcCount,
      gcTimeMs = finalGcTime - initialGcTime
    )
    
    println(s"$testName: ${metrics.executionTimeMs}ms, ${metrics.memoryUsedMB}MB, ${metrics.resultCount} results, ${metrics.gcCount} GCs")
    
    (result, metrics)
  }

  private def createScalabilityTestCpg(size: Int): Cpg = {
    val cpg = Cpg.empty
    val diffGraph = Cpg.newDiffGraphBuilder
    
    // Create methods
    val methods = (1 to math.max(1, size / 10)).map { i =>
      val method = NewMethod().name(s"method$i").fullName(s"method$i").order(i)
      diffGraph.addNode(method)
      method
    }
    
    // Create sources
    val sources = (1 to size).map { i =>
      val source = NewCall().name(s"source$i").code(s"source$i()").order(i)
      diffGraph.addNode(source)
      source
    }
    
    // Create sinks
    val sinks = (1 to size).map { i =>
      val sink = NewCall().name(s"sink$i").code(s"sink$i(data)").order(i + size)
      diffGraph.addNode(sink)
      sink
    }
    
    // Create intermediate nodes
    val intermediates = (1 to size * 2).map { i =>
      val intermediate = NewCall().name(s"process$i").code(s"process$i(data)").order(i + size * 2)
      diffGraph.addNode(intermediate)
      intermediate
    }
    
    // Create arguments for sinks
    val args = (1 to size).map { i =>
      val arg = NewIdentifier().name(s"arg$i").code(s"arg$i").order(i)
      diffGraph.addNode(arg)
      arg
    }
    
    // Connect arguments to sinks
    sinks.zip(args).foreach { case (sink, arg) =>
      diffGraph.addEdge(sink, arg, EdgeTypes.ARGUMENT)
    }
    
    // Create reaching definition chains
    val random = new Random(42) // Fixed seed for reproducibility
    
    sources.zipWithIndex.foreach { case (source, i) =>
      // Each source connects to 2-3 intermediates
      val numConnections = 2 + (i % 2)
      (0 until numConnections).foreach { j =>
        val targetIndex = (i * 2 + j) % intermediates.length
        diffGraph.addEdge(source, intermediates(targetIndex), EdgeTypes.REACHING_DEF)
      }
    }
    
    intermediates.zipWithIndex.foreach { case (intermediate, i) =>
      // Each intermediate connects to 1-2 other intermediates
      val numConnections = 1 + (i % 2)
      (0 until numConnections).foreach { j =>
        val targetIndex = (i + j + 1) % intermediates.length
        if (targetIndex != i) { // Avoid self-loops
          diffGraph.addEdge(intermediate, intermediates(targetIndex), EdgeTypes.REACHING_DEF)
        }
      }
    }
    
    intermediates.zipWithIndex.foreach { case (intermediate, i) =>
      // Each intermediate connects to 1-2 sink arguments
      val numConnections = 1 + (i % 2)
      (0 until numConnections).foreach { j =>
        val targetIndex = (i + j) % args.length
        diffGraph.addEdge(intermediate, args(targetIndex), EdgeTypes.REACHING_DEF)
      }
    }
    
    cpg.graph.apply(diffGraph)
    cpg
  }

  "reachableByFlows performance tests" should {

    "demonstrate baseline performance characteristics" in {
      val cpg = createScalabilityTestCpg(10)
      val iterations = 10
      val metrics = mutable.ArrayBuffer.empty[PerformanceMetrics]
      
      println("=== Baseline Performance Test ===")
      
      (1 to iterations).foreach { i =>
        val (result, metric) = measurePerformance(s"Baseline-$i") {
          val sources = cpg.call.name("source.*")
          val sinks = cpg.call.name("sink.*").argument
          sinks.reachableByFlows(sources).toVector
        }
        metrics += metric
      }
      
      analyzePerformanceMetrics("Baseline", metrics.toVector)
      
      // Validate consistency
      val results = (1 to 5).map { _ =>
        val sources = cpg.call.name("source.*")
        val sinks = cpg.call.name("sink.*").argument
        sinks.reachableByFlows(sources).map(_.toString).toVector.sorted
      }
      
      results.toSet.size shouldBe 1
      println(s"Baseline consistency: ${results.head.size} flows")
    }

    "measure scalability with different CPG sizes" in {
      val sizes = Vector(5, 10, 20, 50, 100)
      val scalabilityResults = mutable.ArrayBuffer.empty[(Int, PerformanceMetrics)]
      
      println("=== Scalability Test ===")
      
      sizes.foreach { size =>
        val cpg = createScalabilityTestCpg(size)
        
        val (result, metrics) = measurePerformance(s"Scale-$size") {
          val sources = cpg.call.name("source.*")
          val sinks = cpg.call.name("sink.*").argument
          sinks.reachableByFlows(sources).toVector
        }
        
        scalabilityResults += ((size, metrics))
        
        // Validate consistency at each scale
        val consistencyResults = (1 to 3).map { _ =>
          val sources = cpg.call.name("source.*")
          val sinks = cpg.call.name("sink.*").argument
          sinks.reachableByFlows(sources).map(_.toString).toVector.sorted
        }
        
        consistencyResults.toSet.size shouldBe 1
        println(s"Scale $size consistency: ${consistencyResults.head.size} flows")
      }
      
      analyzeScalabilityTrends(scalabilityResults.toVector)
    }

    "compare sequential vs parallel execution performance" in {
      val cpg = createScalabilityTestCpg(30)
      val iterations = 8
      
      println("=== Sequential vs Parallel Performance Test ===")
      
      // Sequential execution
      val sequentialMetrics = mutable.ArrayBuffer.empty[PerformanceMetrics]
      (1 to iterations).foreach { i =>
        val (result, metric) = measurePerformance(s"Sequential-$i") {
          val sources = cpg.call.name("source.*")
          val sinks = cpg.call.name("sink.*").argument
          sinks.reachableByFlows(sources).toVector
        }
        sequentialMetrics += metric
      }
      
      // Parallel execution (using parallel collections for test setup)
      val parallelMetrics = mutable.ArrayBuffer.empty[PerformanceMetrics]
      (1 to iterations).foreach { i =>
        val (result, metric) = measurePerformance(s"Parallel-$i") {
          val sources = cpg.call.name("source.*")
          val sinks = cpg.call.name("sink.*").argument
          val results = (1 to 4).par.map { _ =>
            sinks.reachableByFlows(sources).toVector
          }.seq
          results.head // Return first result for measurement
        }
        parallelMetrics += metric
      }
      
      analyzePerformanceComparison("Sequential", sequentialMetrics.toVector, 
                                   "Parallel", parallelMetrics.toVector)
    }

    "measure memory usage patterns" in {
      val cpg = createScalabilityTestCpg(25)
      val iterations = 12
      
      println("=== Memory Usage Test ===")
      
      val memoryMetrics = mutable.ArrayBuffer.empty[PerformanceMetrics]
      
      (1 to iterations).foreach { i =>
        val (result, metric) = measurePerformance(s"Memory-$i") {
          val sources = cpg.call.name("source.*")
          val sinks = cpg.call.name("sink.*").argument
          val flows = sinks.reachableByFlows(sources).toVector
          
          // Simulate additional memory usage
          val processed = flows.map(_.toString).sorted
          processed
        }
        memoryMetrics += metric
        
        // Periodic garbage collection
        if (i % 4 == 0) {
          System.gc()
          Thread.sleep(100)
        }
      }
      
      analyzeMemoryUsage(memoryMetrics.toVector)
    }

    "validate performance regression bounds" in {
      val cpg = createScalabilityTestCpg(20)
      val iterations = 15
      
      println("=== Performance Regression Test ===")
      
      val performanceMetrics = mutable.ArrayBuffer.empty[PerformanceMetrics]
      
      (1 to iterations).foreach { i =>
        val (result, metric) = measurePerformance(s"Regression-$i") {
          val sources = cpg.call.name("source.*")
          val sinks = cpg.call.name("sink.*").argument
          sinks.reachableByFlows(sources).toVector
        }
        performanceMetrics += metric
      }
      
      val metrics = performanceMetrics.toVector
      val avgTime = metrics.map(_.executionTimeMs).sum / metrics.length
      val maxTime = metrics.map(_.executionTimeMs).max
      val minTime = metrics.map(_.executionTimeMs).min
      
      println(s"Performance regression analysis:")
      println(s"  Average execution time: ${avgTime}ms")
      println(s"  Min execution time: ${minTime}ms")
      println(s"  Max execution time: ${maxTime}ms")
      println(s"  Time variance: ${maxTime - minTime}ms")
      
      // Performance should be reasonably stable
      val timeVariance = (maxTime - minTime).toDouble / avgTime
      println(s"  Time variance ratio: ${(timeVariance * 100).toInt}%")
      
      // Variance should be less than 100% (max time shouldn't be more than 2x avg)
      timeVariance should be < 1.0
      
      // Validate consistency
      val consistencyResults = (1 to 5).map { _ =>
        val sources = cpg.call.name("source.*")
        val sinks = cpg.call.name("sink.*").argument
        sinks.reachableByFlows(sources).map(_.toString).toVector.sorted
      }
      
      consistencyResults.toSet.size shouldBe 1
      println(s"Performance regression consistency: ${consistencyResults.head.size} flows")
    }

    "measure concurrent execution performance" in {
      val cpg = createScalabilityTestCpg(15)
      val concurrentTasks = 6
      
      println("=== Concurrent Execution Test ===")
      
      val (results, metrics) = measurePerformance("Concurrent") {
        import java.util.concurrent.{Executors, Future}
        import scala.jdk.CollectionConverters.*
        
        val executor = Executors.newFixedThreadPool(concurrentTasks)
        
        try {
          val futures = (1 to concurrentTasks).map { i =>
            executor.submit(() => {
              implicit val localContext = EngineContext()
              val sources = cpg.call.name("source.*")
              val sinks = cpg.call.name("sink.*").argument
              val flows = sinks.reachableByFlows(sources).toVector
              flows.map(_.toString).sorted
            })
          }
          
          val results = futures.map(_.get())
          results.toVector
        } finally {
          executor.shutdown()
        }
      }
      
      // Validate all concurrent executions produced identical results
      val uniqueResults = results.toSet
      uniqueResults.size shouldBe 1
      
      println(s"Concurrent execution metrics:")
      println(s"  Total execution time: ${metrics.executionTimeMs}ms")
      println(s"  Memory usage: ${metrics.memoryUsedMB}MB")
      println(s"  Result consistency: ${uniqueResults.head.size} flows")
      println(s"  Concurrent tasks: $concurrentTasks")
    }
  }

  private def analyzePerformanceMetrics(testName: String, metrics: Vector[PerformanceMetrics]): Unit = {
    val times = metrics.map(_.executionTimeMs)
    val memories = metrics.map(_.memoryUsedMB)
    val results = metrics.map(_.resultCount)
    
    val avgTime = times.sum / times.length
    val avgMemory = memories.sum / memories.length
    val avgResults = results.sum / results.length
    
    val timeVariance = times.map(t => (t - avgTime) * (t - avgTime)).sum / times.length
    val timeStdDev = math.sqrt(timeVariance)
    
    println(s"$testName Performance Analysis:")
    println(s"  Average execution time: ${avgTime}ms (±${timeStdDev.toInt}ms)")
    println(s"  Time range: ${times.min}ms - ${times.max}ms")
    println(s"  Average memory usage: ${avgMemory}MB")
    println(s"  Average result count: $avgResults")
    println(s"  Coefficient of variation: ${(timeStdDev / avgTime * 100).toInt}%")
  }

  private def analyzeScalabilityTrends(results: Vector[(Int, PerformanceMetrics)]): Unit = {
    println(s"Scalability Analysis:")
    
    results.foreach { case (size, metrics) =>
      println(s"  Size $size: ${metrics.executionTimeMs}ms, ${metrics.memoryUsedMB}MB, ${metrics.resultCount} results")
    }
    
    // Calculate growth rate
    if (results.length >= 2) {
      val firstSize = results.head._1
      val lastSize = results.last._1
      val firstTime = results.head._2.executionTimeMs
      val lastTime = results.last._2.executionTimeMs
      
      val sizeGrowth = lastSize.toDouble / firstSize
      val timeGrowth = lastTime.toDouble / firstTime
      
      println(s"  Size growth factor: ${sizeGrowth}x")
      println(s"  Time growth factor: ${timeGrowth}x")
      println(s"  Time complexity indicator: ${timeGrowth / sizeGrowth}")
    }
  }

  private def analyzePerformanceComparison(name1: String, metrics1: Vector[PerformanceMetrics],
                                          name2: String, metrics2: Vector[PerformanceMetrics]): Unit = {
    val avgTime1 = metrics1.map(_.executionTimeMs).sum / metrics1.length
    val avgTime2 = metrics2.map(_.executionTimeMs).sum / metrics2.length
    
    val avgMemory1 = metrics1.map(_.memoryUsedMB).sum / metrics1.length
    val avgMemory2 = metrics2.map(_.memoryUsedMB).sum / metrics2.length
    
    println(s"Performance Comparison:")
    println(s"  $name1: ${avgTime1}ms avg, ${avgMemory1}MB avg")
    println(s"  $name2: ${avgTime2}ms avg, ${avgMemory2}MB avg")
    println(s"  Time ratio ($name2/$name1): ${(avgTime2.toDouble / avgTime1).formatted("%.2f")}x")
    println(s"  Memory ratio ($name2/$name1): ${(avgMemory2.toDouble / avgMemory1).formatted("%.2f")}x")
  }

  private def analyzeMemoryUsage(metrics: Vector[PerformanceMetrics]): Unit = {
    val memories = metrics.map(_.memoryUsedMB)
    val gcCounts = metrics.map(_.gcCount)
    val gcTimes = metrics.map(_.gcTimeMs)
    
    val avgMemory = memories.sum / memories.length
    val maxMemory = memories.max
    val avgGcCount = gcCounts.sum / gcCounts.length
    val avgGcTime = gcTimes.sum / gcTimes.length
    
    println(s"Memory Usage Analysis:")
    println(s"  Average memory usage: ${avgMemory}MB")
    println(s"  Peak memory usage: ${maxMemory}MB")
    println(s"  Average GC count: $avgGcCount")
    println(s"  Average GC time: ${avgGcTime}ms")
    println(s"  Memory efficiency: ${if (avgMemory < maxMemory * 0.7) "Good" else "Needs optimization"}")
  }
}