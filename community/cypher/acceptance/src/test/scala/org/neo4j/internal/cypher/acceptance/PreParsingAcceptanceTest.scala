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
package org.neo4j.internal.cypher.acceptance

import org.neo4j.cypher.internal.compatibility.CompatibilityPlanDescriptionFor3_0
import org.neo4j.cypher.internal.compiler.v3_0._
import org.neo4j.cypher.{ExecutionEngineFunSuite, ExtendedExecutionResult}
import org.scalatest.matchers.{MatchResult, Matcher}

class PreParsingAcceptanceTest extends ExecutionEngineFunSuite {

  test("specifying no planner should provide IDP") {
    val query = "PROFILE RETURN 1"

    eengine.execute(query) should havePlanner(IDPPlannerName)
  }

  test("specifying cost planner should provide IDP") {
    val query = "PROFILE CYPHER planner=cost RETURN 1"

    eengine.execute(query) should havePlanner(IDPPlannerName)
  }

  test("specifying idp planner should provide IDP") {
    val query = "PROFILE CYPHER planner=idp RETURN 1"

    eengine.execute(query) should havePlanner(IDPPlannerName)
  }

  test("specifying greedy planner should provide greedy") {
    val query = "PROFILE CYPHER planner=greedy RETURN 1"

    eengine.execute(query) should havePlanner(GreedyPlannerName)
  }

  test("specifying dp planner should provide DP") {
    val query = "PROFILE CYPHER planner=dp RETURN 1"

    eengine.execute(query) should havePlanner(DPPlannerName)
  }

  test("specifying rule planner should provide RULE") {
    val query = "PROFILE CYPHER planner=rule RETURN 1"

    eengine.execute(query) should havePlanner(RulePlannerName)
  }

  test("specifying cost planner should provide IDP using old syntax") {
    val query = "PROFILE CYPHER planner=cost RETURN 1"

    eengine.execute(query) should havePlanner(IDPPlannerName)
  }

  test("specifying idp planner should provide IDP using old syntax") {
    val query = "PROFILE CYPHER planner=idp RETURN 1"

    eengine.execute(query) should havePlanner(IDPPlannerName)
  }

  test("specifying greedy planner should provide greedy using old syntax") {
    val query = "PROFILE CYPHER planner=greedy RETURN 1"

    eengine.execute(query) should havePlanner(GreedyPlannerName)
  }

  test("specifying dp planner should provide DP using old syntax") {
    val query = "PROFILE CYPHER planner=dp RETURN 1"

    eengine.execute(query) should havePlanner(DPPlannerName)
  }

  test("specifying rule planner should provide RULE using old syntax") {
    val query = "PROFILE CYPHER planner=rule RETURN 1"

    eengine.execute(query) should havePlanner(RulePlannerName)
  }

  private def havePlanner(expected: PlannerName): Matcher[ExtendedExecutionResult] = new Matcher[ExtendedExecutionResult] {
    override def apply(result: ExtendedExecutionResult): MatchResult = {
      // exhaust the iterator so we can collect the plan description
      result.length
      result.executionPlanDescription() match {
        case CompatibilityPlanDescriptionFor3_0(_, _, actual, _) =>
          MatchResult(
            matches = actual == expected,
            rawFailureMessage = s"PlannerName should be $expected, but was $actual",
            rawNegatedFailureMessage = s"PlannerName should not be $actual"
          )
        case planDesc =>
          MatchResult(
            matches = false,
            rawFailureMessage = s"Plan description should be of type CompatibilityPlanDescriptionFor2_3, but was ${planDesc.getClass.getSimpleName}",
            rawNegatedFailureMessage = s"Plan description should be of type CompatibilityPlanDescriptionFor2_3, but was ${planDesc.getClass.getSimpleName}"
          )
      }
    }
  }
}
