package io.joern.kotlin2cpg.types

import better.files.*
import io.joern.kotlin2cpg.Config
import io.joern.x2cpg.SourceFiles.toRelativePath

object ContentSourcesPicker {

  /** This function checks if the given directory contains a Gradle build file. It looks for both "build.gradle" and
    * "build.gradle.kts" files.
    *
    * @param dir
    *   The directory to check.
    * @return
    *   True if the directory contains a Gradle build file, false otherwise.
    */
  private def hasGradleBuildFile(dir: File): Boolean = {
    (dir / "build.gradle").exists || (dir / "build.gradle.kts").exists
  }

  /** This function processes a child directory and returns a tuple containing a sequence of ModuleInfo objects and a
    * sequence of source file directories. It checks if the child directory contains a Gradle build file or Kotlin
    * source files.
    *
    * @param childDir
    *   The child directory to process.
    * @param config
    *   The configuration object containing settings for processing.
    * @return
    *   A tuple containing a sequence of ModuleInfo objects and a sequence of all .kt files.
    */
  private def processChildFolder(childDir: File, config: Config): (Seq[ModuleInfo], Seq[String]) = {
    var modules: Seq[ModuleInfo] = Seq.empty
    val sourceDirs: Seq[String] = hasGradleBuildFile(childDir) match {
      case true =>
        modules = getModuleWiseSegregation(childDir.pathAsString, config)
        Seq.empty
      case false =>
        childDir.list.exists { f => f.hasExtension && f.pathAsString.endsWith(".kt") } match {
          case true =>
            // If there are .kt files in the childDir, we add it to the list of source file directories.
            // We assume there is no possibility of any sub module under this directory
            childDir.listRecursively
              .filter(f => f.hasExtension && f.pathAsString.endsWith(".kt"))
              .filterNot(file => config.ignoredFilesRegex.matches(toRelativePath(file.pathAsString, config.inputPath)))
              .toSeq
              .sortBy(_.parent.pathAsString)
              .map(_.pathAsString) // Sort by parent directory path
          case false =>
            // If there are no .kt files in the childDir, we process all its child directories
            childDir.list
              .filter(_.isDirectory)
              .flatMap { d =>
                val (submodules, sourceDirs) = processChildFolder(d, config)
                modules = modules ++ submodules
                sourceDirs
              }
              .toSeq
        }
    }
    (modules, sourceDirs)
  }

  /** This function segregates the modules in the given directory and returns a sequence of ModuleInfo objects. Each
    * ModuleInfo object contains the path to the module and a sequence of source file directories.
    *
    * @param rootDir
    *   The root directory to process.
    * @param config
    *   The configuration object containing settings for processing.
    * @return
    *   A sequence of ModuleInfo objects representing the modules found in the directory.
    */
  def getModuleWiseSegregation(rootDir: String, config: Config): Seq[ModuleInfo] = {
    val dir        = File(rootDir)
    val hasSubDirs = dir.list.exists(_.isDirectory)
    if (!hasSubDirs || !config.resolveTypes) {
      return Seq(ModuleInfo(rootDir, Seq(rootDir)))
    }
    var modules: Seq[ModuleInfo] = Seq.empty
    val sourceDirs = dir.list
      .filter(_.isDirectory)
      .flatMap { childDir =>
        val (submodules, sourceDirs) = processChildFolder(childDir, config)
        modules = modules ++ submodules
        sourceDirs
      }
      .toSeq
    val rootmodule = sourceDirs.size match {
      case 0 =>
        Seq.empty
      case _ =>
        Seq(ModuleInfo(rootDir, sourceDirs))
    }
    rootmodule ++ modules
  }

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
        val dirsPicked = f.list
          .filter(_.isDirectory)
          .filterNot { d =>
            d.listRecursively.filter(_.hasExtension).exists(_.pathAsString.endsWith(".kts"))
          }
          .toList
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

case class ModuleInfo(modulePathRoot: String, sourceFileDirs: Seq[String])
