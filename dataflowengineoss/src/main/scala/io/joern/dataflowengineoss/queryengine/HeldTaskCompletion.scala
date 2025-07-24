package io.joern.dataflowengineoss.queryengine

import org.slf4j.{Logger, LoggerFactory}
import scala.collection.mutable
import scala.collection.parallel.CollectionConverters.*

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

  private val logger: Logger = LoggerFactory.getLogger(classOf[HeldTaskCompletion])

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
    logger.info(s"[HELD_TASK_COMPLETION] Starting completion of ${heldTasks.size} held tasks")
    logger.info(s"[HELD_TASK_COMPLETION] Initial result table size: ${resultTable.size} entries")

    // Log sample of held tasks for debugging
    heldTasks.take(5).foreach { task =>
      logger.info(s"[HELD_TASK_COMPLETION] Sample held task - Sink: ${task.fingerprint.sink.getClass.getSimpleName}:${task.fingerprint.sink.id}, CallDepth: ${task.callDepth}, InitialPathLength: ${task.initialPath.length}")
    }

    deduplicateResultTable()
    val dedupTime = System.currentTimeMillis()
    logger.info(s"[HELD_TASK_COMPLETION] Initial deduplication completed in ${dedupTime - startTime}ms")
    
    val toProcess =
      heldTasks.distinct.sortBy(x =>
        (x.fingerprint.sink.id, x.fingerprint.callSiteStack.map(_.id).toString, x.callDepth)
      )
    logger.info(s"[HELD_TASK_COMPLETION] Processing ${toProcess.size} distinct held tasks (after deduplication)")
    
    var resultsProducedByTask: Map[ReachableByTask, Set[(TaskFingerprint, TableEntry)]] = Map()
    var iterationCount = 0
    val maxIterations = 1000 // Circuit breaker

    def allChanged  = toProcess.map { task => task.fingerprint -> true }.toMap
    def noneChanged = toProcess.map { t => t.fingerprint -> false }.toMap

    var changed: Map[TaskFingerprint, Boolean] = allChanged
    logger.info(s"[HELD_TASK_COMPLETION] Starting fixed-point iteration with ${changed.count(_._2)} changed tasks")

    while (changed.values.toList.contains(true) && iterationCount < maxIterations) {
      iterationCount += 1
      val iterationStartTime = System.currentTimeMillis()
      logger.info(s"[HELD_TASK_COMPLETION] Starting iteration $iterationCount with ${changed.count(_._2)} changed tasks")
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

      changed = noneChanged
      taskResultsPairs.foreach { case (t, resultsForTask, newResults) =>
        if (newResults.nonEmpty) {
          logger.debug(s"[HELD_TASK_COMPLETION] Task ${t.fingerprint.sink.id} produced ${newResults.size} new results")
        }
        addCompletedTasksToMainTable(newResults.toList)
        newResults.foreach { case (fingerprint, _) =>
          changed += fingerprint -> true
        }
        resultsProducedByTask += (t -> resultsForTask)
      }
      
      val iterationDuration = System.currentTimeMillis() - iterationStartTime
      val totalNewResults = taskResultsPairs.map(_._3.size).sum
      logger.info(s"[HELD_TASK_COMPLETION] Iteration $iterationCount completed in ${iterationDuration}ms: processed ${taskResultsPairs.size} tasks, generated $totalNewResults new results")
      
      if (iterationDuration > 60000) { // Warn if iteration takes more than 1 minute
        logger.warn(s"[HELD_TASK_COMPLETION] SLOW ITERATION: Iteration $iterationCount took ${iterationDuration}ms")
        logger.warn(s"[HELD_TASK_COMPLETION] Current result table size: ${resultTable.size} entries")
        logger.warn(s"[HELD_TASK_COMPLETION] Tasks with most results: ${taskResultsPairs.sortBy(-_._2.size).take(3).map(t => s"${t._1.fingerprint.sink.id}:${t._2.size}").mkString(", ")}")
      }
    }
    
    if (iterationCount >= maxIterations) {
      logger.error(s"[HELD_TASK_COMPLETION] CIRCUIT BREAKER: Terminated after $maxIterations iterations to prevent infinite loop")
      logger.error(s"[HELD_TASK_COMPLETION] Still have ${changed.count(_._2)} changed tasks when terminated")
    }
    
    val finalDedupStart = System.currentTimeMillis()
    deduplicateResultTable()
    val finalDedupDuration = System.currentTimeMillis() - finalDedupStart
    
    val totalDuration = System.currentTimeMillis() - startTime
    logger.info(s"[HELD_TASK_COMPLETION] Completed in ${totalDuration}ms ($iterationCount iterations, final dedup: ${finalDedupDuration}ms)")
    logger.info(s"[HELD_TASK_COMPLETION] Final result table size: ${resultTable.size} entries")
    
    if (totalDuration > 300000) { // Warn if total time exceeds 5 minutes
      logger.warn(s"[HELD_TASK_COMPLETION] PERFORMANCE WARNING: Held task completion took ${totalDuration}ms (${totalDuration/1000}s)")
    }
  }

  /** In essence, completing a held task simply means appending the path stored in the held task to all results that are
    * available for the held task in the table. In practice, we create one result for each task of the parent task's
    * `taskStack`, so that we do not only get a new result for the sink, but one for each of the parent nodes on the
    * way.
    */
  private def resultsForHeldTask(heldTask: ReachableByTask): List[(TaskFingerprint, TableEntry)] = {
    val startTime = System.currentTimeMillis()
    
    // Create a flat list of results by computing results for each
    // table entry and appending them.
    val result = resultTable.get(heldTask.fingerprint) match {
      case Some(results) =>
        logger.debug(s"[HELD_TASK_COMPLETION] Processing held task ${heldTask.fingerprint.sink.id} with ${results.size} existing results")
        
        // Log source information from initial path
        if (heldTask.initialPath.nonEmpty) {
          val sourcePath = heldTask.initialPath.head
          logger.debug(s"[HELD_TASK_COMPLETION] Held task source: ${sourcePath.node.getClass.getSimpleName}:${sourcePath.node.id}")
        }
        
        val processedResults = results
          .flatMap { r =>
            createResultsForHeldTaskAndTableResult(heldTask, r)
          }
          
        val duration = System.currentTimeMillis() - startTime
        if (duration > 10000) { // Warn if processing takes more than 10 seconds
          logger.warn(s"[HELD_TASK_COMPLETION] SLOW HELD TASK: Task ${heldTask.fingerprint.sink.id} took ${duration}ms to process ${results.size} results -> ${processedResults.size} outputs")
        }
        
        processedResults
      case None => 
        logger.debug(s"[HELD_TASK_COMPLETION] No results found for held task ${heldTask.fingerprint.sink.id}")
        List()
    }
    
    result
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
    val startTime = System.currentTimeMillis()
    val initialSize = resultTable.values.map(_.size).sum
    logger.debug(s"[HELD_TASK_COMPLETION] Starting result table deduplication: ${resultTable.size} keys, $initialSize total entries")
    
    resultTable.keys.foreach { key =>
      val results = resultTable(key)
      val dedupResults = deduplicateTableEntries(results)
      resultTable.put(key, dedupResults)
      
      if (results.size != dedupResults.size) {
        logger.debug(s"[HELD_TASK_COMPLETION] Deduplicated key ${key.sink.id}: ${results.size} -> ${dedupResults.size} entries")
      }
    }
    
    val finalSize = resultTable.values.map(_.size).sum
    val duration = System.currentTimeMillis() - startTime
    logger.debug(s"[HELD_TASK_COMPLETION] Result table deduplication completed in ${duration}ms: $initialSize -> $finalSize entries")
    
    if (duration > 30000) { // Warn if deduplication takes more than 30 seconds
      logger.warn(s"[HELD_TASK_COMPLETION] SLOW DEDUPLICATION: Result table deduplication took ${duration}ms")
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
    val inputSize = list.size
    
    if (inputSize == 0) {
      return List.empty
    }
    
    logger.debug(s"[HELD_TASK_COMPLETION] Starting deduplication of $inputSize table entries")
    
    // Performance warning for large collections
    if (inputSize > 10000) {
      logger.warn(s"[HELD_TASK_COMPLETION] LARGE COLLECTION WARNING: Deduplicating $inputSize table entries - this may be slow")
    }
    
    val groupByStartTime = System.currentTimeMillis()
    val groupedResults = list
      .groupBy { result =>
        val head = result.path.headOption.map(x => (x.node, x.callSiteStack, x.isOutputArg)).get
        val last = result.path.lastOption.map(x => (x.node, x.callSiteStack, x.isOutputArg)).get
        (head, last)
      }
    val groupByDuration = System.currentTimeMillis() - groupByStartTime
    
    logger.debug(s"[HELD_TASK_COMPLETION] GroupBy operation completed in ${groupByDuration}ms: $inputSize entries -> ${groupedResults.size} groups")
    
    if (groupByDuration > 30000) { // Warn if groupBy takes more than 30 seconds
      logger.warn(s"[HELD_TASK_COMPLETION] SLOW GROUPBY: GroupBy operation took ${groupByDuration}ms for $inputSize entries")
      
      // Log the largest groups for debugging
      val largestGroups = groupedResults.toSeq.sortBy(-_._2.size).take(5)
      largestGroups.foreach { case ((head, last), entries) =>
        logger.warn(s"[HELD_TASK_COMPLETION] Large group: ${head._1.id} -> ${last._1.id} with ${entries.size} entries")
      }
    }
    
    val mapStartTime = System.currentTimeMillis()
    val result = groupedResults
      .map { case (_, list) =>
        val sortStartTime = System.currentTimeMillis()
        val lenIdPathPairs = list.map(x => (x.path.length, x))
        val withMaxLength = (lenIdPathPairs.sortBy(_._1).reverse match {
          case Nil    => Nil
          case h :: t => h :: t.takeWhile(y => y._1 == h._1)
        }).map(_._2)
        val sortDuration = System.currentTimeMillis() - sortStartTime
        
        // Warn about expensive sorting operations
        if (sortDuration > 5000 && list.size > 1000) {
          logger.warn(s"[HELD_TASK_COMPLETION] SLOW SORT: Sorting ${list.size} entries took ${sortDuration}ms")
        }

        if (withMaxLength.length == 1) {
          withMaxLength.head
        } else {
          val tieBreakStartTime = System.currentTimeMillis()
          val selected = withMaxLength.minBy { x =>
            x.path
              .map(x => (x.node.id, x.callSiteStack.map(_.id), x.visible, x.isOutputArg, x.outEdgeLabel).toString)
              .mkString("-")
          }
          val tieBreakDuration = System.currentTimeMillis() - tieBreakStartTime
          
          if (tieBreakDuration > 5000) {
            logger.warn(s"[HELD_TASK_COMPLETION] SLOW TIE-BREAK: Tie-breaking ${withMaxLength.size} entries took ${tieBreakDuration}ms")
          }
          
          selected
        }
      }
      .toList
    val mapDuration = System.currentTimeMillis() - mapStartTime
    
    val totalDuration = System.currentTimeMillis() - startTime
    val outputSize = result.size
    val reductionRatio = if (inputSize > 0) (1.0 - outputSize.toDouble / inputSize) * 100 else 0.0
    
    logger.debug(s"[HELD_TASK_COMPLETION] Deduplication completed in ${totalDuration}ms: $inputSize -> $outputSize entries (${reductionRatio.formatted("%.1f")}% reduction)")
    logger.debug(s"[HELD_TASK_COMPLETION] Deduplication timing: groupBy=${groupByDuration}ms, map=${mapDuration}ms")
    
    if (totalDuration > 60000) { // Warn if total deduplication takes more than 1 minute
      logger.warn(s"[HELD_TASK_COMPLETION] SLOW DEDUPLICATION: Total deduplication took ${totalDuration}ms for $inputSize entries")
      logger.warn(s"[HELD_TASK_COMPLETION] Consider increasing EngineConfig limits or optimizing the query to reduce path explosion")
    }
    
    // Memory usage warning
    val estimatedMemoryMB = (inputSize * 1000) / (1024 * 1024) // Rough estimate
    if (estimatedMemoryMB > 100) {
      logger.warn(s"[HELD_TASK_COMPLETION] MEMORY WARNING: Processing ~${estimatedMemoryMB}MB of table entries")
    }
    
    result
  }

}
