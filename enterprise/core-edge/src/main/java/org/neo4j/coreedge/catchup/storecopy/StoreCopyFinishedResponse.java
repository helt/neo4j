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
package org.neo4j.coreedge.catchup.storecopy;

public class StoreCopyFinishedResponse
{
    private final long lastCommittedTxBeforeStoreCopy;

    public StoreCopyFinishedResponse( long lastCommittedTxBeforeStoreCopy )
    {
        this.lastCommittedTxBeforeStoreCopy = lastCommittedTxBeforeStoreCopy;
    }

    public long lastCommittedTxBeforeStoreCopy()
    {
        return lastCommittedTxBeforeStoreCopy;
    }

    @Override
    public boolean equals( Object o )
    {
        if ( this == o )
        {
            return true;
        }
        if ( o == null || getClass() != o.getClass() )
        {
            return false;
        }

        StoreCopyFinishedResponse that = (StoreCopyFinishedResponse) o;

        if ( lastCommittedTxBeforeStoreCopy != that.lastCommittedTxBeforeStoreCopy )
        {
            return false;
        }

        return true;
    }

    @Override
    public int hashCode()
    {
        return (int) (lastCommittedTxBeforeStoreCopy ^ (lastCommittedTxBeforeStoreCopy >>> 32));
    }
}
