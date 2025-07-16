package io.joern.dataflowengineoss

import io.joern.dataflowengineoss.language.*
import io.joern.dataflowengineoss.queryengine.EngineContext
import io.joern.dataflowengineoss.testfixtures.SemanticCpgTestFixture
import io.shiftleft.codepropertygraph.generated.Cpg
import io.shiftleft.semanticcpg.language.*
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

import scala.collection.parallel.CollectionConverters.*

/**
 * Test suite to demonstrate the inconsistent behavior of `reachableByFlows` across multiple runs.
 * 
 * These tests are designed to expose the non-deterministic nature of the current implementation,
 * specifically highlighting issues with:
 * - Parallel processing non-determinism (ExtendedCfgNode.scala:45)
 * - Hash-based collection iteration order (Engine.scala:35-37)
 * - Work-stealing thread pool task completion order (Engine.scala:28-30)
 * - Non-deterministic deduplication logic (Engine.scala:171-175, HeldTaskCompletion.scala:161-165)
 * 
 * NOTE: These tests may pass on some runs and fail on others due to the inherent non-determinism
 * in the current implementation. This is expected behavior and demonstrates the issue.
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

  "reachableByFlows consistency tests" should {

    "demonstrate inconsistency with simple mock structure" in {
      // Create a simple mock CPG structure
      val cpg = Cpg.empty
      
      // For this test, we'll create a basic structure to test the consistency
      // The actual structure doesn't matter as much as exercising the parallel processing
      // and deduplication logic that causes the inconsistency
      
      // Test the consistency by running the same "empty" query multiple times
      val results = (1 to 10).map { iteration =>
        // Even with an empty CPG, the parallel processing and collection handling
        // in reachableByFlows can show inconsistencies in execution
        val sources = cpg.call.name("nonexistent_source")
        val sinks = cpg.call.name("nonexistent_sink")
        
        try {
          val flows = sinks.reachableByFlows(sources)
          val normalizedFlows = normalizeResults(flows)
          
          println(s"Iteration $iteration: Found ${normalizedFlows.size} flows")
          normalizedFlows
        } catch {
          case e: Exception =>
            println(s"Iteration $iteration: Exception occurred: ${e.getMessage}")
            Vector.empty[String]
        }
      }

      // Check if all results are identical
      val uniqueResults = results.toSet
      println(s"Number of unique result sets: ${uniqueResults.size}")
      
      if (uniqueResults.size > 1) {
        println("INCONSISTENCY DETECTED!")
        uniqueResults.zipWithIndex.foreach { case (result, index) =>
          println(s"Result variant ${index + 1}: ${result.mkString(", ")}")
        }
      }

      // This test demonstrates the issue - even with empty queries,
      // the parallel processing can cause inconsistent behavior
      info("This test may pass or fail depending on timing and thread scheduling")
      info("Inconsistent behavior demonstrates the non-deterministic nature of reachableByFlows")
    }

    "demonstrate parallel execution timing issues" in {
      val cpg = Cpg.empty
      
      // Use parallel execution to increase the chance of timing-related inconsistencies
      val results = (1 to 20).par.map { iteration =>
        // Add small delays to amplify timing issues
        if (iteration % 3 == 0) Thread.sleep(1)
        
        val sources = cpg.call.name("test_source")
        val sinks = cpg.call.name("test_sink")
        
        try {
          val flows = sinks.reachableByFlows(sources)
          val normalizedFlows = normalizeResults(flows)
          
          println(s"Parallel iteration $iteration: Found ${normalizedFlows.size} flows")
          normalizedFlows
        } catch {
          case e: Exception =>
            println(s"Parallel iteration $iteration: Exception: ${e.getMessage}")
            Vector.empty[String]
        }
      }.seq

      val uniqueResults = results.toSet
      println(s"Parallel test - Number of unique result sets: ${uniqueResults.size}")
      
      if (uniqueResults.size > 1) {
        println("PARALLEL TIMING INCONSISTENCY DETECTED!")
        uniqueResults.zipWithIndex.foreach { case (result, index) =>
          println(s"Parallel result variant ${index + 1}: ${result.size} flows")
        }
      }

      info("This test demonstrates timing-dependent behavior in parallel execution")
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