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
package org.neo4j.tools.rebuild;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.neo4j.com.storecopy.ExternallyManagedPageCache;
import org.neo4j.consistency.ConsistencyCheckService;
import org.neo4j.consistency.ConsistencyCheckSettings;
import org.neo4j.consistency.checking.full.ConsistencyCheckIncompleteException;
import org.neo4j.consistency.checking.full.FullCheck;
import org.neo4j.consistency.statistics.Statistics;
import org.neo4j.graphdb.DependencyResolver;
import org.neo4j.graphdb.factory.GraphDatabaseFactory;
import org.neo4j.graphdb.factory.GraphDatabaseSettings;
import org.neo4j.helpers.Args;
import org.neo4j.helpers.progress.ProgressMonitorFactory;
import org.neo4j.io.fs.DefaultFileSystemAbstraction;
import org.neo4j.io.fs.FileSystemAbstraction;
import org.neo4j.io.pagecache.PageCache;
import org.neo4j.kernel.GraphDatabaseAPI;
import org.neo4j.kernel.NeoStoreDataSource;
import org.neo4j.kernel.api.direct.DirectStoreAccess;
import org.neo4j.kernel.api.index.SchemaIndexProvider;
import org.neo4j.kernel.api.labelscan.LabelScanStore;
import org.neo4j.kernel.configuration.Config;
import org.neo4j.kernel.impl.api.TransactionCommitProcess;
import org.neo4j.kernel.impl.api.TransactionQueue;
import org.neo4j.kernel.impl.api.TransactionToApply;
import org.neo4j.kernel.impl.pagecache.StandalonePageCacheFactory;
import org.neo4j.kernel.impl.store.MetaDataStore;
import org.neo4j.kernel.impl.store.StoreAccess;
import org.neo4j.kernel.impl.transaction.CommittedTransactionRepresentation;
import org.neo4j.kernel.impl.transaction.TransactionRepresentation;
import org.neo4j.kernel.impl.transaction.log.IOCursor;
import org.neo4j.kernel.impl.transaction.log.PhysicalLogFile;
import org.neo4j.kernel.impl.transaction.log.PhysicalLogFiles;
import org.neo4j.kernel.impl.transaction.log.PhysicalLogVersionedStoreChannel;
import org.neo4j.kernel.impl.transaction.log.PhysicalTransactionCursor;
import org.neo4j.kernel.impl.transaction.log.ReadAheadLogChannel;
import org.neo4j.kernel.impl.transaction.log.ReadableVersionableLogChannel;
import org.neo4j.kernel.impl.transaction.log.ReaderLogVersionBridge;
import org.neo4j.kernel.impl.transaction.log.entry.VersionAwareLogEntryReader;
import org.neo4j.kernel.impl.transaction.state.DataSourceManager;
import org.neo4j.logging.NullLog;

import static java.lang.String.format;

import static org.neo4j.helpers.collection.MapUtil.stringMap;
import static org.neo4j.kernel.impl.api.TransactionApplicationMode.EXTERNAL;
import static org.neo4j.kernel.impl.transaction.log.LogVersionBridge.NO_MORE_CHANNELS;
import static org.neo4j.kernel.impl.transaction.log.PhysicalLogFile.openForVersion;
import static org.neo4j.kernel.impl.transaction.log.TransactionIdStore.BASE_TX_ID;
import static org.neo4j.kernel.impl.transaction.tracing.CommitEvent.NULL;

/**
 * Tool to rebuild store based on available transaction logs.
 */
class RebuildFromLogs
{
    private static final String UP_TO_TX_ID = "tx";

    private final FileSystemAbstraction fs;

    public RebuildFromLogs( FileSystemAbstraction fs )
    {
        this.fs = fs;
    }

    public static void main( String[] args ) throws Exception
    {
        if ( args == null )
        {
            printUsage();
            return;
        }
        Args params = Args.parse( args );
        @SuppressWarnings( "boxing" )
        long txId = params.getNumber( UP_TO_TX_ID, BASE_TX_ID ).longValue();
        List<String> orphans = params.orphans();
        args = orphans.toArray( new String[orphans.size()] );
        if ( args.length != 2 )
        {
            printUsage( "Exactly two positional arguments expected: " +
                        "<source dir with logs> <target dir for graphdb>, got " + args.length );
            System.exit( -1 );
            return;
        }
        File source = new File( args[0] ), target = new File( args[1] );
        if ( !source.isDirectory() )
        {
            printUsage( source + " is not a directory" );
            System.exit( -1 );
            return;
        }
        if ( target.exists() )
        {
            if ( target.isDirectory() )
            {
                if ( directoryContainsDb( target.toPath() ) )
                {
                    printUsage( "target graph database already exists" );
                    System.exit( -1 );
                    return;
                }
                System.err.println( "WARNING: the directory " + target + " already exists" );
            }
            else
            {
                printUsage( target + " is a file" );
                System.exit( -1 );
                return;
            }
        }

        new RebuildFromLogs( new DefaultFileSystemAbstraction() ).rebuild( source, target, txId );
    }

    private static boolean directoryContainsDb( Path path )
    {
        return Files.exists( path.resolve( MetaDataStore.DEFAULT_NAME ) );
    }

    public void rebuild( File source, File target, long txId ) throws Exception
    {
        try ( PageCache pageCache = StandalonePageCacheFactory.createPageCache( fs ) )
        {
            PhysicalLogFiles logFiles = new PhysicalLogFiles( source, fs );
            long highestVersion = logFiles.getHighestLogVersion();
            if ( highestVersion < 0 )
            {
                printUsage( "Inconsistent number of log files found in " + source );
                return;
            }
            long txCount = findLastTransactionId( logFiles, highestVersion );
            ProgressMonitorFactory progress;
            if ( txCount < 0 )
            {
                progress = ProgressMonitorFactory.NONE;
                System.err.println(
                        "Unable to report progress, cannot find highest txId, attempting rebuild anyhow." );
            }
            else
            {
                progress = ProgressMonitorFactory.textual( System.err );
            }
            progress.singlePart( format( "Rebuilding store from %s transactions ", txCount ), txCount );
            try ( TransactionApplier applier = new TransactionApplier( fs, target, pageCache ) )
            {
                long lastTxId = applier.applyTransactionsFrom( source, txId );
                // set last tx id in neostore otherwise the db is not usable
                MetaDataStore.setRecord( pageCache, new File( target, MetaDataStore.DEFAULT_NAME ),
                        MetaDataStore.Position.LAST_TRANSACTION_ID, lastTxId );
            }

            try ( ConsistencyChecker checker = new ConsistencyChecker( target, pageCache ) )
            {
                checker.checkConsistency();
            }
        }
    }


    private long findLastTransactionId( PhysicalLogFiles logFiles, long highestVersion ) throws IOException
    {
        ReadableVersionableLogChannel logChannel = new ReadAheadLogChannel(
                PhysicalLogFile.openForVersion( logFiles, fs, highestVersion ), NO_MORE_CHANNELS );

        long lastTransactionId = -1;

        try ( IOCursor<CommittedTransactionRepresentation> cursor =
                      new PhysicalTransactionCursor<>( logChannel, new VersionAwareLogEntryReader<>() ) )
        {
            while ( cursor.next() )
            {
                lastTransactionId = cursor.get().getCommitEntry().getTxId();
            }
        }
        return lastTransactionId;
    }

    private static void printUsage( String... msgLines )
    {
        for ( String line : msgLines )
        {
            System.err.println( line );
        }
        System.err.println( Args.jarUsage( RebuildFromLogs.class,
                "[-full] <source dir with logs> <target dir for graphdb>" ) );
        System.err.println( "WHERE:   <source dir>  is the path for where transactions to rebuild from are stored" );
        System.err.println( "         <target dir>  is the path for where to create the new graph database" );
        System.err.println( "         -tx       --  to rebuild the store up to a given transaction" );
    }

    private static class TransactionApplier implements AutoCloseable
    {
        private final GraphDatabaseAPI graphdb;
        private final FileSystemAbstraction fs;
        private final TransactionCommitProcess commitProcess;

        TransactionApplier( FileSystemAbstraction fs, File dbDirectory, PageCache pageCache )
        {
            this.fs = fs;
            this.graphdb = startTemporaryDb( dbDirectory.getAbsoluteFile(), pageCache, stringMap() );
            this.commitProcess = graphdb.getDependencyResolver().resolveDependency( TransactionCommitProcess.class );
        }

        long applyTransactionsFrom( File sourceDir, long upToTxId ) throws Exception
        {
            PhysicalLogFiles logFiles = new PhysicalLogFiles( sourceDir, fs );
            int startVersion = 0;
            ReaderLogVersionBridge versionBridge = new ReaderLogVersionBridge( fs, logFiles );
            PhysicalLogVersionedStoreChannel startingChannel = openForVersion( logFiles, fs, startVersion );
            ReadableVersionableLogChannel channel = new ReadAheadLogChannel( startingChannel, versionBridge );
            long txId = BASE_TX_ID;
            TransactionQueue queue = new TransactionQueue( 10_000,
                    (tx) -> {commitProcess.commit( tx, NULL, EXTERNAL );} );
            try ( IOCursor<CommittedTransactionRepresentation> cursor =
                          new PhysicalTransactionCursor<>( channel, new VersionAwareLogEntryReader<>() ) )
            {
                while ( cursor.next() )
                {
                    txId = cursor.get().getCommitEntry().getTxId();
                    TransactionRepresentation transaction = cursor.get().getTransactionRepresentation();
                    queue.queue( new TransactionToApply( transaction, txId ) );
                    if ( upToTxId != BASE_TX_ID && upToTxId == txId )
                    {
                        break;
                    }
                }
            }
            queue.empty();
            return txId;
        }

        @Override
        public void close()
        {
            graphdb.shutdown();
        }
    }

    private static class ConsistencyChecker implements AutoCloseable
    {
        private final GraphDatabaseAPI graphdb;
        private final NeoStoreDataSource dataSource;
        private final Config tuningConfiguration =
                new Config( stringMap(), GraphDatabaseSettings.class, ConsistencyCheckSettings.class );
        private final SchemaIndexProvider indexes;

        ConsistencyChecker( File dbDirectory, PageCache pageCache )
        {
            this.graphdb = startTemporaryDb( dbDirectory.getAbsoluteFile(), pageCache, stringMap() );
            DependencyResolver resolver = graphdb.getDependencyResolver();
            this.dataSource = resolver.resolveDependency( DataSourceManager.class ).getDataSource();
            this.indexes = resolver.resolveDependency( SchemaIndexProvider.class );
        }

        private void checkConsistency() throws ConsistencyCheckIncompleteException
        {
            LabelScanStore labelScanStore = dataSource.getLabelScanStore();
            StoreAccess nativeStores = new StoreAccess( graphdb ).initialize();
            DirectStoreAccess stores = new DirectStoreAccess( nativeStores, labelScanStore, indexes );
            FullCheck fullCheck = new FullCheck( tuningConfiguration, ProgressMonitorFactory.textual( System.err ),
                    Statistics.NONE, ConsistencyCheckService.defaultConsistencyCheckThreadsNumber() );

            fullCheck.execute( stores, NullLog.getInstance() );
        }

        @Override
        public void close() throws Exception
        {
            graphdb.shutdown();
        }
    }

    static GraphDatabaseAPI startTemporaryDb( File targetDirectory, PageCache pageCache, Map<String,String> config )
    {
        GraphDatabaseFactory factory = ExternallyManagedPageCache.graphDatabaseFactoryWithPageCache( pageCache );
        return (GraphDatabaseAPI) factory.newEmbeddedDatabaseBuilder( targetDirectory ).setConfig( config )
                .newGraphDatabase();
    }
}
