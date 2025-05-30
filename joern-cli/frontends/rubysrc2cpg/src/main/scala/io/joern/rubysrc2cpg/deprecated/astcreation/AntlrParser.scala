package io.joern.rubysrc2cpg.deprecated.astcreation

import io.joern.rubysrc2cpg.deprecated.parser.{
  DeprecatedRubyLexer,
  DeprecatedRubyLexerPostProcessor,
  DeprecatedRubyParser
}
import org.antlr.v4.runtime.*
import org.antlr.v4.runtime.atn.ATN
import org.antlr.v4.runtime.dfa.DFA
import org.slf4j.LoggerFactory

import scala.util.Try

/** A consumable wrapper for the RubyParser class used to parse the given file and be disposed thereafter.
  * @param filename
  *   the file path to the file to be parsed.
  */
class AntlrParser(filename: String) {

  private val charStream           = CharStreams.fromFileName(filename)
  private val lexer                = new DeprecatedRubyLexer(charStream)
  private val tokenStream          = new CommonTokenStream(DeprecatedRubyLexerPostProcessor(lexer))
  val parser: DeprecatedRubyParser = new DeprecatedRubyParser(tokenStream)

  def parse(): Try[DeprecatedRubyParser.ProgramContext] = Try(parser.program())
}

/** A re-usable parser object that clears the ANTLR DFA-cache if it determines that the memory usage is becoming large.
  * Once this parser is closed, the whole cache is evicted.
  *
  * This is done in this way since clearing the cache after each file is inefficient, since the cache must be re-built
  * every time, but the cache can become unnecessarily large at times. The cache also does not evict itself at the end
  * of parsing.
  *
  * @param clearLimit
  *   the percentage of used heap to clear the DFA-cache on.
  */
class ResourceManagedParser(clearLimit: Double) extends AutoCloseable {

  private val logger                                 = LoggerFactory.getLogger(getClass)
  private val runtime                                = Runtime.getRuntime
  private var maybeDecisionToDFA: Option[Array[DFA]] = None
  private var maybeAtn: Option[ATN]                  = None

  def parse(filename: String): Try[DeprecatedRubyParser.ProgramContext] = {
    val antlrParser = AntlrParser(filename)
    val interp      = antlrParser.parser.getInterpreter
    // We need to grab a live instance in order to get the static variables as they are protected from static access
    maybeDecisionToDFA = Option(interp.decisionToDFA)
    maybeAtn = Option(interp.atn)
    val usedMemory = (runtime.totalMemory().toDouble - runtime.freeMemory().toDouble) / runtime.totalMemory.toDouble
    if (usedMemory >= clearLimit) {
      logger.info(s"Runtime memory consumption at $usedMemory, clearing ANTLR DFA cache")
      clearDFA()
    }
    antlrParser.parse()
  }

  /** Clears the shared DFA cache.
    */
  private def clearDFA(): Unit = if (maybeDecisionToDFA.isDefined && maybeAtn.isDefined) {
    val decisionToDFA = maybeDecisionToDFA.get
    val atn           = maybeAtn.get
    for (d <- decisionToDFA.indices) {
      decisionToDFA(d) = new DFA(atn.getDecisionState(d), d)
    }
  }

  override def close(): Unit = clearDFA()
}
