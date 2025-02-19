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
package org.neo4j.cypher.internal.compiler.v3_0.planner.logical.steps

import org.neo4j.cypher.internal.compiler.v3_0.commands.QueryExpression
import org.neo4j.cypher.internal.compiler.v3_0.helpers.CollectionSupport
import org.neo4j.cypher.internal.compiler.v3_0.pipes.{LazyLabel, LazyType, LazyTypes, SortDescription}
import org.neo4j.cypher.internal.compiler.v3_0.planner._
import org.neo4j.cypher.internal.compiler.v3_0.planner.logical.LogicalPlanningContext
import org.neo4j.cypher.internal.compiler.v3_0.planner.logical.Metrics.CardinalityModel
import org.neo4j.cypher.internal.compiler.v3_0.planner.logical.plans.{Limit => LimitPlan, Skip => SkipPlan, _}
import org.neo4j.cypher.internal.frontend.v3_0.ast._
import org.neo4j.cypher.internal.frontend.v3_0.ast.functions.Length
import org.neo4j.cypher.internal.frontend.v3_0.symbols._
import org.neo4j.cypher.internal.frontend.v3_0.{InternalException, SemanticDirection, ast, symbols}


/*
 * The responsibility of this class is to produce the correct solved PlannerQuery when creating logical plans.
 * No other functionality or logic should live here - this is supposed to be a very simple class that does not need
 * much testing
 */
case class LogicalPlanProducer(cardinalityModel: CardinalityModel) extends CollectionSupport {
  def solvePredicate(plan: LogicalPlan, solved: Expression)(implicit context: LogicalPlanningContext) =
    plan.updateSolved(_.amendQueryGraph(_.addPredicates(solved)))

  def planAggregation(left: LogicalPlan, grouping: Map[String, Expression], aggregation: Map[String, Expression])
                     (implicit context: LogicalPlanningContext) = {
    val solved = left.solved.updateTailOrSelf(_.withHorizon(
      AggregatingQueryProjection(groupingKeys = grouping, aggregationExpressions = aggregation)
    ))
    Aggregation(left, grouping, aggregation)(solved)
  }

  def planAllNodesScan(idName: IdName, argumentIds: Set[IdName])(implicit context: LogicalPlanningContext) = {
    val solved = PlannerQuery(queryGraph = QueryGraph(argumentIds = argumentIds, patternNodes = Set(idName)))
    AllNodesScan(idName, argumentIds)(solved)
  }

  def planApply(left: LogicalPlan, right: LogicalPlan)(implicit context: LogicalPlanningContext) = {
    // We don't want to keep the arguments that this Apply is inserting on the RHS, so we remove them here.
    val rhsSolved: PlannerQuery = right.solved.updateTailOrSelf(_.amendQueryGraph(_.withArgumentIds(Set.empty)))
    val solved: PlannerQuery = left.solved ++ rhsSolved
    Apply(left, right)(solved = solved)
  }

  def planTailApply(left: LogicalPlan, right: LogicalPlan)(implicit context: LogicalPlanningContext) = {
    val solved = left.solved.updateTailOrSelf(_.withTail(right.solved))
    Apply(left, right)(solved = solved)
  }

  def planCartesianProduct(left: LogicalPlan, right: LogicalPlan)(implicit context: LogicalPlanningContext) = {
    val solved: PlannerQuery = left.solved ++ right.solved
    CartesianProduct(left, right)(solved)
  }

  def planDirectedRelationshipByIdSeek(idName: IdName,
                                       relIds: SeekableArgs,
                                       startNode: IdName,
                                       endNode: IdName,
                                       pattern: PatternRelationship,
                                       argumentIds: Set[IdName],
                                       solvedPredicates: Seq[Expression] = Seq.empty)
                                      (implicit context: LogicalPlanningContext) = {
    val solved = PlannerQuery(queryGraph = QueryGraph.empty
      .addPatternRelationship(pattern)
      .addPredicates(solvedPredicates: _*)
      .addArgumentIds(argumentIds.toSeq)
    )
    DirectedRelationshipByIdSeek(idName, relIds, startNode, endNode, argumentIds)(solved)
  }

  def planUndirectedRelationshipByIdSeek(idName: IdName,
                                         relIds: SeekableArgs,
                                         leftNode: IdName,
                                         rightNode: IdName,
                                         pattern: PatternRelationship,
                                         argumentIds: Set[IdName],
                                         solvedPredicates: Seq[Expression] = Seq.empty)
                                        (implicit context: LogicalPlanningContext) = {
    val solved = PlannerQuery(queryGraph = QueryGraph.empty
      .addPatternRelationship(pattern)
      .addPredicates(solvedPredicates: _*)
      .addArgumentIds(argumentIds.toSeq)
    )
    UndirectedRelationshipByIdSeek(idName, relIds, leftNode, rightNode, argumentIds)(solved)
  }

  def planSimpleExpand(left: LogicalPlan,
                       from: IdName,
                       dir: SemanticDirection,
                       to: IdName,
                       pattern: PatternRelationship,
                       mode: ExpansionMode)(implicit context: LogicalPlanningContext) = {
    val solved = left.solved.amendQueryGraph(_.addPatternRelationship(pattern))
    Expand(left, from, dir, pattern.types, to, pattern.name, mode)(solved)
  }

  def planVarExpand(left: LogicalPlan,
                    from: IdName,
                    dir: SemanticDirection,
                    to: IdName,
                    pattern: PatternRelationship,
                    predicates: Seq[(Variable, Expression)],
                    allPredicates: Seq[Expression],
                    mode: ExpansionMode)(implicit context: LogicalPlanningContext) = pattern.length match {
    case l: VarPatternLength =>
      val projectedDir = projectedDirection(pattern, from, dir)

      val solved = left.solved.amendQueryGraph(_
        .addPatternRelationship(pattern)
        .addPredicates(allPredicates: _*)
      )
      VarExpand(left, from, dir, projectedDir, pattern.types, to, pattern.name, l, mode, predicates)(solved)

    case _ => throw new InternalException("Expected a varlength path to be here")
  }

  def planHiddenSelection(predicates: Seq[Expression], left: LogicalPlan)(implicit context: LogicalPlanningContext) = {
    Selection(predicates, left)(left.solved)
  }

  def planNodeByIdSeek(idName: IdName, nodeIds: SeekableArgs,
                       solvedPredicates: Seq[Expression] = Seq.empty,
                       argumentIds: Set[IdName])(implicit context: LogicalPlanningContext) = {
    val solved = PlannerQuery(queryGraph = QueryGraph.empty
      .addPatternNodes(idName)
      .addPredicates(solvedPredicates: _*)
      .addArgumentIds(argumentIds.toSeq)
    )
    NodeByIdSeek(idName, nodeIds, argumentIds)(solved)
  }

  def planNodeByLabelScan(idName: IdName, label: LazyLabel, solvedPredicates: Seq[Expression],
                          solvedHint: Option[UsingScanHint] = None, argumentIds: Set[IdName])
                         (implicit context: LogicalPlanningContext) = {
    val solved = PlannerQuery(queryGraph = QueryGraph.empty
      .addPatternNodes(idName)
      .addPredicates(solvedPredicates: _*)
      .addHints(solvedHint)
      .addArgumentIds(argumentIds.toSeq)
    )
    NodeByLabelScan(idName, label, argumentIds)(solved)
  }

  def planNodeIndexSeek(idName: IdName,
                        label: ast.LabelToken,
                        propertyKey: ast.PropertyKeyToken,
                        valueExpr: QueryExpression[Expression],
                        solvedPredicates: Seq[Expression] = Seq.empty,
                        solvedHint: Option[UsingIndexHint] = None,
                        argumentIds: Set[IdName])(implicit context: LogicalPlanningContext) = {
    val solved = PlannerQuery(queryGraph = QueryGraph.empty
      .addPatternNodes(idName)
      .addPredicates(solvedPredicates: _*)
      .addHints(solvedHint)
      .addArgumentIds(argumentIds.toSeq)
    )
    NodeIndexSeek(idName, label, propertyKey, valueExpr, argumentIds)(solved)
  }

  def planNodeIndexScan(idName: IdName,
                        label: ast.LabelToken,
                        propertyKey: ast.PropertyKeyToken,
                        solvedPredicates: Seq[Expression] = Seq.empty,
                        solvedHint: Option[UsingIndexHint] = None,
                        argumentIds: Set[IdName])(implicit context: LogicalPlanningContext) = {
    val solved = PlannerQuery(queryGraph = QueryGraph.empty
      .addPatternNodes(idName)
      .addPredicates(solvedPredicates: _*)
      .addHints(solvedHint)
      .addArgumentIds(argumentIds.toSeq)
    )
    NodeIndexScan(idName, label, propertyKey, argumentIds)(solved)
  }

  def planLegacyHintSeek(idName: IdName, hint: LegacyIndexHint, argumentIds: Set[IdName])
                        (implicit context: LogicalPlanningContext) = {
    val patternNode = hint match {
      case n: NodeHint => Seq(IdName(n.variable.name))
      case _ => Seq.empty
    }
    val solved = PlannerQuery(queryGraph = QueryGraph.empty
      .addPatternNodes(patternNode: _*)
      .addHints(Some(hint))
      .addArgumentIds(argumentIds.toSeq)
    )
    LegacyIndexSeek(idName, hint, argumentIds)(solved)
  }

  def planNodeHashJoin(nodes: Set[IdName], left: LogicalPlan, right: LogicalPlan, hints: Set[UsingJoinHint])
                      (implicit context: LogicalPlanningContext) = {

    val plannerQuery = left.solved ++ right.solved
    val solved = plannerQuery.amendQueryGraph(_.addHints(hints))
    NodeHashJoin(nodes, left, right)(solved)
  }

  def planNodeUniqueIndexSeek(idName: IdName,
                              label: ast.LabelToken,
                              propertyKey: ast.PropertyKeyToken,
                              valueExpr: QueryExpression[Expression],
                              solvedPredicates: Seq[Expression] = Seq.empty,
                              solvedHint: Option[UsingIndexHint] = None,
                              argumentIds: Set[IdName])(implicit context: LogicalPlanningContext) = {
    val solved = PlannerQuery(queryGraph = QueryGraph.empty
      .addPatternNodes(idName)
      .addPredicates(solvedPredicates: _*)
      .addHints(solvedHint)
      .addArgumentIds(argumentIds.toSeq)
    )
    NodeUniqueIndexSeek(idName, label, propertyKey, valueExpr, argumentIds)(solved)
  }

  def planAssertSameNode(node: IdName, left: LogicalPlan, right :LogicalPlan)(implicit context: LogicalPlanningContext) = {
    val solved: PlannerQuery = left.solved ++ right.solved
    AssertSameNode(node, left, right)(solved)
  }

  def planOptionalExpand(left: LogicalPlan,
                         from: IdName,
                         dir: SemanticDirection,
                         to: IdName,
                         pattern: PatternRelationship,
                         mode: ExpansionMode = ExpandAll,
                         predicates: Seq[Expression] = Seq.empty,
                         solvedQueryGraph: QueryGraph)(implicit context: LogicalPlanningContext) = {
    val solved = left.solved.amendQueryGraph(_.withAddedOptionalMatch(solvedQueryGraph))
    OptionalExpand(left, from, dir, pattern.types, to, pattern.name, mode, predicates)(solved)
  }

  def planOptional(inputPlan: LogicalPlan, ids: Set[IdName])(implicit context: LogicalPlanningContext) = {
    val solved = PlannerQuery(queryGraph = QueryGraph.empty
      .withAddedOptionalMatch(inputPlan.solved.queryGraph)
      .withArgumentIds(ids)
    )
    Optional(inputPlan)(solved)
  }

  def planOuterHashJoin(nodes: Set[IdName], left: LogicalPlan, right: LogicalPlan)
                       (implicit context: LogicalPlanningContext) = {
    val solved = left.solved.amendQueryGraph(_.withAddedOptionalMatch(right.solved.queryGraph))
    OuterHashJoin(nodes, left, right)(solved)
  }

  def planSelection(predicates: Seq[Expression], left: LogicalPlan)(implicit context: LogicalPlanningContext) = {
    val solved = left.solved.updateTailOrSelf(_.amendQueryGraph(_.addPredicates(predicates: _*)))
    Selection(predicates, left)(solved)
  }

  def planSelectOrAntiSemiApply(outer: LogicalPlan, inner: LogicalPlan, expr: Expression)
                               (implicit context: LogicalPlanningContext) =
    SelectOrAntiSemiApply(outer, inner, expr)(outer.solved)

  def planLetSelectOrAntiSemiApply(outer: LogicalPlan, inner: LogicalPlan, id: IdName, expr: Expression)
                                  (implicit context: LogicalPlanningContext) =
    LetSelectOrAntiSemiApply(outer, inner, id, expr)(outer.solved)

  def planSelectOrSemiApply(outer: LogicalPlan, inner: LogicalPlan, expr: Expression)
                           (implicit context: LogicalPlanningContext) =
    SelectOrSemiApply(outer, inner, expr)(outer.solved)

  def planLetSelectOrSemiApply(outer: LogicalPlan, inner: LogicalPlan, id: IdName, expr: Expression)
                              (implicit context: LogicalPlanningContext) =
    LetSelectOrSemiApply(outer, inner, id, expr)(outer.solved)

  def planLetAntiSemiApply(left: LogicalPlan, right: LogicalPlan, id: IdName)
                          (implicit context: LogicalPlanningContext) =
    LetAntiSemiApply(left, right, id)(left.solved)

  def planLetSemiApply(left: LogicalPlan, right: LogicalPlan, id: IdName)
                      (implicit context: LogicalPlanningContext) =
    LetSemiApply(left, right, id)(left.solved)

  def planAntiSemiApply(left: LogicalPlan, right: LogicalPlan, predicate: PatternExpression, expr: Expression)
                       (implicit context: LogicalPlanningContext) = {
    val solved = left.solved.updateTailOrSelf(_.amendQueryGraph(_.addPredicates(expr)))
    AntiSemiApply(left, right)(solved)
  }

  def planSemiApply(left: LogicalPlan, right: LogicalPlan, predicate: Expression)
                   (implicit context: LogicalPlanningContext) = {
    val solved = left.solved.updateTailOrSelf(_.amendQueryGraph(_.addPredicates(predicate)))
    SemiApply(left, right)(solved)
  }

  def planQueryArgumentRow(queryGraph: QueryGraph)(implicit context: LogicalPlanningContext): LogicalPlan = {
    val patternNodes = queryGraph.argumentIds intersect queryGraph.patternNodes
    val patternRels = queryGraph.patternRelationships.filter(rel => queryGraph.argumentIds.contains(rel.name))
    val otherIds = queryGraph.argumentIds -- patternNodes
    planArgumentRow(patternNodes, patternRels, otherIds)
  }

  def planArgumentRowFrom(plan: LogicalPlan)(implicit context: LogicalPlanningContext): LogicalPlan = {
    val types: Map[String, CypherType] = plan.availableSymbols.map {
      case n if context.semanticTable.isNode(n.name) => n.name -> symbols.CTNode
      case r if context.semanticTable.isRelationship(r.name) => r.name -> symbols.CTRelationship
      case v => v.name -> symbols.CTAny
    }.toMap
    Argument(plan.availableSymbols)(plan.solved)(types)
  }

  def planArgumentRow(patternNodes: Set[IdName], patternRels: Set[PatternRelationship] = Set.empty, other: Set[IdName] = Set.empty)
                     (implicit context: LogicalPlanningContext): LogicalPlan = {
    val relIds = patternRels.map(_.name)
    val coveredIds = patternNodes ++ relIds ++ other
    val typeInfoSeq = patternNodes.toSeq.map((x: IdName) => x.name -> CTNode) ++
                      relIds.toSeq.map((x: IdName) => x.name -> CTRelationship) ++
                      other.toSeq.map((x: IdName) => x.name -> CTAny)
    val typeInfo = typeInfoSeq.toMap

    val solved = PlannerQuery(queryGraph =
      QueryGraph(
        argumentIds = coveredIds,
        patternNodes = patternNodes,
        patternRelationships = Set.empty
      ))

    Argument(coveredIds)(solved)(typeInfo)
  }

  def planSingleRow()(implicit context: LogicalPlanningContext) =
    SingleRow()(PlannerQuery.empty)

  def planEmptyProjection(inner: LogicalPlan)(implicit context: LogicalPlanningContext): LogicalPlan =
    EmptyResult(inner)(inner.solved)

  def planStarProjection(inner: LogicalPlan, expressions: Map[String, Expression])
                        (implicit context: LogicalPlanningContext) =
    inner.updateSolved(_.updateTailOrSelf(_.updateQueryProjection(_.withProjections(expressions))))

  def planRegularProjection(inner: LogicalPlan, expressions: Map[String, Expression])
                           (implicit context: LogicalPlanningContext) = {
    val solved: PlannerQuery = inner.solved.updateTailOrSelf(_.updateQueryProjection(_.withProjections(expressions)))
    Projection(inner, expressions)(solved)
  }

  def planCountStoreNodeAggregation(query: PlannerQuery, idName: IdName, label: Option[LazyLabel], argumentIds: Set[IdName])
                                   (implicit context: LogicalPlanningContext) = {
    val solved: PlannerQuery = PlannerQuery(query.queryGraph, query.updateGraph, query.horizon)
    NodeCountFromCountStore(idName, label, argumentIds)(solved)
  }

  def planCountStoreRelationshipAggregation(query: PlannerQuery, idName: IdName, startLabel: Option[LazyLabel],
                                            typeNames: LazyTypes, endLabel: Option[LazyLabel], bothDirections: Boolean,
                                            argumentIds: Set[IdName])
                                           (implicit context: LogicalPlanningContext) = {
    val solved: PlannerQuery = PlannerQuery(query.queryGraph, query.updateGraph, query.horizon)
    RelationshipCountFromCountStore(idName, startLabel, typeNames, endLabel, bothDirections, argumentIds)(solved)
  }

  def planSkip(inner: LogicalPlan, count: Expression)(implicit context: LogicalPlanningContext) = {
    val solved = inner.solved.updateTailOrSelf(_.updateQueryProjection(_.updateShuffle(_.withSkipExpression(count))))
    SkipPlan(inner, count)(solved)
  }

  def planUnwind(inner: LogicalPlan, name: IdName, expression: Expression)(implicit context: LogicalPlanningContext) = {
    val solved = inner.solved.updateTailOrSelf(_.withHorizon(UnwindProjection(name, expression)))
    UnwindCollection(inner, name, expression)(solved)
  }

  def planLimit(inner: LogicalPlan, count: Expression)(implicit context: LogicalPlanningContext) = {
    val solved = inner.solved.updateTailOrSelf(_.updateQueryProjection(_.updateShuffle(_.withLimitExpression(count))))
    LimitPlan(inner, count)(solved)
  }

  def planSort(inner: LogicalPlan, descriptions: Seq[SortDescription], items: Seq[ast.SortItem])
              (implicit context: LogicalPlanningContext) = {
    val solved = inner.solved.updateTailOrSelf(_.updateQueryProjection(_.updateShuffle(_.withSortItems(items))))
    Sort(inner, descriptions)(solved)
  }

  def planSortedLimit(inner: LogicalPlan, limit: Expression, items: Seq[ast.SortItem])
                     (implicit context: LogicalPlanningContext) = {
    val solved = inner.solved.updateTailOrSelf(_.updateQueryProjection(_.updateShuffle(
      _.withLimitExpression(limit)
        .withSortItems(items))))
    SortedLimit(inner, limit, items)(solved)
  }

  def planSortedSkipAndLimit(inner: LogicalPlan, skip: Expression, limit: Expression, items: Seq[ast.SortItem])
                            (implicit context: LogicalPlanningContext) = {
    val solvedBySortedLimit = inner.solved.updateTailOrSelf(
      _.updateQueryProjection(_.updateShuffle(_.withSkipExpression(skip)
                                               .withLimitExpression(limit)
                                               .withSortItems(items))
      ))
    val sortedLimit = SortedLimit(inner, ast.Add(limit, skip)(limit.position), items)(solvedBySortedLimit)

    planSkip(sortedLimit, skip)
  }

  def planShortestPath(inner: LogicalPlan, shortestPaths: ShortestPathPattern, predicates: Seq[Expression])
                      (implicit context: LogicalPlanningContext) = {
    val solved = inner.solved.amendQueryGraph(_.addShortestPath(shortestPaths).addPredicates(predicates: _*))
    FindShortestPaths(inner, shortestPaths, predicates)(solved)
  }

  def planEndpointProjection(inner: LogicalPlan, start: IdName, startInScope: Boolean, end: IdName, endInScope: Boolean, patternRel: PatternRelationship)
                            (implicit context: LogicalPlanningContext) = {
    val relTypes = patternRel.types.asNonEmptyOption
    val directed = patternRel.dir != SemanticDirection.BOTH
    val solved = inner.solved.amendQueryGraph(_.addPatternRelationship(patternRel))
    ProjectEndpoints(inner, patternRel.name,
      start, startInScope,
      end, endInScope,
      relTypes, directed, patternRel.length)(solved)
  }

  def planUnion(left: LogicalPlan, right: LogicalPlan)(implicit context: LogicalPlanningContext): LogicalPlan = {
    Union(left, right)(left.solved)
    /* TODO: This is not correct in any way.
     LogicalPlan.solved contains a PlannerQuery, but to represent a Union, we'd need a UnionQuery instead
     Not very important at the moment, but dirty.
     */
  }

  def planDistinct(left: LogicalPlan)(implicit context: LogicalPlanningContext): LogicalPlan = {
    val returnAll = QueryProjection.forIds(left.availableSymbols) map {
      case AliasedReturnItem(e, Variable(key)) => key -> e // This smells awful.
    }

    Aggregation(left, returnAll.toMap, Map.empty)(left.solved)
  }

  def planTriadicSelection(positivePredicate: Boolean, left: LogicalPlan, sourceId: IdName, seenId: IdName, targetId: IdName, right: LogicalPlan, predicate: Expression)
                         (implicit context: LogicalPlanningContext): LogicalPlan = {
    val solved = (left.solved ++ right.solved).updateTailOrSelf(_.amendQueryGraph(_.addPredicates(predicate)))
    TriadicSelection(positivePredicate, left, sourceId, seenId, targetId, right)(solved)
  }

  def planCreateNode(inner: LogicalPlan, pattern: CreateNodePattern)
                     (implicit context: LogicalPlanningContext): LogicalPlan = {

    val solved = inner.solved.amendUpdateGraph(_.addMutatingPatterns(pattern))

    CreateNode(inner, pattern.nodeName,
      pattern.labels.map(LazyLabel(_)(context.semanticTable)), pattern.properties)(solved)
  }

  def planMergeCreateNode(inner: LogicalPlan, pattern: CreateNodePattern)(implicit context: LogicalPlanningContext): LogicalPlan = {

    val solved = inner.solved.amendUpdateGraph(_.addMutatingPatterns(pattern))

    MergeCreateNode(inner, pattern.nodeName,
      pattern.labels.map(LazyLabel(_)(context.semanticTable)), pattern.properties)(solved)
  }

  def planCreateRelationship(inner: LogicalPlan, pattern: CreateRelationshipPattern)
                    (implicit context: LogicalPlanningContext): LogicalPlan = {

    val solved = inner.solved.amendUpdateGraph(_.addMutatingPatterns(pattern))

    CreateRelationship(inner, pattern.relName, pattern.startNode, LazyType(pattern.relType)(context.semanticTable),
      pattern.endNode, pattern.properties)(solved)
  }

  def planConditionalApply(lhs: LogicalPlan, rhs: LogicalPlan, idName: IdName)
                    (implicit context: LogicalPlanningContext): LogicalPlan = {
    val solved = lhs.solved ++ rhs.solved

    ConditionalApply(lhs, rhs, idName)(solved)
  }

  def planAntiConditionalApply(inner: LogicalPlan, outer: LogicalPlan, idName: IdName)
                          (implicit context: LogicalPlanningContext): LogicalPlan = {
    val solved = inner.solved ++ outer.solved

    AntiConditionalApply(inner, outer, idName)(solved)
  }

  def planDeleteNode(inner: LogicalPlan, delete: DeleteExpression)
                            (implicit context: LogicalPlanningContext): LogicalPlan = {

    val solved = inner.solved.amendUpdateGraph(_.addMutatingPatterns(delete))

    if (delete.forced) DetachDeleteNode(inner, delete.expression)(solved)
    else DeleteNode(inner, delete.expression)(solved)
  }

  def planDeleteRelationship(inner: LogicalPlan, delete: DeleteExpression)
                    (implicit context: LogicalPlanningContext): LogicalPlan = {

    val solved = inner.solved.amendUpdateGraph(_.addMutatingPatterns(delete))

    DeleteRelationship(inner, delete.expression)(solved)
  }

  def planDeletePath(inner: LogicalPlan, delete: DeleteExpression)
                            (implicit context: LogicalPlanningContext): LogicalPlan = {

    val solved = inner.solved.amendUpdateGraph(_.addMutatingPatterns(delete))

    if (delete.forced) DetachDeletePath(inner, delete.expression)(solved)
    else DeletePath(inner, delete.expression)(solved)
  }

  def planSetLabel(inner: LogicalPlan, pattern: SetLabelPattern)
                    (implicit context: LogicalPlanningContext): LogicalPlan = {

    val solved = inner.solved.amendUpdateGraph(_.addMutatingPatterns(pattern))

    SetLabels(inner, pattern.idName, pattern.labels.map(LazyLabel(_)(context.semanticTable)))(solved)
  }

  def planSetNodeProperty(inner: LogicalPlan, pattern: SetNodePropertyPattern)
                  (implicit context: LogicalPlanningContext): LogicalPlan = {

    val solved = inner.solved.amendUpdateGraph(_.addMutatingPatterns(pattern))

    SetNodeProperty(inner, pattern.idName, pattern.propertyKey, pattern.expression)(solved)
  }

  def planSetNodePropertiesFromMap(inner: LogicalPlan,
                                          pattern: SetNodePropertiesFromMapPattern)
                         (implicit context: LogicalPlanningContext): LogicalPlan = {

    val solved = inner.solved.amendUpdateGraph(_.addMutatingPatterns(pattern))

    SetNodePropertiesFromMap(inner, pattern.idName, pattern.expression, pattern.removeOtherProps)(solved)
  }

  def planSetRelationshipProperty(inner: LogicalPlan, pattern: SetRelationshipPropertyPattern)
                         (implicit context: LogicalPlanningContext): LogicalPlan = {

    val solved = inner.solved.amendUpdateGraph(_.addMutatingPatterns(pattern))

    SetRelationshipPropery(inner, pattern.idName, pattern.propertyKey, pattern.expression)(solved)
  }

  def planSetRelationshipPropertiesFromMap(inner: LogicalPlan,
                                                  pattern: SetRelationshipPropertiesFromMapPattern)
                                 (implicit context: LogicalPlanningContext): LogicalPlan = {

    val solved = inner.solved.amendUpdateGraph(_.addMutatingPatterns(pattern))

    SetRelationshipPropertiesFromMap(inner, pattern.idName, pattern.expression, pattern.removeOtherProps)(solved)
  }

  def planRemoveLabel(inner: LogicalPlan, pattern: RemoveLabelPattern)
                  (implicit context: LogicalPlanningContext): LogicalPlan = {

    val solved = inner.solved.amendUpdateGraph(_.addMutatingPatterns(pattern))

    RemoveLabels(inner, pattern.idName, pattern.labels.map(LazyLabel(_)(context.semanticTable)))(solved)
  }

  def planRepeatableRead(inner: LogicalPlan)
                     (implicit context: LogicalPlanningContext): LogicalPlan = {

    RepeatableRead(inner)(inner.solved)
  }

  def planEager(inner: LogicalPlan) = Eager(inner)(inner.solved)

  implicit def estimatePlannerQuery(plannerQuery: PlannerQuery)(implicit context: LogicalPlanningContext): PlannerQuery with CardinalityEstimation = {
    val cardinality = cardinalityModel(plannerQuery, context.input, context.semanticTable)
    CardinalityEstimation.lift(plannerQuery, cardinality)
  }

  def projectedDirection(pattern: PatternRelationship, from: IdName, dir: SemanticDirection): SemanticDirection = {
    if (dir == SemanticDirection.BOTH) {
      if (from == pattern.left)
        SemanticDirection.OUTGOING
      else
        SemanticDirection.INCOMING
    }
    else
      pattern.dir
  }
}
