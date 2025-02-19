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

import static java.lang.String.format;

public enum ResponseMessageType
{
    TX( (byte) 1 ),
    STORE_ID( (byte) 2 ),
    FILE( (byte) 3 ),
    STORY_COPY_FINISHED( (byte) 4 ),
    TX_STREAM_FINISHED( (byte) 5 ),
    LOCK( (byte) 6 ),
    UNKNOWN( (byte) 200 ),;

    private byte messageType;

    ResponseMessageType( byte messageType )
    {
        this.messageType = messageType;
    }

    public static ResponseMessageType from( byte b )
    {
        for ( ResponseMessageType responseMessageType : values() )
        {
            if ( responseMessageType.messageType == b )
            {
                return responseMessageType;
            }
        }
        return UNKNOWN;
    }

    public byte messageType()
    {
        return messageType;
    }

    @Override
    public String toString()
    {
        return format( "ResponseMessageType{messageType=%s}", messageType );
    }
}
