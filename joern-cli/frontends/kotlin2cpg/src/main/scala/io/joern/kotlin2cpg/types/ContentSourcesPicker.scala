package io.joern.kotlin2cpg.types

import better.files.File
import io.joern.kotlin2cpg.Config
import io.joern.kotlin2cpg.DefaultContentRootJarPath
import io.joern.x2cpg.SourceFiles.toRelativePath

object ContentSourcesPicker {

  // In the following directory structure:
  //  ____________________
  //  | dir1
  //  |   -> build.gradle.kts
  //  |   -> dir2
  //  |      -> build.gradle.kts
  //  |      -> dir3
  //  |        -> source1.kt
  //  |        -> source2.kt
  //  |-------------------
  //  The list of paths which are acceptable for the current version of the Kotlin compiler API is:
  //  `Seq("dir1/dir2/dir3")` and nothing else.

  def dirsForRoot(rootDir: String, config: Config): Seq[String] = {
    val dir        = File(rootDir)
    val hasSubDirs = dir.list.exists(_.isDirectory)
    if (!hasSubDirs || !config.resolveTypes) {
      return Seq(rootDir)
    }
    val dirPaths = dir.listRecursively
      .filter(_.isDirectory)
      .flatMap { f =>
        val hasKtsFile = f.listRecursively.exists { f => f.hasExtension && f.pathAsString.endsWith(".kts") }
        val dirsPicked = f.list.filter(_.isDirectory).filterNot { d =>
          d.listRecursively.filter(_.hasExtension).exists(_.pathAsString.endsWith(".kts"))
        }
        if (hasKtsFile) Some(dirsPicked.map(_.pathAsString))
        else Some(Seq(f.pathAsString))
      }
      .flatten
      .toSeq
    dirPaths.filterNot(path =>
      val t = toRelativePath(path, config.inputPath)
      config.ignoredFilesRegex.matches(toRelativePath(path, config.inputPath))
    )
  }
}
