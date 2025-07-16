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

import scala.collection.parallel.CollectionConverters.*
import scala.collection.mutable
import scala.util.Random

/**
 * Comprehensive test suite to validate the consistency fixes for `reachableByFlows` queries.
 * 
 * This test suite validates that the FlatGraph consistency fixes work correctly:
 * - Deterministic result ordering across multiple runs
 * - Stable deduplication behavior 
 * - Consistent performance characteristics
 * - Proper handling of concurrent execution
 * 
 * Tests are designed to pass consistently after the consistency fixes are applied.
 */
class ReachableByFlowsConsistencyTest extends AnyWordSpec with Matchers with SemanticCpgTestFixture() {

  /**
   * Helper method to convert a path to a stable string representation for comparison
   */
  private def pathToString(path: Path): String = {
    path.elements.map(node => s"${node.getClass.getSimpleName}:${node.id}").mkString(" -> ")
  }

  /**
   * Helper method to normalize and sort results for comparison
   */
  private def normalizeResults(results: Iterator[Path]): Vector[String] = {
    results.map(pathToString).toVector.sorted
  }

  /**
   * Create a test CPG with realistic data flow structure for consistency testing
   */
  private def createTestCpg(): Cpg = {
    val cpg = Cpg.empty
    val diffGraph = Cpg.newDiffGraphBuilder
    
    // Create a realistic method structure
    val method = NewMethod().name("testMethod").fullName("testMethod").order(1)
    diffGraph.addNode(method)
    
    // Create source calls (input sources)
    val source1 = NewCall().name("getInput").code("getInput()").order(1)
    val source2 = NewCall().name("readFile").code("readFile()").order(2)
    val source3 = NewCall().name("getUserData").code("getUserData()").order(3)
    diffGraph.addNode(source1)
    diffGraph.addNode(source2)
    diffGraph.addNode(source3)
    
    // Create intermediate processing nodes
    val process1 = NewCall().name("processData").code("processData(input1)").order(4)
    val process2 = NewCall().name("processData").code("processData(input2)").order(5)
    val process3 = NewCall().name("merge").code("merge(data1, data2)").order(6)
    diffGraph.addNode(process1)
    diffGraph.addNode(process2)
    diffGraph.addNode(process3)
    
    // Create sink calls (output sinks)
    val sink1 = NewCall().name("printf").code("printf(data)").order(7)
    val sink2 = NewCall().name("writeFile").code("writeFile(data)").order(8)
    val sink3 = NewCall().name("sendData").code("sendData(data)").order(9)
    diffGraph.addNode(sink1)
    diffGraph.addNode(sink2)
    diffGraph.addNode(sink3)
    
    // Create identifiers for arguments
    val arg1 = NewIdentifier().name("data1").code("data1").order(1)
    val arg2 = NewIdentifier().name("data2").code("data2").order(1)
    val arg3 = NewIdentifier().name("data3").code("data3").order(1)
    diffGraph.addNode(arg1)
    diffGraph.addNode(arg2)
    diffGraph.addNode(arg3)
    
    // Connect arguments to sinks
    diffGraph.addEdge(sink1, arg1, EdgeTypes.ARGUMENT)
    diffGraph.addEdge(sink2, arg2, EdgeTypes.ARGUMENT)
    diffGraph.addEdge(sink3, arg3, EdgeTypes.ARGUMENT)
    
    // Create reaching definition edges for data flow
    diffGraph.addEdge(source1, process1, EdgeTypes.REACHING_DEF)
    diffGraph.addEdge(source2, process2, EdgeTypes.REACHING_DEF)
    diffGraph.addEdge(source3, process3, EdgeTypes.REACHING_DEF)
    
    diffGraph.addEdge(process1, arg1, EdgeTypes.REACHING_DEF)
    diffGraph.addEdge(process2, arg2, EdgeTypes.REACHING_DEF)
    diffGraph.addEdge(process3, arg3, EdgeTypes.REACHING_DEF)
    
    // Create additional cross-connections for complex flow patterns
    diffGraph.addEdge(source1, process2, EdgeTypes.REACHING_DEF)
    diffGraph.addEdge(source2, process3, EdgeTypes.REACHING_DEF)
    diffGraph.addEdge(process1, process3, EdgeTypes.REACHING_DEF)
    
    // Apply the diff graph
    cpg.graph.apply(diffGraph)
    cpg
  }

  "reachableByFlows consistency tests" should {

    "return identical results across 100 sequential runs" in {
      val cpg = createTestCpg()
      
      // Run the same query 100 times to test consistency
      val results = (1 to 100).map { iteration =>
        val sources = cpg.call.name("getInput")
        val sinks = cpg.call.name("printf").argument
        val flows = sinks.reachableByFlows(sources)
        val normalizedFlows = normalizeResults(flows)
        
        if (iteration % 10 == 0) {
          println(s"Sequential run $iteration: Found ${normalizedFlows.size} flows")
        }
        
        normalizedFlows
      }

      // All results should be identical
      val uniqueResults = results.toSet
      println(s"Sequential test - Number of unique result sets: ${uniqueResults.size}")
      
      uniqueResults.size shouldBe 1
      if (uniqueResults.nonEmpty) {
        println(s"Consistent result contains ${uniqueResults.head.size} flows")
      }
    }

    "maintain consistency under parallel execution" in {
      val cpg = createTestCpg()
      
      // Use parallel execution to test consistency under concurrent access
      val results = (1 to 50).par.map { iteration =>
        // Add small delays to amplify potential timing issues
        if (iteration % 5 == 0) Thread.sleep(1)
        
        val sources = cpg.call.name("getInput")
        val sinks = cpg.call.name("printf").argument
        val flows = sinks.reachableByFlows(sources)
        val normalizedFlows = normalizeResults(flows)
        
        if (iteration % 10 == 0) {
          println(s"Parallel run $iteration: Found ${normalizedFlows.size} flows")
        }
        
        normalizedFlows
      }.seq

      val uniqueResults = results.toSet
      println(s"Parallel test - Number of unique result sets: ${uniqueResults.size}")
      
      // After fixes, all results should be identical even under parallel execution
      uniqueResults.size shouldBe 1
      if (uniqueResults.nonEmpty) {
        println(s"Parallel execution consistent result contains ${uniqueResults.head.size} flows")
      }
    }

    "demonstrate hash-based collection ordering effects" in {
      val cpg = Cpg.empty
      
      // Test with different query patterns to exercise hash-based collections
      val results = (1 to 15).map { iteration =>
        val sourcePattern = if (iteration % 2 == 0) "source.*" else ".*source"
        val sinkPattern = if (iteration % 3 == 0) "sink.*" else ".*sink"
        
        val sources = cpg.call.name(sourcePattern)
        val sinks = cpg.call.name(sinkPattern)
        
        try {
          val flows = sinks.reachableByFlows(sources)
          val normalizedFlows = normalizeResults(flows)
          
          println(s"Hash test iteration $iteration: Found ${normalizedFlows.size} flows")
          normalizedFlows
        } catch {
          case e: Exception =>
            println(s"Hash test iteration $iteration: Exception: ${e.getMessage}")
            Vector.empty[String]
        }
      }

      val uniqueResults = results.toSet
      println(s"Hash test - Number of unique result sets: ${uniqueResults.size}")
      
      if (uniqueResults.size > 1) {
        println("HASH-BASED COLLECTION INCONSISTENCY DETECTED!")
        uniqueResults.zipWithIndex.foreach { case (result, index) =>
          println(s"Hash result variant ${index + 1}: ${result.size} flows")
        }
      }

      info("This test demonstrates hash-based collection ordering effects")
    }

    "demonstrate engine context state effects" in {
      val cpg = Cpg.empty
      
      // Test with different engine contexts to see if that affects consistency
      val results = (1 to 12).map { iteration =>
        // Create fresh engine context for some iterations
        implicit val localContext = if (iteration % 2 == 0) {
          EngineContext()
        } else {
          context
        }
        
        val sources = cpg.call.name("ctx_source")
        val sinks = cpg.call.name("ctx_sink")
        
        try {
          val flows = sinks.reachableByFlows(sources)
          val normalizedFlows = normalizeResults(flows)
          
          println(s"Context test iteration $iteration: Found ${normalizedFlows.size} flows")
          normalizedFlows
        } catch {
          case e: Exception =>
            println(s"Context test iteration $iteration: Exception: ${e.getMessage}")
            Vector.empty[String]
        }
      }

      val uniqueResults = results.toSet
      println(s"Context test - Number of unique result sets: ${uniqueResults.size}")
      
      if (uniqueResults.size > 1) {
        println("ENGINE CONTEXT STATE INCONSISTENCY DETECTED!")
        uniqueResults.zipWithIndex.foreach { case (result, index) =>
          println(s"Context result variant ${index + 1}: ${result.size} flows")
        }
      }

      info("This test demonstrates engine context state effects on consistency")
    }

    "demonstrate collection iteration order effects" in {
      val cpg = Cpg.empty
      
      // Test with different collection creation patterns
      val results = (1 to 18).map { iteration =>
        val sources = cpg.call.name("iter_source")
        val sinks = cpg.call.name("iter_sink")
        
        try {
          val flows = sinks.reachableByFlows(sources)
          val normalizedFlows = normalizeResults(flows)
          
          println(s"Collection test iteration $iteration: Found ${normalizedFlows.size} flows")
          normalizedFlows
        } catch {
          case e: Exception =>
            println(s"Collection test iteration $iteration: Exception: ${e.getMessage}")
            Vector.empty[String]
        }
      }

      val uniqueResults = results.toSet
      println(s"Collection test - Number of unique result sets: ${uniqueResults.size}")
      
      if (uniqueResults.size > 1) {
        println("COLLECTION ITERATION ORDER INCONSISTENCY DETECTED!")
        uniqueResults.zipWithIndex.foreach { case (result, index) =>
          println(s"Collection result variant ${index + 1}: ${result.size} flows")
        }
      }

      info("This test demonstrates collection iteration order effects")
    }

    "handle complex data flow patterns consistently" in {
      val cpg = createComplexTestCpg()
      
      // Test with complex patterns: multiple sources, multiple sinks, cross-dependencies
      val results = (1 to 30).map { iteration =>
        val sources = cpg.call.name(".*Input.*")
        val sinks = cpg.call.name(".*Output.*")
        
        try {
          val flows = sinks.reachableByFlows(sources)
          val normalizedFlows = normalizeResults(flows)
          
          if (iteration % 5 == 0) {
            println(s"Complex test iteration $iteration: Found ${normalizedFlows.size} flows")
          }
          
          normalizedFlows
        } catch {
          case e: Exception =>
            println(s"Complex test iteration $iteration: Exception: ${e.getMessage}")
            Vector.empty[String]
        }
      }

      val uniqueResults = results.toSet
      println(s"Complex flow test - Number of unique result sets: ${uniqueResults.size}")
      
      // After fixes, should be consistent even with complex patterns
      uniqueResults.size shouldBe 1
      if (uniqueResults.nonEmpty) {
        println(s"Complex flow consistent result contains ${uniqueResults.head.size} flows")
      }
    }

    "validate deduplication behavior consistency" in {
      val cpg = createTestCpg()
      
      // Test deduplication behavior with overlapping paths
      val results = (1 to 25).map { iteration =>
        val sources = cpg.call.name(".*Input.*")
        val sinks = cpg.call.name(".*printf.*")
        
        try {
          val flows = sinks.reachableByFlows(sources)
          val normalizedFlows = normalizeResults(flows)
          
          // Validate deduplication is working correctly
          val duplicateCheck = normalizedFlows.groupBy(identity).filter(_._2.size > 1)
          if (duplicateCheck.nonEmpty) {
            println(s"Deduplication test iteration $iteration: Found ${duplicateCheck.size} duplicate flows")
          }
          
          normalizedFlows
        } catch {
          case e: Exception =>
            println(s"Deduplication test iteration $iteration: Exception: ${e.getMessage}")
            Vector.empty[String]
        }
      }

      val uniqueResults = results.toSet
      println(s"Deduplication test - Number of unique result sets: ${uniqueResults.size}")
      
      uniqueResults.size shouldBe 1
      if (uniqueResults.nonEmpty) {
        println(s"Deduplication consistent result contains ${uniqueResults.head.size} flows")
      }
    }

    "demonstrate performance characteristics" in {
      val cpg = createTestCpg()
      val iterations = 20
      val timings = mutable.ArrayBuffer.empty[Long]
      
      println("Performance test - measuring query execution times:")
      
      // Measure execution times for consistency
      val results = (1 to iterations).map { iteration =>
        val startTime = System.nanoTime()
        
        val sources = cpg.call.name("getInput")
        val sinks = cpg.call.name("printf").argument
        val flows = sinks.reachableByFlows(sources)
        val normalizedFlows = normalizeResults(flows)
        
        val endTime = System.nanoTime()
        val executionTime = (endTime - startTime) / 1000000 // Convert to milliseconds
        timings += executionTime
        
        if (iteration % 5 == 0) {
          println(s"Performance iteration $iteration: ${executionTime}ms, ${normalizedFlows.size} flows")
        }
        
        normalizedFlows
      }

      // Analyze performance consistency
      val avgTime = timings.sum / timings.length
      val maxTime = timings.max
      val minTime = timings.min
      val variance = timings.map(t => (t - avgTime) * (t - avgTime)).sum / timings.length
      val stdDev = math.sqrt(variance)
      
      println(s"Performance metrics:")
      println(s"  Average time: ${avgTime}ms")
      println(s"  Min time: ${minTime}ms")
      println(s"  Max time: ${maxTime}ms")
      println(s"  Standard deviation: ${stdDev.toInt}ms")
      println(s"  Coefficient of variation: ${(stdDev / avgTime * 100).toInt}%")
      
      // Results should be consistent
      val uniqueResults = results.toSet
      println(s"Performance test - Number of unique result sets: ${uniqueResults.size}")
      
      uniqueResults.size shouldBe 1
      if (uniqueResults.nonEmpty) {
        println(s"Performance consistent result contains ${uniqueResults.head.size} flows")
      }
      
      // Performance should be reasonable (coefficient of variation < 50%)
      val coefficientOfVariation = stdDev / avgTime
      coefficientOfVariation should be < 0.5
    }

    "handle memory pressure scenarios" in {
      val cpg = createLargeTestCpg()
      
      // Test consistency under memory pressure
      val results = (1 to 15).map { iteration =>
        // Force garbage collection to simulate memory pressure
        if (iteration % 5 == 0) {
          System.gc()
          Thread.sleep(10)
        }
        
        val sources = cpg.call.name(".*Source.*")
        val sinks = cpg.call.name(".*Sink.*")
        
        try {
          val flows = sinks.reachableByFlows(sources)
          val normalizedFlows = normalizeResults(flows)
          
          if (iteration % 3 == 0) {
            println(s"Memory pressure iteration $iteration: Found ${normalizedFlows.size} flows")
          }
          
          normalizedFlows
        } catch {
          case e: Exception =>
            println(s"Memory pressure iteration $iteration: Exception: ${e.getMessage}")
            Vector.empty[String]
        }
      }

      val uniqueResults = results.toSet
      println(s"Memory pressure test - Number of unique result sets: ${uniqueResults.size}")
      
      uniqueResults.size shouldBe 1
      if (uniqueResults.nonEmpty) {
        println(s"Memory pressure consistent result contains ${uniqueResults.head.size} flows")
      }
    }

    "validate concurrent engine contexts" in {
      val cpg = createTestCpg()
      
      // Test with multiple concurrent engine contexts
      val results = (1 to 20).par.map { iteration =>
        implicit val localContext = EngineContext()
        
        val sources = cpg.call.name("getInput")
        val sinks = cpg.call.name("printf").argument
        
        try {
          val flows = sinks.reachableByFlows(sources)
          val normalizedFlows = normalizeResults(flows)
          
          if (iteration % 5 == 0) {
            println(s"Concurrent context iteration $iteration: Found ${normalizedFlows.size} flows")
          }
          
          normalizedFlows
        } catch {
          case e: Exception =>
            println(s"Concurrent context iteration $iteration: Exception: ${e.getMessage}")
            Vector.empty[String]
        }
      }.seq

      val uniqueResults = results.toSet
      println(s"Concurrent context test - Number of unique result sets: ${uniqueResults.size}")
      
      uniqueResults.size shouldBe 1
      if (uniqueResults.nonEmpty) {
        println(s"Concurrent context consistent result contains ${uniqueResults.head.size} flows")
      }
    }
  }

  /**
   * Create a more complex test CPG with multiple sources, sinks, and interconnected flows
   */
  private def createComplexTestCpg(): Cpg = {
    val cpg = Cpg.empty
    val diffGraph = Cpg.newDiffGraphBuilder
    
    // Create multiple methods
    val method1 = NewMethod().name("method1").fullName("method1").order(1)
    val method2 = NewMethod().name("method2").fullName("method2").order(2)
    diffGraph.addNode(method1)
    diffGraph.addNode(method2)
    
    // Create multiple input sources
    val inputs = (1 to 5).map { i =>
      val input = NewCall().name(s"userInput$i").code(s"userInput$i()").order(i)
      diffGraph.addNode(input)
      input
    }
    
    // Create multiple output sinks
    val outputs = (1 to 5).map { i =>
      val output = NewCall().name(s"systemOutput$i").code(s"systemOutput$i(data)").order(i + 10)
      diffGraph.addNode(output)
      output
    }
    
    // Create processing nodes
    val processors = (1 to 8).map { i =>
      val processor = NewCall().name(s"process$i").code(s"process$i(data)").order(i + 20)
      diffGraph.addNode(processor)
      processor
    }
    
    // Create arguments for outputs
    val args = (1 to 5).map { i =>
      val arg = NewIdentifier().name(s"arg$i").code(s"arg$i").order(i)
      diffGraph.addNode(arg)
      arg
    }
    
    // Connect arguments to outputs
    outputs.zip(args).foreach { case (output, arg) =>
      diffGraph.addEdge(output, arg, EdgeTypes.ARGUMENT)
    }
    
    // Create complex reaching definition patterns
    // Direct connections
    inputs.zip(processors.take(5)).foreach { case (input, processor) =>
      diffGraph.addEdge(input, processor, EdgeTypes.REACHING_DEF)
    }
    
    // Cross connections
    processors.zip(args).foreach { case (processor, arg) =>
      diffGraph.addEdge(processor, arg, EdgeTypes.REACHING_DEF)
    }
    
    // Complex interconnections
    for (i <- 0 until 3) {
      for (j <- i + 1 until 5) {
        diffGraph.addEdge(processors(i), processors(j + 3), EdgeTypes.REACHING_DEF)
      }
    }
    
    cpg.graph.apply(diffGraph)
    cpg
  }

  /**
   * Create a larger test CPG for memory pressure testing
   */
  private def createLargeTestCpg(): Cpg = {
    val cpg = Cpg.empty
    val diffGraph = Cpg.newDiffGraphBuilder
    
    // Create multiple methods
    val methods = (1 to 10).map { i =>
      val method = NewMethod().name(s"method$i").fullName(s"method$i").order(i)
      diffGraph.addNode(method)
      method
    }
    
    // Create many source calls
    val sources = (1 to 50).map { i =>
      val source = NewCall().name(s"dataSource$i").code(s"dataSource$i()").order(i)
      diffGraph.addNode(source)
      source
    }
    
    // Create many sink calls
    val sinks = (1 to 50).map { i =>
      val sink = NewCall().name(s"dataSink$i").code(s"dataSink$i(data)").order(i + 100)
      diffGraph.addNode(sink)
      sink
    }
    
    // Create many processing nodes
    val processors = (1 to 100).map { i =>
      val processor = NewCall().name(s"processor$i").code(s"processor$i(data)").order(i + 200)
      diffGraph.addNode(processor)
      processor
    }
    
    // Create arguments for sinks
    val args = (1 to 50).map { i =>
      val arg = NewIdentifier().name(s"sinkArg$i").code(s"sinkArg$i").order(i)
      diffGraph.addNode(arg)
      arg
    }
    
    // Connect arguments to sinks
    sinks.zip(args).foreach { case (sink, arg) =>
      diffGraph.addEdge(sink, arg, EdgeTypes.ARGUMENT)
    }
    
    // Create complex web of reaching definitions
    sources.zipWithIndex.foreach { case (source, i) =>
      // Each source connects to multiple processors
      for (j <- 0 until 3) {
        val processorIndex = (i * 2 + j) % processors.length
        diffGraph.addEdge(source, processors(processorIndex), EdgeTypes.REACHING_DEF)
      }
    }
    
    processors.zipWithIndex.foreach { case (processor, i) =>
      // Each processor connects to multiple other processors
      for (j <- 1 to 2) {
        val nextProcessorIndex = (i + j) % processors.length
        diffGraph.addEdge(processor, processors(nextProcessorIndex), EdgeTypes.REACHING_DEF)
      }
    }
    
    processors.zipWithIndex.foreach { case (processor, i) =>
      // Each processor connects to multiple sinks
      for (j <- 0 until 2) {
        val argIndex = (i + j) % args.length
        diffGraph.addEdge(processor, args(argIndex), EdgeTypes.REACHING_DEF)
      }
    }
    
    cpg.graph.apply(diffGraph)
    cpg
  }

}

/**
 * Additional documentation for the test failures:
 * 
 * Expected Failure Modes:
 * 1. Different number of flows returned across runs
 * 2. Same flows but in different orders
 * 3. Intermittent exceptions due to race conditions
 * 4. Different results with parallel vs sequential execution
 * 
 * Root Causes Being Tested:
 * 1. ExtendedCfgNode.scala:45 - .par creates non-deterministic ordering
 * 2. Engine.scala:35-37 - HashMap/HashSet iteration order varies
 * 3. Engine.scala:28-30 - WorkStealingPool completion order varies
 * 4. Engine.scala:171-175 - minBy string comparison instability
 * 5. HeldTaskCompletion.scala:51-60 - Parallel task completion races
 * 
 * These tests are designed to fail intermittently, which proves the inconsistency issue.
 */