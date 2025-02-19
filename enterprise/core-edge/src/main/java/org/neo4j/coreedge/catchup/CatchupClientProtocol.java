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
package org.neo4j.coreedge.catchup;

public class CatchupClientProtocol
{
    private NextMessage nextMessage = NextMessage.MESSAGE_TYPE;

    public void expect( NextMessage nextMessage )
    {
        this.nextMessage = nextMessage;
    }

    public boolean isExpecting( NextMessage message )
    {
        return this.nextMessage == message;
    }

    public enum NextMessage
    {
        MESSAGE_TYPE,
        STORE_ID,
        TX_PULL_RESPONSE,
        STORE_COPY_FINISHED,
        TX_STREAM_FINISHED,
        FILE_HEADER,
        FILE_CONTENTS,
        LOCK_RESPONSE
    }
}
