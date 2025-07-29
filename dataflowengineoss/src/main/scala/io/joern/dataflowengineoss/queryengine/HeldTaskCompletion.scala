package io.joern.dataflowengineoss.queryengine

import scala.collection.mutable
import scala.collection.parallel.CollectionConverters.*
import io.shiftleft.codepropertygraph.generated.language.*

/** Complete held tasks using the result table. The result table is modified in the process.
  *
  * Results obtained when completing a held task depend on the following:
  *
  * (a) the `initialPath` of the held task (path from the node where the task was held down to a sink)
  *
  * (b) the entries in the table for `heldTask.fingerprint`.
  *
  * Upon completing a task, new results are stored in the table for each task of its `taskStack`. This means that we may
  * not end up with all results when first completing a task because another task needs to be completed first so that
  * all results for `heldTask.fingerprint` are available. We address this problem by computing results in a loop until
  * no more changes can be observed.
  */
class HeldTaskCompletion(
  heldTasks: List[ReachableByTask],
  resultTable: mutable.Map[TaskFingerprint, List[TableEntry]]
) {

  /** Add results produced by held task until no more change can be observed.
    *
    * We use the following simple algorithm (that can possibly be optimized in the future):
    *
    * For each held `task`, we keep a Boolean `changed(task)`, which indicates whether new results for the `task` were
    * produced. We initialize the Booleans to be true. Computation is terminated when all Booleans are false, that is,
    * when no more changes in the result table can be observed.
    *
    * If we do detect a change, we determine all tasks for which changed results exist and recompute their results. We
    * compare the results with those produced previously (stored in `resultsProducedByTask`). If any new results were
    * created, `changed` is set to true for the result's table entry and `resultsProductByTask` is updated.
    */
  def completeHeldTasks(): Unit = {
    val startTime = System.currentTimeMillis()
    val totalTasks = heldTasks.size
    
    println(s"[DATAFLOW-DEBUG] Starting completeHeldTasks with ${totalTasks} held tasks")

    deduplicateResultTable()
    val toProcess =
      heldTasks.distinct.sortBy(x =>
        (x.fingerprint.sink.id, x.fingerprint.callSiteStack.map(_.id).toString, x.callDepth)
      )
    var resultsProducedByTask: Map[ReachableByTask, Set[(TaskFingerprint, TableEntry)]] = Map()

    def allChanged  = toProcess.map { task => task.fingerprint -> true }.toMap
    def noneChanged = toProcess.map { t => t.fingerprint -> false }.toMap

    var changed: Map[TaskFingerprint, Boolean] = allChanged
    var iteration = 0

    while (changed.values.toList.contains(true)) {
      iteration += 1
      val iterationStartTime = System.currentTimeMillis()
      val changedCount = changed.values.count(_ == true)
      
      println(s"[DATAFLOW-DEBUG] Iteration ${iteration}: Processing ${changedCount} changed tasks")
      
      val taskResultsPairs = toProcess
        .filter(t => changed(t.fingerprint))
        .par
        .map { t =>
          val resultsForTask = resultsForHeldTask(t).toSet
          val newResults     = resultsForTask -- resultsProducedByTask.getOrElse(t, Set())
          (t, resultsForTask, newResults)
        }
        .filter { case (_, _, newResults) => newResults.nonEmpty }
        .seq

      val tasksWithNewResults = taskResultsPairs.size
      val totalNewResults = taskResultsPairs.map(_._3.size).sum

      changed = noneChanged
      taskResultsPairs.foreach { case (t, resultsForTask, newResults) =>
        addCompletedTasksToMainTable(newResults.toList)
        newResults.foreach { case (fingerprint, _) =>
          changed += fingerprint -> true
        }
        resultsProducedByTask += (t -> resultsForTask)
      }
      
      val iterationTime = System.currentTimeMillis() - iterationStartTime
      println(s"[DATAFLOW-DEBUG] Iteration ${iteration} completed: ${tasksWithNewResults} tasks produced ${totalNewResults} new results in ${iterationTime}ms")
    }
    
    val finalDeduplicationStart = System.currentTimeMillis()
    deduplicateResultTable()
    val finalDeduplicationTime = System.currentTimeMillis() - finalDeduplicationStart
    
    val totalTime = System.currentTimeMillis() - startTime
    println(s"[DATAFLOW-DEBUG] completeHeldTasks finished: ${iteration} iterations, final deduplication took ${finalDeduplicationTime}ms, total time ${totalTime}ms")
  }

  /** In essence, completing a held task simply means appending the path stored in the held task to all results that are
    * available for the held task in the table. In practice, we create one result for each task of the parent task's
    * `taskStack`, so that we do not only get a new result for the sink, but one for each of the parent nodes on the
    * way.
    */
  private def resultsForHeldTask(heldTask: ReachableByTask): List[(TaskFingerprint, TableEntry)] = {
    // Create a flat list of results by computing results for each
    // table entry and appending them.
    resultTable.get(heldTask.fingerprint) match {
      case Some(results) =>
        results
          .flatMap { r =>
            createResultsForHeldTaskAndTableResult(heldTask, r)
          }
      case None => List()
    }
  }

  /** This method creates a list of results from a held task and a table entry by appending paths of the held task to
    * the path stored in the held task (`initialPath`) up to each of its parent tasks.
    *
    * A possible optimization here is to store computed slices in a lazily populated table and attempt to look them up.
    */
  private def createResultsForHeldTaskAndTableResult(
    heldTask: ReachableByTask,
    result: TableEntry
  ): List[(TaskFingerprint, TableEntry)] = {
    val parentTasks = heldTask.taskStack.dropRight(1)
    val initialPath = heldTask.initialPath
    parentTasks
      .map { parentTask =>
        val stopIndex = initialPath
          .map(x => (x.node, x.callSiteStack))
          .indexOf((parentTask.sink, parentTask.callSiteStack)) + 1
        val initialPathOnlyUpToSink = initialPath.slice(0, stopIndex)
        val newPath                 = result.path ++ initialPathOnlyUpToSink
        (parentTask, TableEntry(newPath))
      }
      .filter { case (_, tableEntry) => containsCycle(tableEntry) }
  }

  private def containsCycle(tableEntry: TableEntry): Boolean = {
    val pathSeq = tableEntry.path.map(x => (x.node, x.callSiteStack, x.isOutputArg, x.outEdgeLabel))
    pathSeq.distinct.size == pathSeq.size
  }

  private def addCompletedTasksToMainTable(results: List[(TaskFingerprint, TableEntry)]): Unit = {
    results.groupBy(_._1).foreach { case (fingerprint, resultList) =>
      val entries = resultList.map(_._2)
      val old     = resultTable.getOrElse(fingerprint, Vector()).toList
      resultTable.put(fingerprint, deduplicateTableEntries(old ++ entries))
    }
  }

  private def deduplicateResultTable(): Unit = {
    resultTable.keys.foreach { key =>
      val results = resultTable(key)
      resultTable.put(key, deduplicateTableEntries(results))
    }
  }

  /** This method deduplicates the list of entries stored in a table cell.
    *
    * We treat entries as the same if their start and end point are the same. Points are given by nodes in the graph,
    * the `callSiteStack` and the `isOutputArg` flag.
    *
    * For a group of flows that we treat as the same, we select the flow with the maximum length. If there are multiple
    * flows with maximum length, then we compute a string representation of the flows - taking into account all fields
    *   - and select the flow with maximum length that is smallest in terms of this string representation.
    */
  private def deduplicateTableEntries(list: List[TableEntry]): List[TableEntry] = {
    val startTime = System.currentTimeMillis()
    val originalSize = list.size
    
    // Early return for small lists
    if (originalSize <= 1) {
      return list
    }
    
    // Log entry with collection size
    if (originalSize > 100) {
      println(s"[DATAFLOW-DEBUG] deduplicateTableEntries: Processing ${originalSize} entries")
      logSampleNodeInfo(list.take(5))
    }
    
    // Size limit to prevent runaway processing
    val MAX_ENTRIES = 10000
    val processedList = if (originalSize > MAX_ENTRIES) {
      println(s"[DATAFLOW-DEBUG] WARNING: Collection size ${originalSize} exceeds limit ${MAX_ENTRIES}, truncating")
      list.take(MAX_ENTRIES)
    } else {
      list
    }
    
    try {
      val groupingStartTime = System.currentTimeMillis()
      
      // Use simplified hash keys based on node IDs instead of full objects
      val grouped = processedList.groupBy { result =>
        val headPath = result.path.headOption
        val lastPath = result.path.lastOption
        
        val headKey = headPath.map(x => (x.node.id, x.callSiteStack.map(_.id), x.isOutputArg)).getOrElse((0L, List.empty[Long], false))
        val lastKey = lastPath.map(x => (x.node.id, x.callSiteStack.map(_.id), x.isOutputArg)).getOrElse((0L, List.empty[Long], false))
        (headKey, lastKey)
      }
      
      val groupingTime = System.currentTimeMillis() - groupingStartTime
      if (groupingTime > 1000) {
        println(s"[DATAFLOW-DEBUG] Grouping took ${groupingTime}ms for ${processedList.size} entries")
      }
      
      val processingStartTime = System.currentTimeMillis()
      val result = grouped.map { case (_, groupList) =>
        if (groupList.size == 1) {
          // Fast path for single entries
          groupList.head
        } else {
          // Optimize for multiple entries
          deduplicateGroup(groupList)
        }
      }.toList
      
      val processingTime = System.currentTimeMillis() - processingStartTime
      val totalTime = System.currentTimeMillis() - startTime
      
      val finalSize = result.size
      if (totalTime > 500 || originalSize > 100) {
        println(s"[DATAFLOW-DEBUG] deduplicateTableEntries completed: ${originalSize} -> ${finalSize} entries in ${totalTime}ms")
      }
      
      result
      
    } catch {
      case e: Exception =>
        println(s"[DATAFLOW-DEBUG] ERROR in deduplicateTableEntries: ${e.getMessage}")
        println(s"[DATAFLOW-DEBUG] Original list size: ${originalSize}")
        logSampleNodeInfo(list.take(3))
        throw e
    }
  }
  
  private def deduplicateGroup(groupList: List[TableEntry]): TableEntry = {
    // Find entries with maximum path length
    val maxLength = groupList.map(_.path.length).max
    val maxLengthEntries = groupList.filter(_.path.length == maxLength)
    
    if (maxLengthEntries.size == 1) {
      maxLengthEntries.head
    } else {
      // Use more efficient tie-breaking than string concatenation
      maxLengthEntries.minBy { entry =>
        // Create a more efficient comparison key
        val pathIds = entry.path.map(_.node.id)
        val pathKey = pathIds.mkString(",")
        pathKey
      }
    }
  }
  
  private def logSampleNodeInfo(samples: List[TableEntry]): Unit = {
    samples.zipWithIndex.foreach { case (entry, idx) =>
      val pathElements = entry.path
      if (pathElements.nonEmpty) {
        val firstNode = pathElements.head.node
        val lastNode = pathElements.last.node
        
        println(s"[DATAFLOW-DEBUG] Sample ${idx + 1}:")
        println(s"  Path length: ${pathElements.size}")
        println(s"  First node: id=${firstNode.id}, ${getNodeInfo(firstNode)}")
        println(s"  Last node: id=${lastNode.id}, ${getNodeInfo(lastNode)}")
      }
    }
  }
  
  private def getNodeInfo(node: io.shiftleft.codepropertygraph.generated.nodes.AstNode): String = {
    try {
      val nodeLabel = node.label
      val nodeId = node.id
      
      // Try to get code property safely
      val codeOpt = try {
        node.propertyOption("CODE").map(_.toString)
      } catch {
        case _: Exception => None
      }
      
      // Try to get line number safely
      val lineNumberOpt = try {
        node.propertyOption("LINE_NUMBER").map(_.toString)
      } catch {
        case _: Exception => None
      }
      
      // Try to get file name safely
      val fileNameOpt = try {
        node.propertyOption("FILENAME").map(_.toString)
      } catch {
        case _: Exception => None
      }
      
      val code = codeOpt.map(c => s"'${c.take(50)}'").getOrElse("'unknown'")
      val fileName = fileNameOpt.getOrElse("unknown")
      val lineNumber = lineNumberOpt.getOrElse("-1")
      
      s"label=$nodeLabel, code=$code, file=$fileName, line=$lineNumber"
    } catch {
      case _: Exception => s"label=${node.label}, id=${node.id}"
    }
  }

}
