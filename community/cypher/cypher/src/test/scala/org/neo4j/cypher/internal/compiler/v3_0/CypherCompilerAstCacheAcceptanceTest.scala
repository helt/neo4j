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
package org.neo4j.cypher.internal.compiler.v3_0

import java.util.concurrent.TimeUnit

import org.neo4j.cypher.GraphDatabaseTestSupport
import org.neo4j.cypher.internal.compatibility.{StringInfoLogger3_0, WrappedMonitors3_0}
import org.neo4j.cypher.internal.frontend.v3_0.ast.Statement
import org.neo4j.cypher.internal.compiler.v3_0.executionplan.ExecutionPlan
import org.neo4j.cypher.internal.compiler.v3_0.tracing.rewriters.RewriterStepSequencer
import org.neo4j.cypher.internal.frontend.v3_0.test_helpers.CypherFunSuite
import org.neo4j.cypher.internal.spi.v3_0.GeneratedQueryStructure
import org.neo4j.graphdb.config.Setting
import org.neo4j.graphdb.factory.GraphDatabaseSettings
import org.neo4j.helpers.{Clock, FrozenClock}
import org.neo4j.logging.{NullLog, Log, AssertableLogProvider}
import AssertableLogProvider.inLog

import scala.collection.Map

class CypherCompilerAstCacheAcceptanceTest extends CypherFunSuite with GraphDatabaseTestSupport {
  def createCompiler(queryCacheSize: Int = 128, statsDivergenceThreshold: Double = 0.5, queryPlanTTL: Long = 1000,
                     clock: Clock = Clock.SYSTEM_CLOCK, log: Log = NullLog.getInstance) =
    CypherCompilerFactory.costBasedCompiler(
      graph,
      CypherCompilerConfiguration(
        queryCacheSize,
        statsDivergenceThreshold,
        queryPlanTTL,
        useErrorsOverWarnings = false,
        nonIndexedLabelWarningThreshold = 10000L
      ),
      clock, GeneratedQueryStructure,
      new WrappedMonitors3_0(kernelMonitors),
      new StringInfoLogger3_0(log),
      plannerName = Some(GreedyPlannerName),
      runtimeName = Some(CompiledRuntimeName),
      updateStrategy = None,
      rewriterSequencer = RewriterStepSequencer.newValidating
     )

  case class CacheCounts(hits: Int = 0, misses: Int = 0, flushes: Int = 0, evicted: Int = 0) {
    override def toString = s"hits = $hits, misses = $misses, flushes = $flushes, evicted = $evicted"
  }

  class CacheCounter(var counts: CacheCounts = CacheCounts()) extends AstCacheMonitor {
    override def cacheHit(key: Statement) {
      counts = counts.copy(hits = counts.hits + 1)
    }

    override def cacheMiss(key: Statement) {
      counts = counts.copy(misses = counts.misses + 1)
    }

    override def cacheFlushDetected(justBeforeKey: CacheAccessor[Statement, ExecutionPlan]) {
      counts = counts.copy(flushes = counts.flushes + 1)
    }

    override def cacheDiscard(key: Statement): Unit = {
      counts = counts.copy(evicted = counts.evicted + 1)
    }
  }

  override def databaseConfig(): Map[Setting[_],String] = Map(GraphDatabaseSettings.cypher_min_replan_interval -> "0")

  test("should monitor cache misses") {
    val counter = new CacheCounter()
    val compiler = createCompiler()
    compiler.monitors.addMonitorListener(counter)

    graph.inTx { compiler.planQuery("return 42", planContext, devNullLogger) }

    counter.counts should equal(CacheCounts(hits = 0, misses = 1, flushes = 1))
  }

  test("should monitor cache hits") {
    val compiler = createCompiler()
    val counter = new CacheCounter()
    compiler.monitors.addMonitorListener(counter)

    graph.inTx { compiler.planQuery("return 42", planContext, devNullLogger) }
    graph.inTx { compiler.planQuery("return 42", planContext, devNullLogger) }

    counter.counts should equal(CacheCounts(hits = 1, misses = 1, flushes = 1))
  }

  test("should not care about white spaces") {
    val compiler = createCompiler()
    val counter = new CacheCounter()
    compiler.monitors.addMonitorListener(counter)

    graph.inTx { compiler.planQuery("return 42", planContext, devNullLogger) }
    graph.inTx { compiler.planQuery("\treturn          42", planContext, devNullLogger) }

    counter.counts should equal(CacheCounts(hits = 1, misses = 1, flushes = 1))
  }

  test("should cache easily parametrized queries") {
    val compiler = createCompiler()
    val counter = new CacheCounter()
    compiler.monitors.addMonitorListener(counter)

    graph.inTx { compiler.planQuery("return 42 as result", planContext, devNullLogger) }
    graph.inTx { compiler.planQuery("return 43 as result", planContext, devNullLogger) }

    counter.counts should equal(CacheCounts(hits = 1, misses = 1, flushes = 1))
  }

  test("should monitor cache flushes") {
    val compiler = createCompiler()
    val counter = new CacheCounter()
    compiler.monitors.addMonitorListener(counter)

    graph.inTx { compiler.planQuery("return 42", planContext, devNullLogger) }
    graph.createConstraint("Person", "id")
    graph.inTx { compiler.planQuery("return 42", planContext, devNullLogger) }

    counter.counts should equal(CacheCounts(hits = 0, misses = 2, flushes = 2))
  }

  test("should monitor cache remove") {
    // given
    val counter = new CacheCounter()
    val clock: Clock = new FrozenClock(1000, TimeUnit.MILLISECONDS)
    val compiler = createCompiler(queryPlanTTL = 0, clock = clock)
    compiler.monitors.addMonitorListener(counter)
    val query: String = "match (n:Person:Dog) return n"

    createLabeledNode("Dog")
    (0 until 50).foreach { _ => createLabeledNode("Person") }
    graph.inTx { compiler.planQuery(query, planContext, devNullLogger) }

    // when
    (0 until 1000).foreach { _ => createLabeledNode("Dog") }
    graph.inTx { compiler.planQuery(query, planContext, devNullLogger) }

    // then
    counter.counts should equal(CacheCounts(hits = 1, misses = 2, flushes = 1, evicted = 1))
  }

  test("should log on cache remove") {
    // given
    val counter = new CacheCounter()
    val logProvider = new AssertableLogProvider()
    val clock: Clock = new FrozenClock(1000, TimeUnit.MILLISECONDS)
    val compiler = createCompiler(queryPlanTTL = 0, clock = clock, log = logProvider.getLog(getClass))
    compiler.monitors.addMonitorListener(counter)
    val query: String = "match (n:Person:Dog) return n"
    val statement = compiler.prepareQuery(query, query, devNullLogger).statement

    createLabeledNode("Dog")
    (0 until 50).foreach { _ => createLabeledNode("Person") }
    graph.inTx { compiler.planQuery(query, planContext, devNullLogger) }

    // when
    (0 until 1000).foreach { _ => createLabeledNode("Dog") }
    graph.inTx { compiler.planQuery(query, planContext, devNullLogger) }

    // then
    logProvider.assertExactly(
      inLog(getClass).info( s"Discarded stale query from the query cache: $statement" )
    )
  }
}
