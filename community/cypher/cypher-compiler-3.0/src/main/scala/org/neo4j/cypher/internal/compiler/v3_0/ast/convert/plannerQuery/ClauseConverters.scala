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
package org.neo4j.cypher.internal.compiler.v3_0.ast.convert.plannerQuery

import org.neo4j.cypher.internal.compiler.v3_0.ast.convert.plannerQuery.ExpressionConverters._
import org.neo4j.cypher.internal.compiler.v3_0.ast.convert.plannerQuery.PatternConverters._
import org.neo4j.cypher.internal.compiler.v3_0.planner._
import org.neo4j.cypher.internal.compiler.v3_0.planner.logical.plans.IdName
import org.neo4j.cypher.internal.frontend.v3_0.ast._
import org.neo4j.cypher.internal.frontend.v3_0.{InternalException, SemanticTable, SyntaxException}

import scala.collection.mutable

object ClauseConverters {

  def addToLogicalPlanInput(acc: PlannerQueryBuilder, clause: Clause): PlannerQueryBuilder = clause match {
    case c: Return => addReturnToLogicalPlanInput(acc, c)
    case c: Match => addMatchToLogicalPlanInput(acc, c)
    case c: With => addWithToLogicalPlanInput(acc, c)
    case c: Unwind => addUnwindToLogicalPlanInput(acc, c)
    case c: Start => addStartToLogicalPlanInput(acc, c)
    case c: Create => addCreateToLogicalPlanInput(acc, c)
    case c: SetClause => addSetClauseToLogicalPlanInput(acc, c)
    case c: Delete => addDeleteToLogicalPlanInput(acc, c)
    case c: Remove => addRemoveToLogicalPlanInput(acc, c)
    case c: Merge => addMergeToLogicalPlanInput(acc, c)

    case x => throw new CantHandleQueryException(s"$x is not supported by the new runtime yet")
  }

  private def asSelections(optWhere: Option[Where]) = Selections(optWhere.
    map(_.expression.asPredicates).
    getOrElse(Set.empty))

  private def asQueryShuffle(optOrderBy: Option[OrderBy]) = {
    val sortItems: Seq[SortItem] = optOrderBy.fold(Seq.empty[SortItem])(_.sortItems)
    QueryShuffle(sortItems, None, None)
  }

  private def asQueryProjection(distinct: Boolean, items: Seq[ReturnItem]): QueryProjection = {
    val (aggregatingItems: Seq[ReturnItem], groupingKeys: Seq[ReturnItem]) =
      items.partition(item => IsAggregate(item.expression))

    def turnIntoMap(x: Seq[ReturnItem]) = x.map(e => e.name -> e.expression).toMap

    val projectionMap = turnIntoMap(groupingKeys)
    val aggregationsMap = turnIntoMap(aggregatingItems)

    if (projectionMap.values.exists(containsAggregate))
      throw new InternalException("Grouping keys contains aggregation. AST has not been rewritten?")

    if (aggregationsMap.nonEmpty || distinct)
      AggregatingQueryProjection(groupingKeys = projectionMap, aggregationExpressions = aggregationsMap)
    else
      RegularQueryProjection(projections = projectionMap)
  }

  private def addReturnToLogicalPlanInput(acc: PlannerQueryBuilder,
                                          clause: Return): PlannerQueryBuilder = clause match {
    case Return(distinct, ri, optOrderBy, skip, limit) if !ri.includeExisting =>

      val shuffle = asQueryShuffle(optOrderBy).
        withSkip(skip).
        withLimit(limit)

      val projection = asQueryProjection(distinct, ri.items).
        withShuffle(shuffle)
      val returns = ri.items.collect {
        case AliasedReturnItem(_, variable) => IdName.fromVariable(variable)
      }
      acc.
        withHorizon(projection).
        withReturns(returns)
    case _ =>
      throw new InternalException("AST needs to be rewritten before it can be used for planning. Got: " + clause)
  }

  private def addSetClauseToLogicalPlanInput(acc: PlannerQueryBuilder, clause: SetClause): PlannerQueryBuilder =
    clause.items.foldLeft(acc) {

      case (builder, item) =>
        builder.amendUpdateGraph(_.addMutatingPatterns(toSetPattern(acc.semanticTable)(item)))
    }

  private def addCreateToLogicalPlanInput(acc: PlannerQueryBuilder, clause: Create): PlannerQueryBuilder = {
    clause.pattern.patternParts.foldLeft(acc) {
      //CREATE (n :L1:L2 {prop: 42})
      case (builder, EveryPath(NodePattern(Some(id), labels, props))) =>
        builder
          .amendUpdateGraph(ug => ug.addMutatingPatterns(CreateNodePattern(IdName.fromVariable(id), labels, props)))

      //CREATE (n)-[r: R]->(m)
      case (builder, EveryPath(pattern: RelationshipChain)) =>

        val (nodes, rels) = allCreatePatterns(pattern)
        //remove duplicates from loops, (a:L)-[:ER1]->(a)
        val dedupedNodes = dedup(nodes)

        //create nodes that are not already matched or created
        val nodesToCreate = dedupedNodes.filterNot(pattern => builder.allSeenPatternNodes(pattern.nodeName))

        //we must check that we are not trying to set a pattern or label on any already created nodes
        val nodesCreatedBefore = dedupedNodes.filter(pattern => builder.allSeenPatternNodes(pattern.nodeName))
        nodesCreatedBefore.collectFirst {
          case c if c.labels.nonEmpty || c.properties.nonEmpty =>
            throw new SyntaxException(
              s"Can't create node ${c.nodeName.name} with labels or properties here. The variable is already declared in this context")
        }

        builder
          .amendUpdateGraph(ug => ug
            .addMutatingPatterns(nodesToCreate ++ rels: _*))

      case _ => throw new CantHandleQueryException(s"$clause is not yet supported")
    }
  }

  private def dedup(nodePatterns: Vector[CreateNodePattern]) = {
    val seen = mutable.Set.empty[IdName]
    val result = mutable.ListBuffer.empty[CreateNodePattern]
    nodePatterns.foreach { pattern =>
      if (!seen(pattern.nodeName)) result.append(pattern)
      else if (pattern.labels.nonEmpty || pattern.properties.nonEmpty) {
        //reused patterns must be pure variable
        throw new SyntaxException(s"Can't create node ${
          pattern.nodeName.name
        } with labels or properties here. The variable is already declared in this context")
      }
      seen.add(pattern.nodeName)
    }
    result.toVector
  }

  private def allCreatePatterns(element: PatternElement): (Vector[CreateNodePattern], Vector[CreateRelationshipPattern]) = element match {
    case NodePattern(None, _, _) => throw new InternalException("All nodes must be named at this instance")
    //CREATE ()
    case NodePattern(Some(variable), labels, props) =>
      (Vector(CreateNodePattern(IdName.fromVariable(variable), labels, props)), Vector.empty)

    //CREATE ()-[:R]->()
    case RelationshipChain(leftNode: NodePattern, rel, rightNode) =>
      val leftIdName = IdName.fromVariable(leftNode.variable.get)
      val rightIdName = IdName.fromVariable(rightNode.variable.get)


      //Semantic checking enforces types.size == 1
      val relType = rel.types.headOption.getOrElse(
        throw new InternalException("Expected single relationship type"))

      (Vector(
        CreateNodePattern(leftIdName, leftNode.labels, leftNode.properties),
        CreateNodePattern(rightIdName, rightNode.labels, rightNode.properties)
      ), Vector(CreateRelationshipPattern(IdName.fromVariable(rel.variable.get),
        leftIdName, relType, rightIdName, rel.properties, rel.direction)))

    //CREATE ()->[:R]->()-[:R]->...->()
    case RelationshipChain(left, rel, rightNode) =>
      val (nodes, rels) = allCreatePatterns(left)
      val rightIdName = IdName.fromVariable(rightNode.variable.get)

      (nodes :+
        CreateNodePattern(rightIdName, rightNode.labels, rightNode.properties)
        , rels :+
        CreateRelationshipPattern(IdName.fromVariable(rel.variable.get), nodes.last.nodeName, rel.types.head,
          rightIdName, rel.properties, rel.direction))
  }

  private def addDeleteToLogicalPlanInput(acc: PlannerQueryBuilder, clause: Delete): PlannerQueryBuilder = {
    acc.amendUpdateGraph(ug => ug.addMutatingPatterns(clause.expressions.map(DeleteExpression(_, clause.forced)): _*))
  }

  private def asReturnItems(current: QueryGraph, clause: ReturnItems): Seq[ReturnItem] =
    if (clause.includeExisting)
      QueryProjection.forIds(current.allCoveredIds) ++ clause.items
    else
      clause.items

  private def addMatchToLogicalPlanInput(acc: PlannerQueryBuilder, clause: Match): PlannerQueryBuilder = {
    val patternContent = clause.pattern.destructed

    val selections = asSelections(clause.where)

    if (clause.optional) {
      acc.
        amendQueryGraph { qg => qg.withAddedOptionalMatch(
          // When adding QueryGraphs for optional matches, we always start with a new one.
          // It's either all or nothing per match clause.
          QueryGraph(
            selections = selections,
            patternNodes = patternContent.nodeIds.toSet,
            patternRelationships = patternContent.rels.toSet,
            hints = clause.hints.toSet,
            shortestPathPatterns = patternContent.shortestPaths.toSet
          ))
        }
    } else {
      acc.amendQueryGraph {
        qg => qg.
          addSelections(selections).
          addPatternNodes(patternContent.nodeIds: _*).
          addPatternRelationships(patternContent.rels).
          addHints(clause.hints).
          addShortestPaths(patternContent.shortestPaths: _*)
      }
    }
  }

  private def toPropertyMap(expr: Option[Expression]): Map[PropertyKeyName, Expression] = expr match {
    case None => Map.empty
    case Some(MapExpression(items)) => items.toMap
    case e => throw new InternalException(s"Expected MapExpression, got $e")
  }

  private def toPropertySelection(identifier: Variable,  map:Map[PropertyKeyName, Expression]): Seq[Expression] = map.map {
    case (k, e) => In(Property(identifier, k)(k.position), Collection(Seq(e))(e.position))(identifier.position)
  }.toSeq

  private def toSetPattern(semanticTable: SemanticTable)(setItem: SetItem): SetMutatingPattern = setItem match {
    case SetLabelItem(id, labels) => SetLabelPattern(IdName.fromVariable(id), labels)

    case SetPropertyItem(Property(node: Variable, propertyKey), expr) if semanticTable.isNode(node) =>
      SetNodePropertyPattern(IdName.fromVariable(node), propertyKey, expr)

    case SetPropertyItem(Property(rel: Variable, propertyKey), expr) if semanticTable.isRelationship(rel) =>
      SetRelationshipPropertyPattern(IdName.fromVariable(rel), propertyKey, expr)

    case SetExactPropertiesFromMapItem(node, expression) if semanticTable.isNode(node) =>
      SetNodePropertiesFromMapPattern(IdName.fromVariable(node), expression, removeOtherProps = true)

    case SetExactPropertiesFromMapItem(rel, expression) if semanticTable.isRelationship(rel) =>
      SetRelationshipPropertiesFromMapPattern(IdName.fromVariable(rel), expression, removeOtherProps = true)

    case SetIncludingPropertiesFromMapItem(node, expression) if semanticTable.isNode(node) =>
      SetNodePropertiesFromMapPattern(IdName.fromVariable(node), expression, removeOtherProps = false)

    case SetIncludingPropertiesFromMapItem(rel, expression) if semanticTable.isRelationship(rel) =>
      SetRelationshipPropertiesFromMapPattern(IdName.fromVariable(rel), expression, removeOtherProps = false)
  }

  private def addMergeToLogicalPlanInput(acc: PlannerQueryBuilder, clause: Merge): PlannerQueryBuilder = {
    val onCreate = clause.actions.collect {
      case OnCreate(setClause) => setClause.items.map(toSetPattern(acc.semanticTable))
    }.flatten
    val onMatch = clause.actions.collect {
      case OnMatch(setClause) => setClause.items.map(toSetPattern(acc.semanticTable))
    }.flatten

    clause.pattern.patternParts.foldLeft(acc) {
      //MERGE (n :L1:L2 {prop: 42})
      case (builder, EveryPath(NodePattern(Some(id), labels, props))) =>
        val matchGraph = QueryGraph(
          patternNodes = Set(IdName.fromVariable(id)),
          selections = Selections.from(labels.map(l => HasLabels(id, Seq(l))(id.position)) ++ toPropertySelection(id,
            toPropertyMap(props)) :_*),
          argumentIds = acc.currentlyAvailableVariables
        )
      builder
          .amendUpdateGraph(ug =>
            ug.addMutatingPatterns(
              MergeNodePattern(CreateNodePattern(IdName.fromVariable(id), labels, props),
                matchGraph, onCreate, onMatch)))

      case _ => throw new CantHandleQueryException("not supported yet")
    }
  }

  private implicit def returnItemsToIdName(s: Seq[ReturnItem]): Set[IdName] =
    s.map(item => IdName(item.name)).toSet

  private def addWithToLogicalPlanInput(builder: PlannerQueryBuilder,
                                        clause: With): PlannerQueryBuilder = clause match {

    /*
    When encountering a WITH that is not an event horizon, and we have no optional matches in the current QueryGraph,
    we simply continue building on the current PlannerQuery. Our ASTRewriters rewrite queries in such a way that
    a lot of queries have these WITH clauses.

    Handles: ... WITH * [WHERE <predicate>] ...
     */
    case With(false, ri, None, None, None, where)
      if !builder.currentQueryGraph.hasOptionalPatterns
        && ri.items.forall(item => !containsAggregate(item.expression))
        && ri.items.forall {
        case item: AliasedReturnItem => item.expression == item.variable
        case x => throw new InternalException("This should have been rewritten to an AliasedReturnItem.")
      } && builder.readOnly =>
      val selections = asSelections(where)
      builder.
        amendQueryGraph(_.addSelections(selections))

    /*
    When encountering a WITH that is an event horizon, we introduce the horizon and start a new empty QueryGraph.

    Handles all other WITH clauses
     */
    case With(distinct, projection, orderBy, skip, limit, where) =>
      val selections = asSelections(where)
      val returnItems = asReturnItems(builder.currentQueryGraph, projection)

      val shuffle =
        asQueryShuffle(orderBy).
          withLimit(limit).
          withSkip(skip)

      val queryProjection =
        asQueryProjection(distinct, returnItems).
          withShuffle(shuffle)

      builder.
        withHorizon(queryProjection).
        withTail(PlannerQuery(QueryGraph(selections = selections)))

    case _ =>
      throw new InternalException("AST needs to be rewritten before it can be used for planning. Got: " + clause)
  }

  private def addUnwindToLogicalPlanInput(builder: PlannerQueryBuilder, clause: Unwind): PlannerQueryBuilder =
    builder.
      withHorizon(
        UnwindProjection(
          variable = IdName(clause.variable.name),
          exp = clause.expression)
      ).
      withTail(PlannerQuery.empty)

  private def addStartToLogicalPlanInput(builder: PlannerQueryBuilder, clause: Start): PlannerQueryBuilder = {
    builder.amendQueryGraph { qg =>
      val items = clause.items.map {
        case hints: LegacyIndexHint => Right(hints)
        case item => Left(item)
      }

      val hints = items.collect { case Right(hint) => hint }
      val nonHints = items.collect { case Left(item) => item }

      if (nonHints.nonEmpty) {
        // all other start queries is delegated to legacy planner
        throw new CantHandleQueryException()
      }

      val nodeIds = hints.collect { case n: NodeHint => IdName(n.variable.name) }

      val selections = asSelections(clause.where)

      qg.addPatternNodes(nodeIds: _*)
        .addSelections(selections)
        .addHints(hints)
    }
  }

    def addRemoveToLogicalPlanInput(acc: PlannerQueryBuilder, clause: Remove): PlannerQueryBuilder = {
      clause.items.foldLeft(acc) {
        // REMOVE n:Foo
        case (builder, RemoveLabelItem(identifier, labelNames)) =>
          builder.amendUpdateGraph(ug => ug.addMutatingPatterns(RemoveLabelPattern(IdName.fromVariable(identifier), labelNames)))

        case (builder, other) =>
          throw new CantHandleQueryException(s"REMOVE $other not supported in cost planner yet")
      }
    }
}
