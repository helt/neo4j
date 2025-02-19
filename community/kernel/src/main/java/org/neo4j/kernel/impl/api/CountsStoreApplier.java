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
package org.neo4j.kernel.impl.api;

import java.io.IOException;


import org.neo4j.kernel.impl.locking.LockGroup;
import org.neo4j.kernel.impl.store.counts.CountsTracker;
import org.neo4j.kernel.impl.transaction.command.Command;
import org.neo4j.kernel.impl.transaction.command.CommandHandler;

public class CountsStoreApplier extends CommandHandler.Adapter
{
    private final CountsTracker countsTracker;
    private CountsTracker.Updater countsUpdater;
    private final TransactionApplicationMode mode;

    public CountsStoreApplier( CountsTracker countsTracker, TransactionApplicationMode mode )
    {
        this.countsTracker = countsTracker;
        this.mode = mode;
    }

    @Override
    public void begin( TransactionToApply transaction, LockGroup locks ) throws IOException
    {
        countsTracker.apply( transaction.transactionId() ).map(
                ( CountsTracker.Updater updater ) -> this.countsUpdater = updater );
        assert this.countsUpdater != null || mode == TransactionApplicationMode.RECOVERY;
    }

    @Override
    public void end()
    {
        assert countsUpdater != null || mode == TransactionApplicationMode.RECOVERY : "You must call begin first";
        if ( countsUpdater != null )
        {   // CountsUpdater is null if we're in recovery and the counts store already has had this transaction applied.
            countsUpdater.close();
        }
    }

    @Override
    public boolean visitNodeCountsCommand( Command.NodeCountsCommand command )
    {
        assert countsUpdater != null || mode == TransactionApplicationMode.RECOVERY : "You must call begin first";
        if ( countsUpdater != null )
        {   // CountsUpdater is null if we're in recovery and the counts store already has had this transaction applied.
            countsUpdater.incrementNodeCount( command.labelId(), command.delta() );
        }
        return false;
    }

    @Override
    public boolean visitRelationshipCountsCommand( Command.RelationshipCountsCommand command ) throws IOException
    {
        assert countsUpdater != null || mode == TransactionApplicationMode.RECOVERY : "You must call begin first";
        if ( countsUpdater != null )
        {   // CountsUpdater is null if we're in recovery and the counts store already has had this transaction applied.
            countsUpdater.incrementRelationshipCount(
                    command.startLabelId(), command.typeId(), command.endLabelId(), command.delta() );
        }
        return false;
    }
}
