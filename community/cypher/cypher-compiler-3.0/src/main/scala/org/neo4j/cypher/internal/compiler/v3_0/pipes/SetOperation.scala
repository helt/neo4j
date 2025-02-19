/*
 * Copyright (c) 2002-2015 "Neo Technology,"
 * Network Engine for Objects in Lund AB [http://neotechnology.com]
 *
 * This file is part of Neo4j.
 *
 * Neo4j is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package org.neo4j.cypher.internal.compiler.v3_0.pipes

import org.neo4j.cypher.internal.compiler.v3_0.ExecutionContext
import org.neo4j.cypher.internal.compiler.v3_0.commands.expressions.Expression
import org.neo4j.cypher.internal.compiler.v3_0.helpers.{CastSupport, IsMap}
import org.neo4j.cypher.internal.compiler.v3_0.mutation.makeValueNeoSafe
import org.neo4j.cypher.internal.compiler.v3_0.planner.logical.plans.IdName
import org.neo4j.cypher.internal.compiler.v3_0.planner.{SetLabelPattern, SetMutatingPattern, SetNodePropertiesFromMapPattern, SetNodePropertyPattern, SetRelationshipPropertiesFromMapPattern, SetRelationshipPropertyPattern}
import org.neo4j.cypher.internal.compiler.v3_0.spi.{Operations, QueryContext}
import org.neo4j.cypher.internal.frontend.v3_0.{CypherTypeException, SemanticTable}
import org.neo4j.graphdb.{Node, PropertyContainer, Relationship}

import scala.collection.Map

sealed trait SetOperation {
  def set(executionContext: ExecutionContext, state: QueryState): Unit

  def name: String
}

object SetOperation {

  import org.neo4j.cypher.internal.compiler.v3_0.ast.convert.commands.ExpressionConverters._

  def toSetOperation(pattern: SetMutatingPattern)(implicit table: SemanticTable) = pattern match {
    case SetLabelPattern(IdName(name), labels) =>
      SetLabelsOperation(name, labels.map(LazyLabel(_)))
    case SetNodePropertyPattern(IdName(name), propKey, expression) =>
      SetNodePropertyOperation(name, LazyPropertyKey(propKey.name), toCommandExpression(expression))
    case SetNodePropertiesFromMapPattern(IdName(name), expression, removeOtherProps) =>
      SetNodePropertyFromMapOperation(name, toCommandExpression(expression), removeOtherProps)
    case SetRelationshipPropertyPattern(IdName(name), propKey, expression) =>
      SetRelationshipPropertyOperation(name, LazyPropertyKey(propKey.name), toCommandExpression(expression))
    case SetRelationshipPropertiesFromMapPattern(IdName(name), expression, removeOtherProps) =>
      SetRelationshipPropertyFromMapOperation(name, toCommandExpression(expression), removeOtherProps)
  }

  private[pipes] def setProperty[T <: PropertyContainer](context: ExecutionContext, state: QueryState,
                                                         ops: Operations[T],
                                                         itemId: Long, propertyKey: LazyPropertyKey,
                                                         expression: Expression) = {
    val queryContext = state.query
    val maybePropertyKey = propertyKey.id(queryContext).map(_.id) // if the key was already looked up
    val propertyId = maybePropertyKey
        .getOrElse(queryContext.getOrCreatePropertyKeyId(propertyKey.name)) // otherwise create it

    val value = makeValueNeoSafe(expression(context)(state))

    if (value == null) ops.removeProperty(itemId, propertyId)
    else ops.setProperty(itemId, propertyId, value)
  }

  private[pipes] def setPropertiesFromMap[T <: PropertyContainer](qtx: QueryContext, ops: Operations[T], itemId: Long,
                              map: Map[Int, Any], removeOtherProps: Boolean) {

    /*Set all map values on the property container*/
    for ((k, v) <- map) {
      if (v == null)
        ops.removeProperty(itemId, k)
      else
        ops.setProperty(itemId, k, makeValueNeoSafe(v))
    }

    val properties = ops.propertyKeyIds(itemId).filterNot(map.contains).toSet

    /*Remove all other properties from the property container ( SET n = {prop1: ...})*/
    if (removeOtherProps) {
      for (propertyKeyId <- properties) {
        ops.removeProperty(itemId, propertyKeyId)
      }
    }
  }

  private[pipes] def toMap(executionContext: ExecutionContext, state: QueryState, expression: Expression) = {
    /* Make the map expression look like a map */
    val qtx = state.query
    expression(executionContext)(state) match {
      case IsMap(createMapFrom) => propertyKeyMap(qtx, createMapFrom(qtx))
      case x => throw new CypherTypeException(s"Expected $expression to be a map, but it was :`$x`")
    }
  }

  private def propertyKeyMap(qtx: QueryContext, map: Map[String, Any]): Map[Int, Any] = {
    var builder = Map.newBuilder[Int, Any]

    for ((k, v) <- map) {
      if (v == null) {
        val optPropertyKeyId = qtx.getOptPropertyKeyId(k)
        if (optPropertyKeyId.isDefined) {
          builder += optPropertyKeyId.get -> v
        }
      }
      else {
        builder += qtx.getOrCreatePropertyKeyId(k) -> v
      }
    }

    builder.result()
  }
}

abstract class SetPropertyOperation[T <: PropertyContainer](itemName: String, propertyKey: LazyPropertyKey, expression: Expression) extends SetOperation {
  private val needsExclusiveLock = Expression.hasPropertyReadDependency(itemName, expression, propertyKey.name)

  override def set(executionContext: ExecutionContext, state: QueryState) = {
    val item = executionContext.get(itemName).get
    if (item != null) {
      val itemId = id(item)
      val ops = operations(state.query)
      if (needsExclusiveLock) ops.acquireExclusiveLock(itemId)

      try {
        SetOperation.setProperty(executionContext, state, ops, itemId, propertyKey,
          expression)
      } finally if (needsExclusiveLock) ops.releaseExclusiveLock(itemId)
    }
  }

  protected def id(item: Any): Long
  protected def operations(qtx: QueryContext): Operations[T]
}

case class SetNodePropertyOperation(nodeName: String, propertyKey: LazyPropertyKey,
                                    expression: Expression)
  extends SetPropertyOperation[Node](nodeName, propertyKey, expression) {

  override def name = "SetNodeProperty"

  override protected def id(item: Any) = CastSupport.castOrFail[Node](item).getId

  override protected def operations(qtx: QueryContext) = qtx.nodeOps
}

case class SetRelationshipPropertyOperation(relName: String, propertyKey: LazyPropertyKey,
                                            expression: Expression)
  extends SetPropertyOperation[Relationship](relName, propertyKey, expression) {

  override def name = "SetRelationshipProperty"

  override protected def id(item: Any) = CastSupport.castOrFail[Relationship](item).getId

  override protected def operations(qtx: QueryContext) = qtx.relationshipOps
}

abstract class SetPropertyFromMapOperation[T <: PropertyContainer](itemName: String, expression: Expression,
                                           removeOtherProps: Boolean) extends SetOperation {
  private val needsExclusiveLock = Expression.mapExpressionHasPropertyReadDependency(itemName, expression)

  override def set(executionContext: ExecutionContext, state: QueryState) = {
    val item = executionContext.get(itemName).get
    if (item != null) {
      val ops = operations(state.query)
      val itemId = id(item)
      if (needsExclusiveLock) ops.acquireExclusiveLock(itemId)

      try {
        val map = SetOperation.toMap(executionContext, state, expression)

        SetOperation.setPropertiesFromMap(state.query, ops, itemId, map, removeOtherProps)
      }  finally if (needsExclusiveLock) ops.releaseExclusiveLock(itemId)
    }
  }
  protected def id(item: Any): Long
  protected def operations(qtx: QueryContext): Operations[T]

}

case class SetNodePropertyFromMapOperation(nodeName: String, expression: Expression,
                                           removeOtherProps: Boolean)
  extends SetPropertyFromMapOperation[Node](nodeName, expression, removeOtherProps) {

  override def name = "SetNodePropertyFromMap"

  override protected def id(item: Any) = CastSupport.castOrFail[Node](item).getId

  override protected def operations(qtx: QueryContext) = qtx.nodeOps
}

case class SetRelationshipPropertyFromMapOperation(relName: String, expression: Expression,
                                                   removeOtherProps: Boolean)
  extends SetPropertyFromMapOperation[Relationship](relName, expression, removeOtherProps) {

  override def name = "SetRelationshipPropertyFromMap"

  override protected def id(item: Any) = CastSupport.castOrFail[Relationship](item).getId

  override protected def operations(qtx: QueryContext) = qtx.relationshipOps
}

case class SetLabelsOperation(nodeName: String, labels: Seq[LazyLabel]) extends SetOperation {

  override def set(executionContext: ExecutionContext, state: QueryState) = {
    val value = executionContext.get(nodeName).get
    if (value != null) {
      val nodeId = CastSupport.castOrFail[Node](value).getId
      val labelIds = labels.map(l => {
        val maybeLabelId = l.id(state.query).map(_.id)
        maybeLabelId getOrElse state.query.getOrCreateLabelId(l.name)
      })
      state.query.setLabelsOnNode(nodeId, labelIds.iterator)
    }
  }

  override def name = "SetLabels"
}


