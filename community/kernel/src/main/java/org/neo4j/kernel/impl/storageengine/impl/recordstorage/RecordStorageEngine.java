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
package org.neo4j.kernel.impl.storageengine.impl.recordstorage;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.function.Supplier;

import org.neo4j.concurrent.WorkSync;
import org.neo4j.function.Factory;
import org.neo4j.graphdb.config.Setting;
import org.neo4j.io.fs.FileSystemAbstraction;
import org.neo4j.io.pagecache.PageCache;
import org.neo4j.kernel.IdGeneratorFactory;
import org.neo4j.kernel.api.TokenNameLookup;
import org.neo4j.kernel.api.exceptions.TransactionFailureException;
import org.neo4j.kernel.api.exceptions.schema.ConstraintValidationKernelException;
import org.neo4j.kernel.api.exceptions.schema.CreateConstraintFailureException;
import org.neo4j.kernel.api.index.SchemaIndexProvider;
import org.neo4j.kernel.api.labelscan.LabelScanStore;
import org.neo4j.kernel.api.txstate.LegacyIndexTransactionState;
import org.neo4j.kernel.api.txstate.TransactionCountingStateVisitor;
import org.neo4j.kernel.api.txstate.TransactionState;
import org.neo4j.kernel.api.txstate.TxStateVisitor;
import org.neo4j.kernel.configuration.Config;
import org.neo4j.kernel.impl.api.CommandApplierFacade;
import org.neo4j.kernel.impl.api.CountsRecordState;
import org.neo4j.kernel.impl.api.CountsStoreApplier;
import org.neo4j.kernel.impl.api.LegacyIndexApplier;
import org.neo4j.kernel.impl.api.LegacyIndexApplierLookup;
import org.neo4j.kernel.impl.api.LegacyIndexProviderLookup;
import org.neo4j.kernel.impl.api.StatementOperationParts;
import org.neo4j.kernel.impl.api.TransactionApplicationMode;
import org.neo4j.kernel.impl.api.TransactionToApply;
import org.neo4j.kernel.impl.api.index.IndexUpdateMode;
import org.neo4j.kernel.impl.api.index.IndexUpdatesValidator;
import org.neo4j.kernel.impl.api.index.IndexingService;
import org.neo4j.kernel.impl.api.index.OnlineIndexUpdatesValidator;
import org.neo4j.kernel.impl.api.index.RecoveryIndexingUpdatesValidator;
import org.neo4j.kernel.impl.api.index.SchemaIndexProviderMap;
import org.neo4j.kernel.impl.api.index.sampling.IndexSamplingConfig;
import org.neo4j.kernel.impl.api.scan.LabelScanStoreProvider;
import org.neo4j.kernel.impl.api.store.CacheLayer;
import org.neo4j.kernel.impl.api.store.DiskLayer;
import org.neo4j.kernel.impl.api.store.ProcedureCache;
import org.neo4j.kernel.impl.api.store.SchemaCache;
import org.neo4j.kernel.impl.api.store.StoreReadLayer;
import org.neo4j.kernel.impl.api.store.StoreStatement;
import org.neo4j.kernel.impl.cache.BridgingCacheAccess;
import org.neo4j.kernel.impl.constraints.ConstraintSemantics;
import org.neo4j.kernel.impl.core.CacheAccessBackDoor;
import org.neo4j.kernel.impl.core.LabelTokenHolder;
import org.neo4j.kernel.impl.core.PropertyKeyTokenHolder;
import org.neo4j.kernel.impl.core.RelationshipTypeTokenHolder;
import org.neo4j.kernel.impl.index.IndexConfigStore;
import org.neo4j.kernel.impl.locking.LockGroup;
import org.neo4j.kernel.impl.locking.LockService;
import org.neo4j.kernel.impl.locking.Locks;
import org.neo4j.kernel.impl.storageengine.StorageEngine;
import org.neo4j.kernel.impl.store.MetaDataStore;
import org.neo4j.kernel.impl.store.NeoStores;
import org.neo4j.kernel.impl.store.SchemaStorage;
import org.neo4j.kernel.impl.store.StoreFactory;
import org.neo4j.kernel.impl.store.record.SchemaRule;
import org.neo4j.kernel.impl.transaction.command.CacheInvalidationTransactionApplier;
import org.neo4j.kernel.impl.transaction.command.Command;
import org.neo4j.kernel.impl.transaction.command.CommandHandler;
import org.neo4j.kernel.impl.transaction.command.HighIdTransactionApplier;
import org.neo4j.kernel.impl.transaction.command.IndexTransactionApplier;
import org.neo4j.kernel.impl.transaction.command.LabelUpdateWork;
import org.neo4j.kernel.impl.transaction.command.NeoStoreTransactionApplier;
import org.neo4j.kernel.impl.transaction.log.LogVersionRepository;
import org.neo4j.kernel.impl.transaction.log.TransactionIdStore;
import org.neo4j.kernel.impl.transaction.state.DefaultSchemaIndexProviderMap;
import org.neo4j.kernel.impl.transaction.state.IntegrityValidator;
import org.neo4j.kernel.impl.transaction.state.NeoStoreIndexStoreView;
import org.neo4j.kernel.impl.transaction.state.NeoStoreTransactionContext;
import org.neo4j.kernel.impl.transaction.state.PropertyLoader;
import org.neo4j.kernel.impl.transaction.state.TransactionRecordState;
import org.neo4j.kernel.impl.util.IdOrderingQueue;
import org.neo4j.kernel.impl.util.JobScheduler;
import org.neo4j.kernel.impl.util.SynchronizedArrayIdOrderingQueue;
import org.neo4j.kernel.internal.DatabaseHealth;
import org.neo4j.kernel.lifecycle.Lifecycle;
import org.neo4j.logging.LogProvider;
import org.neo4j.unsafe.batchinsert.LabelScanWriter;

import static org.neo4j.helpers.Settings.BOOLEAN;
import static org.neo4j.helpers.Settings.TRUE;
import static org.neo4j.helpers.Settings.setting;
import static org.neo4j.helpers.collection.Iterables.toList;
import static org.neo4j.kernel.impl.locking.LockService.NO_LOCK_SERVICE;

public class RecordStorageEngine implements StorageEngine, Lifecycle
{
    /**
     * This setting is hidden to the user and is here merely for making it easier to back out of
     * a change where reading property chains incurs read locks on {@link LockService}.
     */
    private static final Setting<Boolean> use_read_locks_on_property_reads =
            setting( "experimental.use_read_locks_on_property_reads", BOOLEAN, TRUE );

    private final StoreReadLayer storeLayer;
    private final IndexingService indexingService;
    private final NeoStores neoStores;
    private final PropertyKeyTokenHolder propertyKeyTokenHolder;
    private final RelationshipTypeTokenHolder relationshipTypeTokenHolder;
    private final LabelTokenHolder labelTokenHolder;
    private final DatabaseHealth databaseHealth;
    private final IndexConfigStore indexConfigStore;
    private final SchemaCache schemaCache;
    private final IntegrityValidator integrityValidator;
    private final IndexUpdatesValidator indexUpdatesValidator;
    private final CacheAccessBackDoor cacheAccess;
    private final LabelScanStore labelScanStore;
    private final DefaultSchemaIndexProviderMap providerMap;
    private final ProcedureCache procedureCache;
    private final LegacyIndexApplierLookup legacyIndexApplierLookup;
    private final Runnable schemaStateChangeCallback;
    private final SchemaStorage schemaStorage;
    private final ConstraintSemantics constraintSemantics;
    private final RecoveryIndexingUpdatesValidator indexUpdatesValidatorForRecovery;
    private final IdOrderingQueue legacyIndexTransactionOrdering;
    private final LockService lockService;
    private final WorkSync<Supplier<LabelScanWriter>,LabelUpdateWork> labelScanStoreSync;

    public RecordStorageEngine(
            File storeDir,
            Config config,
            IdGeneratorFactory idGeneratorFactory,
            PageCache pageCache,
            FileSystemAbstraction fs,
            LogProvider logProvider,
            PropertyKeyTokenHolder propertyKeyTokenHolder,
            LabelTokenHolder labelTokens,
            RelationshipTypeTokenHolder relationshipTypeTokens,
            Runnable schemaStateChangeCallback,
            ConstraintSemantics constraintSemantics,
            JobScheduler scheduler,
            TokenNameLookup tokenNameLookup,
            LockService lockService,
            SchemaIndexProvider indexProvider,
            IndexingService.Monitor indexingServiceMonitor,
            DatabaseHealth databaseHealth,
            LabelScanStoreProvider labelScanStoreProvider,
            LegacyIndexProviderLookup legacyIndexProviderLookup,
            IndexConfigStore indexConfigStore )
    {
        this.propertyKeyTokenHolder = propertyKeyTokenHolder;
        this.relationshipTypeTokenHolder = relationshipTypeTokens;
        this.labelTokenHolder = labelTokens;
        this.schemaStateChangeCallback = schemaStateChangeCallback;
        this.constraintSemantics = constraintSemantics;
        this.lockService = lockService;
        this.databaseHealth = databaseHealth;
        this.indexConfigStore = indexConfigStore;
        final StoreFactory storeFactory = new StoreFactory( storeDir, config, idGeneratorFactory, pageCache, fs, logProvider );
        neoStores = storeFactory.openAllNeoStores( true );

        try
        {
            schemaCache = new SchemaCache( constraintSemantics, Collections.<SchemaRule>emptyList() );
            schemaStorage = new SchemaStorage( neoStores.getSchemaStore() );

            providerMap = new DefaultSchemaIndexProviderMap( indexProvider );
            indexingService = IndexingService.create(
                    new IndexSamplingConfig( config ), scheduler, providerMap,
                    new NeoStoreIndexStoreView( lockService, neoStores ), tokenNameLookup,
                    toList( new SchemaStorage( neoStores.getSchemaStore() ).allIndexRules() ), logProvider,
                    indexingServiceMonitor, schemaStateChangeCallback );

            integrityValidator = new IntegrityValidator( neoStores, indexingService );
            indexUpdatesValidator = new OnlineIndexUpdatesValidator(
                    neoStores, databaseHealth, new PropertyLoader( neoStores ),
                    indexingService, IndexUpdateMode.BATCHED );
            cacheAccess = new BridgingCacheAccess( schemaCache, schemaStateChangeCallback,
                    propertyKeyTokenHolder, relationshipTypeTokens, labelTokens );

            DiskLayer diskLayer = new DiskLayer( propertyKeyTokenHolder, labelTokens, relationshipTypeTokens,
                    schemaStorage,
                    neoStores, indexingService, storeStatementFactory( neoStores, config, lockService ) );
            procedureCache = new ProcedureCache();
            storeLayer = new CacheLayer( diskLayer, schemaCache, procedureCache );
            labelScanStore = labelScanStoreProvider.getLabelScanStore();
            legacyIndexApplierLookup = new LegacyIndexApplierLookup.Direct( legacyIndexProviderLookup );
            legacyIndexTransactionOrdering = new SynchronizedArrayIdOrderingQueue( 20 );

            indexUpdatesValidatorForRecovery = new RecoveryIndexingUpdatesValidator( indexingService );
            labelScanStoreSync = new WorkSync<>( labelScanStore::newWriter );
        }
        catch ( Throwable failure )
        {
            neoStores.close();
            throw failure;
        }
    }

    private static Factory<StoreStatement> storeStatementFactory(
            NeoStores neoStores, Config config, LockService lockService )
    {
        final LockService currentLockService =
                config.get( use_read_locks_on_property_reads ) ? lockService : NO_LOCK_SERVICE;
        return () -> new StoreStatement( neoStores, currentLockService );
    }

    @Override
    public StoreReadLayer storeReadLayer()
    {
        return storeLayer;
    }

    @SuppressWarnings( "resource" )
    @Override
    public Collection<Command> createCommands(
            TransactionState txState,
            LegacyIndexTransactionState legacyIndexTransactionState,
            Locks.Client locks,
            StatementOperationParts operations,
            StoreStatement storeStatement,
            long lastTransactionIdWhenStarted )
            throws TransactionFailureException, CreateConstraintFailureException, ConstraintValidationKernelException
    {
        // Create objects to be populated with command-friendly changes
        Collection<Command> commands = new ArrayList<>();

        if ( txState == null )
        {
            // Bypass creating commands for record transaction state if we have no transaction state
            legacyIndexTransactionState.extractCommands( commands );
            return commands;
        }

        NeoStoreTransactionContext context = new NeoStoreTransactionContext( neoStores, locks );
        TransactionRecordState recordState = new TransactionRecordState( neoStores, integrityValidator, context );
        recordState.initialize( lastTransactionIdWhenStarted );

        // Visit transaction state and populate these record state objects
        TxStateVisitor txStateVisitor = new TransactionToRecordStateVisitor( recordState,
                schemaStateChangeCallback, schemaStorage, constraintSemantics, providerMap,
                legacyIndexTransactionState, procedureCache );
        CountsRecordState countsRecordState = new CountsRecordState();
        txStateVisitor = constraintSemantics.decorateTxStateVisitor(
                operations,
                storeStatement,
                storeLayer,
                new DirectTxStateHolder( txState, legacyIndexTransactionState ),
                txStateVisitor );
        txStateVisitor = new TransactionCountingStateVisitor(
                txStateVisitor, storeLayer, txState, countsRecordState );
        try ( TxStateVisitor visitor = txStateVisitor )
        {
            txState.accept( txStateVisitor );
        }

        // Convert record state into commands
        recordState.extractCommands( commands );
        legacyIndexTransactionState.extractCommands( commands );
        countsRecordState.extractCommands( commands );

        return commands;
    }

    @Override
    public void apply( TransactionToApply batch, TransactionApplicationMode mode ) throws Exception
    {
        // Have these command appliers as separate try-with-resource to have better control over
        // point between closing this and the locks above
        try ( CommandApplierFacade applier = applier( mode ) )
        {
            TransactionToApply tx = batch;
            while ( tx != null )
            {
                try ( LockGroup locks = new LockGroup() )
                {
                    ensureValidatedIndexUpdates( tx );
                    applier.begin( tx, locks );
                    tx.transactionRepresentation().accept( applier );
                    applier.end();
                    tx = tx.next();
                }
                catch ( Throwable cause )
                {
                    databaseHealth.panic( cause );
                    throw cause;
                }
            }
        }
    }

    /**
     * Creates a {@link CommandApplierFacade} that is to be used for all transactions in a batch.
     * Before starting to apply contents of each transaction a call to {@link CommandHandler#begin(TransactionToApply)}
     * is issued, otherwise the appliers all just gets visit-calls with contents from all transactions.
     *
     * After all transactions have been applied the appliers are closed (apply() then close()).
     */
    private CommandApplierFacade applier( TransactionApplicationMode mode )
    {
        // Graph store application. The order of the decorated store appliers is irrelevant
        CommandHandler storeApplier = new NeoStoreTransactionApplier( neoStores, cacheAccess, lockService );
        if ( mode.needsHighIdTracking() )
        {
            storeApplier = new HighIdTransactionApplier( storeApplier, neoStores );
        }
        if ( mode.needsCacheInvalidationOnUpdates() )
        {
            storeApplier = new CacheInvalidationTransactionApplier( storeApplier, neoStores, cacheAccess );
        }

        // Schema index application
        CommandHandler indexApplier = new IndexTransactionApplier( indexingService, labelScanStoreSync );

        // Legacy index application
        CommandHandler legacyIndexApplier = new LegacyIndexApplier( indexConfigStore,
                legacyIndexApplierLookup, legacyIndexTransactionOrdering, mode );

        // Counts store application
        CommandHandler countsStoreApplier = new CountsStoreApplier( neoStores.getCounts(), mode );

        // Perform the application
        return new CommandApplierFacade( storeApplier, indexApplier, legacyIndexApplier, countsStoreApplier );
    }

    private void ensureValidatedIndexUpdates( TransactionToApply tx ) throws IOException
    {
        // This method is a hack needed because
        // - we derive logical index updates from physical record changes + reading from store.
        //   In a batching scenario we must do this validation before each transaction gets applied,
        //   because data might rely on data applied by the previous transaction.
        // - we do index validation up front like this and will be removed once we bust the lucene limits.
        if ( tx.validatedIndexUpdates() == null )
        {
            tx.validatedIndexUpdates( indexUpdatesValidator.validate( tx.transactionRepresentation() ) );
        }
    }

    @Override
    public IndexUpdatesValidator indexUpdatesValidatorForRecovery()
    {
        return indexUpdatesValidatorForRecovery;
    }

    @Override
    public TransactionIdStore transactionIdStore()
    {
        return neoStores.getMetaDataStore();
    }

    @Override
    public LogVersionRepository logVersionRepository()
    {
        return neoStores.getMetaDataStore();
    }

    @Override
    public ProcedureCache procedureCache()
    {
        return procedureCache;
    }

    @Override
    public NeoStores neoStores()
    {
        return neoStores;
    }

    @Override
    public MetaDataStore metaDataStore()
    {
        return neoStores.getMetaDataStore();
    }

    @Override
    public IndexingService indexingService()
    {
        return indexingService;
    }

    @Override
    public IndexUpdatesValidator indexUpdatesValidator()
    {
        return indexUpdatesValidator;
    }

    @Override
    public LabelScanStore labelScanStore()
    {
        return labelScanStore;
    }

    @Override
    public IntegrityValidator integrityValidator()
    {
        return integrityValidator;
    }

    @Override
    public SchemaIndexProviderMap schemaIndexProviderMap()
    {
        return providerMap;
    }

    @Override
    public CacheAccessBackDoor cacheAccess()
    {
        return cacheAccess;
    }

    @Override
    public LegacyIndexApplierLookup legacyIndexApplierLookup()
    {
        return legacyIndexApplierLookup;
    }

    @Override
    public IndexConfigStore indexConfigStore()
    {
        return indexConfigStore;
    }

    @Override
    public IdOrderingQueue legacyIndexTransactionOrdering()
    {
        return legacyIndexTransactionOrdering;
    }

    @Override
    public void init() throws Throwable
    {
        indexingService.init();
        labelScanStore.init();
    }

    @Override
    public void start() throws Throwable
    {
        neoStores.makeStoreOk();

        propertyKeyTokenHolder.setInitialTokens(
                neoStores.getPropertyKeyTokenStore().getTokens( Integer.MAX_VALUE ) );
        relationshipTypeTokenHolder.setInitialTokens(
                neoStores.getRelationshipTypeTokenStore().getTokens( Integer.MAX_VALUE ) );
        labelTokenHolder.setInitialTokens(
                neoStores.getLabelTokenStore().getTokens( Integer.MAX_VALUE ) );

        neoStores.rebuildCountStoreIfNeeded(); // TODO: move this to counts store lifecycle
        loadSchemaCache();
        indexingService.start();
        labelScanStore.start();
    }

    @Override
    public void loadSchemaCache()
    {
        List<SchemaRule> schemaRules = toList( neoStores.getSchemaStore().loadAllSchemaRules() );
        schemaCache.load( schemaRules );
    }

    @Override
    public void stop() throws Throwable
    {
        labelScanStore.stop();
        indexingService.stop();
    }

    @Override
    public void shutdown() throws Throwable
    {
        labelScanStore.shutdown();
        indexingService.shutdown();
    }
}
