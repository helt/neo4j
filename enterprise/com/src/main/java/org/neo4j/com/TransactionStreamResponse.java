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
package org.neo4j.com;

import org.neo4j.com.storecopy.ResponseUnpacker;
import org.neo4j.kernel.impl.store.StoreId;

/**
 * {@link Response} that carries {@link TransactionStream transaction data} as a side-effect, to be applied
 * before accessing the response value.
 *
 * @see ResponseUnpacker
 */
public class TransactionStreamResponse<T> extends Response<T>
{
    private final TransactionStream transactions;

    public TransactionStreamResponse( T response, StoreId storeId, TransactionStream transactions,
            ResourceReleaser releaser )
    {
        super( response, storeId, releaser );
        this.transactions = transactions;
    }

    @Override
    public void accept( Response.Handler handler ) throws Exception
    {
        transactions.accept( handler.transactions() );
    }

    @Override
    public boolean hasTransactionsToBeApplied()
    {
        return true;
    }
}
