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

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.neo4j.collection.pool.LinkedQueuePool;
import org.neo4j.collection.pool.MarshlandPool;
import org.neo4j.function.Factory;
import org.neo4j.graphdb.DatabaseShutdownException;
import org.neo4j.helpers.Clock;
import org.neo4j.kernel.api.KernelTransaction;
import org.neo4j.kernel.api.txstate.LegacyIndexTransactionState;
import org.neo4j.kernel.impl.api.state.ConstraintIndexCreator;
import org.neo4j.kernel.impl.api.state.LegacyIndexTransactionStateImpl;
import org.neo4j.kernel.impl.index.IndexConfigStore;
import org.neo4j.kernel.impl.locking.Locks;
import org.neo4j.kernel.impl.storageengine.StorageEngine;
import org.neo4j.kernel.impl.transaction.TransactionHeaderInformationFactory;
import org.neo4j.kernel.impl.transaction.TransactionMonitor;
import org.neo4j.kernel.impl.transaction.state.NeoStoreTransactionContext;
import org.neo4j.kernel.impl.transaction.state.NeoStoreTransactionContextFactory;
import org.neo4j.kernel.impl.transaction.state.TransactionRecordState;
import org.neo4j.kernel.lifecycle.LifeSupport;
import org.neo4j.kernel.lifecycle.LifecycleAdapter;
import org.neo4j.kernel.monitoring.tracing.Tracers;

import static java.util.Collections.newSetFromMap;

/**
 * Central source of transactions in the database.
 * <p>
 * This class maintains references to all transactions, a pool of passive kernel transactions, and provides
 * capabilities
 * for enumerating all running transactions. During normal operation, acquiring new transactions and enumerating live
 * ones requires no synchronization (although the live list is not guaranteed to be exact).
 */
public class KernelTransactions extends LifecycleAdapter implements Factory<KernelTransaction>
{
    // Transaction dependencies

    private final NeoStoreTransactionContextFactory neoStoreTransactionContextFactory;
    private final Locks locks;
    private final ConstraintIndexCreator constraintIndexCreator;
    private final StatementOperationParts statementOperations;
    private final SchemaWriteGuard schemaWriteGuard;
    private final TransactionHeaderInformationFactory transactionHeaderInformationFactory;
    private final TransactionCommitProcess transactionCommitProcess;
    private final IndexConfigStore indexConfigStore;
    private final LegacyIndexProviderLookup legacyIndexProviderLookup;
    private final TransactionHooks hooks;
    private final TransactionMonitor transactionMonitor;
    private final LifeSupport dataSourceLife;
    private final Tracers tracers;
    private final StorageEngine storageEngine;

    // End Tx Dependencies

    /**
     * Used to enumerate all transactions in the system, active and idle ones.
     * <p>
     * This data structure is *only* updated when brand-new transactions are created, or when transactions are disposed
     * of. During normal operation (where all transactions come from and are returned to the pool), this will be left
     * in peace, working solely as a collection of references to all transaction objects (idle and active) in the
     * database.
     * <p>
     * As such, it provides a good mechanism for listing all transactions without requiring synchronization when
     * starting and committing transactions.
     */
    private final Set<KernelTransactionImplementation> allTransactions = newSetFromMap(
            new ConcurrentHashMap<>() );

    public KernelTransactions( NeoStoreTransactionContextFactory neoStoreTransactionContextFactory,
                               Locks locks,
                               ConstraintIndexCreator constraintIndexCreator,
                               StatementOperationParts statementOperations,
                               SchemaWriteGuard schemaWriteGuard,
                               TransactionHeaderInformationFactory txHeaderFactory,
                               TransactionCommitProcess transactionCommitProcess,
                               IndexConfigStore indexConfigStore,
                               LegacyIndexProviderLookup legacyIndexProviderLookup,
                               TransactionHooks hooks,
                               TransactionMonitor transactionMonitor,
                               LifeSupport dataSourceLife,
                               Tracers tracers,
                               StorageEngine storageEngine )
    {
        this.neoStoreTransactionContextFactory = neoStoreTransactionContextFactory;
        this.locks = locks;
        this.constraintIndexCreator = constraintIndexCreator;
        this.statementOperations = statementOperations;
        this.schemaWriteGuard = schemaWriteGuard;
        this.transactionHeaderInformationFactory = txHeaderFactory;
        this.transactionCommitProcess = transactionCommitProcess;
        this.indexConfigStore = indexConfigStore;
        this.legacyIndexProviderLookup = legacyIndexProviderLookup;
        this.hooks = hooks;
        this.transactionMonitor = transactionMonitor;
        this.dataSourceLife = dataSourceLife;
        this.tracers = tracers;
        this.storageEngine = storageEngine;
    }

    /**
     * This is the factory that actually builds brand-new instances.
     */
    private final Factory<KernelTransactionImplementation> factory = new Factory<KernelTransactionImplementation>()
    {
        @Override
        public KernelTransactionImplementation newInstance()
        {
            Locks.Client locksClient = locks.newClient();
            NeoStoreTransactionContext context = neoStoreTransactionContextFactory.newInstance( locksClient );
            TransactionRecordState recordState = new TransactionRecordState(
                    storageEngine.neoStores(), storageEngine.integrityValidator(), context );
            LegacyIndexTransactionState legacyIndexTransactionState =
                    new LegacyIndexTransactionStateImpl( indexConfigStore, legacyIndexProviderLookup );
            KernelTransactionImplementation tx = new KernelTransactionImplementation(
                    statementOperations, schemaWriteGuard,
                    locksClient, hooks, constraintIndexCreator, transactionHeaderInformationFactory,
                    transactionCommitProcess, transactionMonitor, legacyIndexTransactionState,
                    localTxPool, Clock.SYSTEM_CLOCK, tracers.transactionTracer, storageEngine );

            allTransactions.add( tx );

            return tx;
        }
    };

    @Override
    public KernelTransaction newInstance()
    {
        assertDatabaseIsRunning();
        return localTxPool.acquire().initialize( storageEngine.metaDataStore().getLastCommittedTransactionId() );
    }

    /**
     * Global pool of transactions, wrapped by the thread-local marshland pool and so is not used directly.
     */
    private final LinkedQueuePool<KernelTransactionImplementation> globalTxPool
            = new LinkedQueuePool<KernelTransactionImplementation>( 8, factory )
    {
        @Override
        protected void dispose( KernelTransactionImplementation tx )
        {
            allTransactions.remove( tx );
            tx.dispose();
            super.dispose( tx );
        }
    };

    /**
     * Give an approximate list of all transactions currently running. This is not guaranteed to be exact, as
     * transactions may stop and start while this list is gathered.
     */
    public List<KernelTransaction> activeTransactions()
    {
        List<KernelTransaction> output = new ArrayList<>();
        for ( KernelTransactionImplementation tx : allTransactions )
        {
            if ( tx.isOpen() )
            {
                output.add( tx );
            }
        }

        return output;
    }

    /**
     * Pool of unused transactions.
     */
    private final MarshlandPool<KernelTransactionImplementation> localTxPool = new MarshlandPool<>( globalTxPool );

    /**
     * Dispose of all pooled transactions. This is done on shutdown or on internal events (like an HA mode switch) that
     * require transactions to be re-created.
     */
    public void disposeAll()
    {
        for ( KernelTransactionImplementation tx : allTransactions )
        {
            // we mark all transactions for termination since we want to make sure these transactions
            // won't be reused, ever. Each transaction has, among other things, a Locks.Client and we
            // certainly want to keep that from being reused from this point.
            tx.markForTermination();
        }
        localTxPool.disposeAll();
        globalTxPool.disposeAll();
    }

    @Override
    public void shutdown() throws Throwable
    {
        disposeAll();
    }

    private void assertDatabaseIsRunning()
    {
        // TODO: Copied over from original source in NeoXADS - this should probably use DBAvailability,
        // rather than this.
        // Note, if you change this, you need a separate mechanism to stop transactions from being started during
        // Kernel#stop().
        if ( !dataSourceLife.isRunning() )
        {
            throw new DatabaseShutdownException();
        }
    }
}
