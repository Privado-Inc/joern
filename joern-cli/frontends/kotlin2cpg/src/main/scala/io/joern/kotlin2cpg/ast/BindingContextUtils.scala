package io.joern.kotlin2cpg.ast

import org.jetbrains.kotlin.com.google.common.collect.ImmutableMap
import org.jetbrains.kotlin.descriptors.*
import org.jetbrains.kotlin.descriptors.annotations.AnnotationDescriptor
import org.jetbrains.kotlin.psi.*
import org.jetbrains.kotlin.resolve.calls.model.ResolvedCall
import org.jetbrains.kotlin.resolve.diagnostics.Diagnostics
import org.jetbrains.kotlin.resolve.{BindingContext, BindingTrace, BindingTraceContext}
import org.jetbrains.kotlin.types.KotlinType
import org.jetbrains.kotlin.types.error.ErrorType
import org.jetbrains.kotlin.util.slicedMap.{MutableSlicedMap, ReadOnlySlice, SlicedMapImpl, WritableSlice}

import _root_.java.util
import scala.jdk.CollectionConverters.*

class BindingContextUtils() {

  private val bindingTraceContext: BindingTraceContext = BindingTraceContext(true)

  def absorbBindingContext(getBindingContext: BindingContext): Unit = {
    getBindingContext.addOwnDataTo(bindingTraceContext, true)
  }

  def getBindingContext: BindingContext = {
    bindingTraceContext.getBindingContext
  }

  def getClassDesc(classAst: KtClassOrObject): Option[ClassDescriptor] = {
    Option(getBindingContext.get(BindingContext.CLASS, classAst))
  }

  def getFunctionDesc(functionAst: KtNamedFunction): Option[FunctionDescriptor] = {
    Option(getBindingContext.get(BindingContext.FUNCTION, functionAst))
  }

  def getFunctionDesc(functionLiteralAst: KtFunctionLiteral): Option[FunctionDescriptor] = {
    Option(getBindingContext.get(BindingContext.FUNCTION, functionLiteralAst))
  }

  def getConstructorDesc(constructorAst: KtConstructor[?]): Option[ConstructorDescriptor] = {
    Option(getBindingContext.get(BindingContext.CONSTRUCTOR, constructorAst))
  }

  def getCalledFunctionDesc(destructuringAst: KtDestructuringDeclarationEntry): Option[FunctionDescriptor] = {
    val resolvedCall = Option(getBindingContext.get(BindingContext.COMPONENT_RESOLVED_CALL, destructuringAst))
    resolvedCall.map(_.getResultingDescriptor)
  }

  def getCalledFunctionDesc(expressionAst: KtExpression): Option[FunctionDescriptor] = {
    val call         = Option(getBindingContext.get(BindingContext.CALL, expressionAst))
    val resolvedCall = call.flatMap(call => Option(getBindingContext.get(BindingContext.RESOLVED_CALL, call)))
    resolvedCall.map(_.getResultingDescriptor).collect { case functionDesc: FunctionDescriptor => functionDesc }
  }

  def getAmbiguousCalledFunctionDescs(expression: KtExpression): collection.Seq[FunctionDescriptor] = {
    val descriptors = getBindingContext.get(BindingContext.AMBIGUOUS_REFERENCE_TARGET, expression)
    if (descriptors == null) { return Seq.empty }
    descriptors.asScala.toSeq.collect { case functionDescriptor: FunctionDescriptor => functionDescriptor }
  }

  def getResolvedCallDesc(expr: KtExpression): Option[ResolvedCall[?]] = {
    val call         = Option(getBindingContext.get(BindingContext.CALL, expr))
    val resolvedCall = call.flatMap(call => Option(getBindingContext.get(BindingContext.RESOLVED_CALL, call)))
    resolvedCall
  }

  def getVariableDesc(param: KtParameter): Option[VariableDescriptor] = {
    Option(getBindingContext.get(BindingContext.VALUE_PARAMETER, param))
  }

  def getVariableDesc(entry: KtDestructuringDeclarationEntry): Option[VariableDescriptor] = {
    Option(getBindingContext.get(BindingContext.VARIABLE, entry))
  }

  def getVariableDesc(property: KtProperty): Option[VariableDescriptor] = {
    Option(getBindingContext.get(BindingContext.VARIABLE, property))
  }

  def getTypeAliasDesc(typeAlias: KtTypeAlias): Option[TypeAliasDescriptor] = {
    Option(getBindingContext.get(BindingContext.TYPE_ALIAS, typeAlias))
  }

  def getAnnotationDesc(entry: KtAnnotationEntry): Option[AnnotationDescriptor] = {
    Option(getBindingContext.get(BindingContext.ANNOTATION, entry))
  }

  def getDeclDesc(nameRefExpr: KtReferenceExpression): Option[DeclarationDescriptor] = {
    Option(getBindingContext.get(BindingContext.REFERENCE_TARGET, nameRefExpr))
  }

  def getExprType(expr: KtExpression): Option[KotlinType] = {
    Option(getBindingContext.get(BindingContext.EXPRESSION_TYPE_INFO, expr))
      .flatMap(typeInfo => Option(typeInfo.getType))
  }

  def getTypeRefType(typeRef: KtTypeReference): Option[KotlinType] = {
    Option(getBindingContext.get(BindingContext.TYPE, typeRef)) match {
      case Some(_: ErrorType) => None
      case other              => other
    }
  }
}
