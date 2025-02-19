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
package org.neo4j.cypher.internal.helpers

import org.neo4j.cypher.InternalException
import org.neo4j.cypher.internal.compiler.v2_3
import org.neo4j.cypher.internal.compiler.v2_3.CompilationPhaseTracer.CompilationPhaseEvent
import org.neo4j.cypher.internal.compiler.v2_3.{CypherCompilerConfiguration => CypherCompilerConfiguration2_3, InternalNotificationLogger => InternalNotificationLogger2_3}
import org.neo4j.cypher.internal.compiler.v3_0.{CompilationPhaseTracer, CypherCompilerConfiguration, InternalNotificationLogger, RecordingNotificationLogger, devNullLogger}
import org.neo4j.cypher.internal.frontend.v2_3.notification.{InternalNotification => InternalNotification2_3}
import org.neo4j.cypher.internal.frontend.v2_3.{InputPosition => InputPosition2_3}
import org.neo4j.cypher.internal.frontend.v3_0.InputPosition
import org.neo4j.cypher.internal.frontend.v3_0.notification.{CartesianProductNotification, EagerLoadCsvNotification, IndexHintUnfulfillableNotification, IndexLookupUnfulfillableNotification, InternalNotification, JoinHintUnfulfillableNotification, JoinHintUnsupportedNotification, LargeLabelWithLoadCsvNotification, LengthOnNonPathNotification, MissingLabelNotification, MissingPropertyNameNotification, MissingRelTypeNotification, PlannerUnsupportedNotification, RuntimeUnsupportedNotification, UnboundedShortestPathNotification}
import org.neo4j.cypher.internal.frontend.{v2_3 => frontend2_3}

/**
 * Contains necessary wrappers for supporting 2.3 in 3.0
 */
object wrappersFor2_3 {
  def as2_3(config: CypherCompilerConfiguration)= CypherCompilerConfiguration2_3(config.queryCacheSize, config.statsDivergenceThreshold, config.queryPlanTTL, config.useErrorsOverWarnings, config.nonIndexedLabelWarningThreshold)

  /*
     *This is awful but needed until 2.3 is updated no to send in the tracer here
     */
  def as2_3(tracer: CompilationPhaseTracer): v2_3.CompilationPhaseTracer = {
    new v2_3.CompilationPhaseTracer {
      override def beginPhase(phase: v2_3.CompilationPhaseTracer.CompilationPhase) = {
        val wrappedPhase =
          if (phase == v2_3.CompilationPhaseTracer.CompilationPhase.AST_REWRITE)
            org.neo4j.cypher.internal.compiler.v3_0.CompilationPhaseTracer.CompilationPhase.AST_REWRITE
          else if (phase == v2_3.CompilationPhaseTracer.CompilationPhase
            .CODE_GENERATION)
            org.neo4j.cypher.internal.compiler.v3_0.CompilationPhaseTracer.CompilationPhase.CODE_GENERATION
          else if (phase == v2_3.CompilationPhaseTracer.CompilationPhase
            .LOGICAL_PLANNING)
            org.neo4j.cypher.internal.compiler.v3_0.CompilationPhaseTracer.CompilationPhase.LOGICAL_PLANNING
          else if (phase == v2_3.CompilationPhaseTracer.CompilationPhase.PARSING)
            org.neo4j.cypher.internal.compiler.v3_0.CompilationPhaseTracer.CompilationPhase.PARSING
          else if (phase == v2_3.CompilationPhaseTracer.CompilationPhase
            .PIPE_BUILDING)
            org.neo4j.cypher.internal.compiler.v3_0.CompilationPhaseTracer.CompilationPhase.PIPE_BUILDING
          else if (phase == v2_3.CompilationPhaseTracer.CompilationPhase
            .SEMANTIC_CHECK)
            org.neo4j.cypher.internal.compiler.v3_0.CompilationPhaseTracer.CompilationPhase.SEMANTIC_CHECK
          else throw new InternalException(s"Cannot handle $phase in 2.3")

        val wrappedEvent = tracer.beginPhase(wrappedPhase)

        new CompilationPhaseEvent {
          override def close(): Unit = wrappedEvent.close()
        }
      }
    }
  }

  def as2_3(logger: InternalNotificationLogger): InternalNotificationLogger2_3 = logger match {
    case _: devNullLogger.type => v2_3.devNullLogger
    case logger: RecordingNotificationLogger => {
      val logger23 = new v2_3.RecordingNotificationLogger
      logger.notifications.map(as2_3)foreach(logger23 += _)
      logger23
    }
  }

  def as2_3(notification: InternalNotification): InternalNotification2_3 = notification match {
    case CartesianProductNotification(pos, variables) =>
      frontend2_3.notification.CartesianProductNotification(as2_3(pos), variables)
    case LengthOnNonPathNotification(pos) =>
      frontend2_3.notification.LengthOnNonPathNotification(as2_3(pos))
    case PlannerUnsupportedNotification =>
      frontend2_3.notification.PlannerUnsupportedNotification
    case RuntimeUnsupportedNotification =>
      frontend2_3.notification.RuntimeUnsupportedNotification
    case IndexHintUnfulfillableNotification(label, propertyKey) =>
      frontend2_3.notification.IndexHintUnfulfillableNotification(label, propertyKey)
    case JoinHintUnfulfillableNotification(variables) =>
      frontend2_3.notification.JoinHintUnfulfillableNotification(variables)
    case JoinHintUnsupportedNotification(variables) =>
      frontend2_3.notification.JoinHintUnsupportedNotification(variables)
    case IndexLookupUnfulfillableNotification(labels) =>
      frontend2_3.notification.IndexLookupUnfulfillableNotification(labels)
    case EagerLoadCsvNotification =>
      frontend2_3.notification.EagerLoadCsvNotification
    case LargeLabelWithLoadCsvNotification =>
      frontend2_3.notification.LargeLabelWithLoadCsvNotification
    case MissingLabelNotification(pos, label) =>
      frontend2_3.notification.MissingLabelNotification(as2_3(pos), label)
    case MissingRelTypeNotification(pos, relType) =>
      frontend2_3.notification.MissingRelTypeNotification(as2_3(pos), relType)
    case MissingPropertyNameNotification(pos, name) =>
      frontend2_3.notification.MissingPropertyNameNotification(as2_3(pos), name)
    case UnboundedShortestPathNotification(pos)
      => frontend2_3.notification.UnboundedShortestPathNotification(as2_3(pos))
  }

  def as3_0(notification: InternalNotification2_3): InternalNotification = notification match {
    case frontend2_3.notification.CartesianProductNotification(pos, variables) =>
      CartesianProductNotification(as3_0(pos), variables)
    case frontend2_3.notification.LegacyPlannerNotification =>
      throw new InternalException("Syntax PLANNER COST/RULE no longer supported")
    case frontend2_3.notification.LengthOnNonPathNotification(pos) =>
      LengthOnNonPathNotification(as3_0(pos))
    case  frontend2_3.notification.PlannerUnsupportedNotification =>
     PlannerUnsupportedNotification
    case frontend2_3.notification.RuntimeUnsupportedNotification =>
      RuntimeUnsupportedNotification
    case frontend2_3.notification.IndexHintUnfulfillableNotification(label, propertyKey) =>
      IndexHintUnfulfillableNotification(label, propertyKey)
    case frontend2_3.notification.JoinHintUnfulfillableNotification(variables) =>
      JoinHintUnfulfillableNotification(variables)
    case frontend2_3.notification.JoinHintUnsupportedNotification(variables) =>
      JoinHintUnsupportedNotification(variables)
    case frontend2_3.notification.IndexLookupUnfulfillableNotification(labels) =>
      IndexLookupUnfulfillableNotification(labels)
    case frontend2_3.notification.EagerLoadCsvNotification =>
      EagerLoadCsvNotification
    case  frontend2_3.notification.LargeLabelWithLoadCsvNotification =>
     LargeLabelWithLoadCsvNotification
    case frontend2_3.notification.MissingLabelNotification(pos, label) =>
      MissingLabelNotification(as3_0(pos), label)
    case frontend2_3.notification.MissingRelTypeNotification(pos, relType) =>
      MissingRelTypeNotification(as3_0(pos), relType)
    case frontend2_3.notification.MissingPropertyNameNotification(pos, name) =>
      MissingPropertyNameNotification(as3_0(pos), name)
    case frontend2_3.notification.UnboundedShortestPathNotification(pos) =>
      UnboundedShortestPathNotification(as3_0(pos))
    case frontend2_3.notification.BareNodeSyntaxDeprecatedNotification(pos) =>
      throw new InternalException("Warnings for bare nodes are no longer supported, query should have already failed")

  }

  def as2_3(pos: InputPosition): InputPosition2_3 = InputPosition2_3(pos.offset, pos.line, pos.column)

  def as3_0(pos: InputPosition2_3): InputPosition = InputPosition(pos.offset, pos.line, pos.column)

}
