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
package org.neo4j.cypher.internal.compiler.v3_0.codegen.ir

import org.neo4j.cypher.internal.compiler.v3_0.codegen.{CodeGenContext, MethodStructure, Variable}
import org.neo4j.cypher.internal.frontend.v3_0.SemanticDirection

case class ExpandAllLoopDataGenerator(opName: String, fromVar: Variable, dir: SemanticDirection,
                   types: Map[String, String], toVar: Variable)
  extends LoopDataGenerator {

  override def init[E](generator: MethodStructure[E])(implicit context: CodeGenContext) = {
    types.foreach {
      case (typeVar,relType) => generator.lookupRelationshipTypeId(typeVar, relType)
    }
  }

  override def produceIterator[E](iterVar: String, generator: MethodStructure[E])(implicit context: CodeGenContext) = {
    if(types.isEmpty)
      generator.nodeGetAllRelationships(iterVar, fromVar.name, dir)
    else
      generator.nodeGetRelationships(iterVar, fromVar.name, dir, types.keys.toSeq)
    generator.incrementDbHits()
  }

  override def produceNext[E](nextVar: Variable, iterVar: String, generator: MethodStructure[E])
                             (implicit context: CodeGenContext) =
    generator.nextRelationshipAndNode(toVar.name, iterVar, dir, fromVar.name, nextVar.name)
}
