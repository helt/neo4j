/*
 * Copyright (c) 2002-2015 "Neo Technology,"
 * Network Engine for Objects in Lund AB [http://neotechnology.com]
 *
 * This file is part of Neo4j.
 *
 * Neo4j is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program. If not, see <http://www.gnu.org/licenses/>.
 */
package org.neo4j.coreedge.raft.outcome;

import java.util.Iterator;
import java.util.Map;

import org.neo4j.coreedge.raft.RaftMessages;

public class Messages<MEMBER> implements Iterable<Map.Entry<MEMBER, RaftMessages.Message<MEMBER>>>
{
    private final Map<MEMBER, RaftMessages.Message<MEMBER>> map;

    Messages( Map<MEMBER, RaftMessages.Message<MEMBER>> map )
    {
        this.map = map;
    }

    public boolean hasMessageFor( MEMBER member )
    {
        return map.containsKey( member );
    }

    public RaftMessages.Message<MEMBER> messageFor( MEMBER member )
    {
        return map.get( member );
    }

    @Override
    public Iterator<Map.Entry<MEMBER, RaftMessages.Message<MEMBER>>> iterator()
    {
        return map.entrySet().iterator();
    }
}
