package io.joern.gosrc2cpg.astcreation

import io.joern.gosrc2cpg.parser.ParserAst.{GenDecl, ImportSpec}
import io.joern.gosrc2cpg.parser.ParserKeys
import io.joern.x2cpg.ValidationMode
import ujson.Value

trait InitialMainSrcProcessor(implicit withSchemaValidation: ValidationMode) { this: AstCreator =>
  def identifyUsedPackages(): Unit = {
    // NOTE: Here we are making assumption that largely a practice being followed to declare the imports at the top of the file.
    parserResult
      .json(ParserKeys.Decls)
      .arrOpt
      .getOrElse(List())
      .map(createParserNodeInfo)
      .foreach(decl => {
        decl.node match
          case GenDecl =>
            decl
              .json(ParserKeys.Specs)
              .arrOpt
              .getOrElse(List())
              .map(createParserNodeInfo)
              .foreach(spec => {
                spec.node match
                  case ImportSpec =>
                    val namespace = spec.json(ParserKeys.Path).obj(ParserKeys.Value).str.replaceAll("\"", "")
                    goMod
                      .recordUsedDependencies(namespace)
                    goGlobal.recordForThisNamespace(namespace)
                  case _ =>
                // Only process ImportSpec
              })
          case _ =>
        // Only process GenDecl
      })

  }
}
