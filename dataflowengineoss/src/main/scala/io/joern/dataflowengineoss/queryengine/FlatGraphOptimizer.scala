package io.joern.dataflowengineoss.queryengine

import flatgraph.Edge
import io.shiftleft.codepropertygraph.generated.nodes.CfgNode
import io.shiftleft.codepropertygraph.generated.EdgeTypes

import scala.collection.mutable

/**
 * FlatGraph-specific optimizations for data flow analysis.
 * 
 * These optimizations leverage FlatGraph's columnar storage layout for better
 * cache locality and performance while maintaining deterministic behavior.
 */
object FlatGraphOptimizer {

  /**
   * Optimized edge traversal for FlatGraph's array-based storage.
   * 
   * @param node The node to traverse edges from
   * @param edgeType The type of edge to traverse
   * @return Vector of edges sorted by source node ID for deterministic ordering
   */
  def optimizedEdgeTraversal(node: CfgNode, edgeType: String): Vector[Edge] = {
    // FlatGraph optimization: Use Vector for better cache locality with columnar layout
    node.inE(edgeType)
      .toVector
      .sortBy(_.src.id) // Stable ordering leveraging FlatGraph's efficient ID access
  }

  /**
   * Cache-friendly node access patterns optimized for FlatGraph.
   * 
   * @param edges Vector of edges to extract source nodes from
   * @return Vector of source nodes sorted by ID for cache efficiency
   */
  def optimizedNodeAccess(edges: Vector[Edge]): Vector[CfgNode] = {
    // FlatGraph optimization: Sort by ID for better cache locality
    edges.map(_.src.asInstanceOf[CfgNode])
      .sortBy(_.id) // Leverage FlatGraph's columnar ID storage
  }

  /**
   * Optimized collection operations for FlatGraph's memory layout.
   * 
   * @param elements Iterator of elements to process
   * @param ord Ordering for deterministic sorting
   * @return Vector sorted for optimal cache access patterns
   */
  def optimizeForFlatGraph[T](elements: Iterator[T])(implicit ord: Ordering[T]): Vector[T] = {
    // Use Vector for better cache locality with FlatGraph's columnar layout
    elements.toVector.sorted
  }

  /**
   * Efficient path node ID extraction for FlatGraph.
   * 
   * @param pathElements Vector of path elements
   * @return Set of node IDs for O(1) lookup
   */
  def extractPathNodeIds(pathElements: Vector[PathElement]): Set[Long] = {
    // FlatGraph optimization: Pre-compute node IDs for efficient lookup
    pathElements.map(_.node.id).toSet
  }

  /**
   * Optimized deduplication using FlatGraph's efficient ID access.
   * 
   * @param entries List of table entries to deduplicate
   * @return Deduplicated list sorted by node ID
   */
  def optimizedDeduplication(entries: List[TableEntry]): List[TableEntry] = {
    entries
      .groupBy { entry =>
        // Use efficient ID-based grouping instead of object comparison
        (entry.path.head.node.id, entry.path.last.node.id)
      }
      .view.map { case (_, group) =>
        if (group.size == 1) {
          group.head
        } else {
          // Efficient ID-based comparison for tie-breaking
          group.minBy(_.path.map(_.node.id).sum)
        }
      }
      .toList
      .sortBy(_.path.head.node.id) // Final stable ordering by node ID
  }

  /**
   * Batch node ID extraction for efficient FlatGraph access.
   * 
   * @param nodes Collection of nodes
   * @return Vector of node IDs sorted for cache efficiency
   */
  def batchNodeIds(nodes: IterableOnce[CfgNode]): Vector[Long] = {
    // FlatGraph optimization: Batch ID extraction for better cache locality
    nodes.iterator.map(_.id).toVector.sorted
  }

  /**
   * Optimized table entry sorting for FlatGraph.
   * 
   * @param entries List of table entries
   * @return Sorted list optimized for FlatGraph's access patterns
   */
  def optimizedTableEntrySort(entries: List[TableEntry]): List[TableEntry] = {
    // Multi-level sorting for stable, deterministic ordering
    entries.sortBy(entry => 
      (entry.path.head.node.id, entry.path.last.node.id, entry.path.length)
    )
  }

  /**
   * Efficient task fingerprint comparison for FlatGraph.
   * 
   * @param fingerprint1 First fingerprint
   * @param fingerprint2 Second fingerprint
   * @return Comparison result based on efficient ID comparison
   */
  def compareTaskFingerprints(fingerprint1: TaskFingerprint, fingerprint2: TaskFingerprint): Int = {
    // Use efficient ID-based comparison instead of object comparison
    val sinkComparison = fingerprint1.sink.id.compare(fingerprint2.sink.id)
    if (sinkComparison != 0) {
      sinkComparison
    } else {
      fingerprint1.callSiteStack.map(_.id).sum.compare(fingerprint2.callSiteStack.map(_.id).sum)
    }
  }

  /**
   * Memory-efficient result aggregation for FlatGraph.
   * 
   * @param results Iterator of results to aggregate
   * @return Aggregated results with optimal memory usage
   */
  def efficientResultAggregation[T](results: Iterator[T])(implicit ord: Ordering[T]): Vector[T] = {
    // Use Vector for better memory layout with FlatGraph
    val buffer = mutable.ArrayBuffer.empty[T]
    results.foreach(buffer += _)
    buffer.toVector.sorted
  }

  /**
   * Optimized path element comparison for FlatGraph.
   * 
   * @param path1 First path
   * @param path2 Second path
   * @return Comparison result based on efficient node ID comparison
   */
  def comparePathElements(path1: Vector[PathElement], path2: Vector[PathElement]): Int = {
    // Efficient comparison using node IDs instead of object comparison
    val lengthComparison = path1.length.compare(path2.length)
    if (lengthComparison != 0) {
      lengthComparison
    } else {
      path1.map(_.node.id).sum.compare(path2.map(_.node.id).sum)
    }
  }
}