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
package org.neo4j.cypher.internal

import org.neo4j.cypher.internal.frontend.v3_0.InputPosition
import org.neo4j.cypher.internal.frontend.v3_0.notification.InternalNotification
import org.neo4j.cypher.{CypherPlanner, CypherRuntime, CypherUpdateStrategy, CypherVersion, InvalidArgumentException}

import scala.annotation.tailrec

object CypherStatementWithOptions {
  def apply(input: PreParsedStatement): CypherStatementWithOptions = {

    @tailrec
    def recurse(options: List[PreParserOption], version: Option[CypherVersion],
                planner: Option[CypherPlanner], runtime: Option[CypherRuntime],
                updateStrategy: Option[CypherUpdateStrategy],
                executionMode: Option[CypherExecutionMode]): CypherStatementWithOptions = options match {
      case Nil => CypherStatementWithOptions(input.statement, input.offset,
                                             version, planner, runtime, updateStrategy, executionMode)
      case option :: tail =>
        option match {
          case e: ExecutionModePreParserOption  =>
            val newExecutionMode = mergeOption(executionMode, CypherExecutionMode(e.name), "Can't specify multiple conflicting Cypher execution modes")
            recurse(tail, version, planner, runtime, updateStrategy, newExecutionMode)
          case VersionOption(v) =>
            val newVersion = mergeOption(version, CypherVersion(v), "Can't specify multiple conflicting Cypher versions")
            recurse(tail, newVersion, planner, runtime, updateStrategy, executionMode)
          case p: PlannerPreParserOption =>
            val newPlanner = mergeOption(planner, CypherPlanner(p.name), "Can't specify multiple conflicting Cypher planners")
            recurse(tail, version, newPlanner, runtime, updateStrategy, executionMode)
          case r: RuntimePreParserOption =>
            val newRuntime = mergeOption(runtime, CypherRuntime(r.name), "Can't specify multiple conflicting Cypher runtimes")
            recurse(tail, version, planner, newRuntime, updateStrategy, executionMode)
          case u: UpdateStrategyOption =>
            val newUpdateStrategy = mergeOption(updateStrategy, CypherUpdateStrategy(u.name), "Can't specify multiple conflicting update strategies")
            recurse(tail, version, planner, runtime, newUpdateStrategy, executionMode)
          case ConfigurationOptions(v, innerOptions) =>
            val newVersion = v.map(v => mergeOption(version, CypherVersion(v.version), "Can't specify multiple conflicting Cypher versions")).getOrElse(version)
            recurse(innerOptions.toList ++ tail, newVersion, planner, runtime, updateStrategy, executionMode)
        }
    }

    recurse(input.options.toList, None, None, None, None, None)
  }

  private def mergeOption[T](oldValue: Option[T], newValue: T, failureMessage: String): Option[T] = oldValue match {
    case Some(prevValue) if prevValue != newValue => throw new InvalidArgumentException(failureMessage)
    case _ =>  Some(newValue)
  }
}

case class CypherStatementWithOptions(statement: String, offset: InputPosition,
                                      version: Option[CypherVersion],
                                      planner: Option[CypherPlanner],
                                      runtime: Option[CypherRuntime],
                                      updateStrategy: Option[CypherUpdateStrategy],
                                      executionMode: Option[CypherExecutionMode])
