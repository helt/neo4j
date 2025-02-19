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

import org.junit.Test;

import java.util.ArrayList;
import java.util.Collection;

import org.neo4j.helpers.collection.Iterables;
import org.neo4j.kernel.api.KernelTransaction;
import org.neo4j.kernel.api.exceptions.TransactionFailureException;
import org.neo4j.kernel.api.txstate.LegacyIndexTransactionState;
import org.neo4j.kernel.api.txstate.TransactionState;
import org.neo4j.kernel.impl.api.store.StoreReadLayer;
import org.neo4j.kernel.impl.api.store.StoreStatement;
import org.neo4j.kernel.impl.locking.Locks;
import org.neo4j.kernel.impl.storageengine.StorageEngine;
import org.neo4j.kernel.impl.store.MetaDataStore;
import org.neo4j.kernel.impl.store.NeoStores;
import org.neo4j.kernel.impl.store.record.NodeRecord;
import org.neo4j.kernel.impl.transaction.TransactionHeaderInformationFactory;
import org.neo4j.kernel.impl.transaction.TransactionMonitor;
import org.neo4j.kernel.impl.transaction.TransactionRepresentation;
import org.neo4j.kernel.impl.transaction.command.Command;
import org.neo4j.kernel.impl.transaction.state.IntegrityValidator;
import org.neo4j.kernel.impl.transaction.state.NeoStoreTransactionContext;
import org.neo4j.kernel.impl.transaction.state.NeoStoreTransactionContextFactory;
import org.neo4j.kernel.impl.transaction.state.RecordAccess;
import org.neo4j.kernel.impl.transaction.state.RecordAccess.RecordProxy;
import org.neo4j.kernel.impl.transaction.tracing.CommitEvent;
import org.neo4j.kernel.lifecycle.LifeSupport;
import org.neo4j.kernel.monitoring.tracing.Tracers;
import org.neo4j.logging.NullLog;

import static org.hamcrest.CoreMatchers.equalTo;
import static org.hamcrest.CoreMatchers.not;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.assertTrue;
import static org.mockito.Matchers.any;
import static org.mockito.Matchers.anyLong;
import static org.mockito.Mockito.RETURNS_MOCKS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import static org.neo4j.helpers.collection.IteratorUtil.asSet;
import static org.neo4j.helpers.collection.IteratorUtil.asUniqueSet;

public class KernelTransactionsTest
{
    @Test
    public void shouldListActiveTransactions() throws Exception
    {
        // Given
        KernelTransactions registry = newKernelTransactions();

        // When
        KernelTransaction first = registry.newInstance();
        KernelTransaction second = registry.newInstance();
        KernelTransaction third = registry.newInstance();

        first.close();

        // Then
        assertThat( asUniqueSet( registry.activeTransactions() ), equalTo( asSet( second, third ) ) );
    }

    @Test
    public void shouldDisposeTransactionsWhenAsked() throws Exception
    {
        // Given
        KernelTransactions registry = newKernelTransactions();

        registry.disposeAll();

        KernelTransaction first = registry.newInstance();
        KernelTransaction second = registry.newInstance();
        KernelTransaction leftOpen = registry.newInstance();
        first.close();
        second.close();

        // When
        registry.disposeAll();

        // Then
        KernelTransaction postDispose = registry.newInstance();
        assertThat( postDispose, not( equalTo( first ) ) );
        assertThat( postDispose, not( equalTo( second ) ) );

        assertTrue( leftOpen.shouldBeTerminated() );
    }

    @Test
    public void shouldIncludeRandomBytesInAdditionalHeader() throws Exception
    {
        // Given
        TransactionRepresentation[] transactionRepresentation = new TransactionRepresentation[1];

        KernelTransactions registry = newKernelTransactions(
                newRememberingCommitProcess( transactionRepresentation ), newMockContextFactoryWithChanges() );

        // When
        try ( KernelTransaction transaction = registry.newInstance() )
        {
            // Just pick anything that can flag that changes have been made to this transaction
            ((KernelTransactionImplementation)transaction).txState().nodeDoCreate( 0 );
            transaction.success();
        }

        // Then
        byte[] additionalHeader = transactionRepresentation[0].additionalHeader();
        assertNotNull( additionalHeader );
        assertTrue( additionalHeader.length > 0 );
    }

    private static KernelTransactions newKernelTransactions() throws Exception
    {
        return newKernelTransactions( mock( TransactionCommitProcess.class ), newMockContextFactory() );
    }

    private static KernelTransactions newKernelTransactions( TransactionCommitProcess commitProcess,
            NeoStoreTransactionContextFactory contextSupplier ) throws Exception
    {
        LifeSupport life = new LifeSupport();
        life.start();

        Locks locks = mock( Locks.class );
        when( locks.newClient() ).thenReturn( mock( Locks.Client.class ) );

        StoreReadLayer readLayer = mock( StoreReadLayer.class );
        MetaDataStore metaDataStore = mock( MetaDataStore.class );
        IntegrityValidator integrityValidator = mock( IntegrityValidator.class );
        NeoStores neoStores = mock( NeoStores.class );

        StorageEngine storageEngine = mock( StorageEngine.class );
        when( storageEngine.storeReadLayer() ).thenReturn( readLayer );
        when( storageEngine.neoStores() ).thenReturn( neoStores );
        when( storageEngine.metaDataStore() ).thenReturn( metaDataStore );
        when( storageEngine.integrityValidator() ).thenReturn( integrityValidator );
        when( storageEngine.createCommands(
                any( TransactionState.class ),
                any( LegacyIndexTransactionState.class ),
                any( Locks.Client.class ),
                any( StatementOperationParts.class ),
                any( StoreStatement.class ),
                anyLong() ) )
                .thenReturn( sillyCommandList() );

        return new KernelTransactions( contextSupplier, locks,
                null, null, null, TransactionHeaderInformationFactory.DEFAULT,
                commitProcess, null,
                null, new TransactionHooks(), mock( TransactionMonitor.class ), life,
                new Tracers( "null", NullLog.getInstance() ), storageEngine );
    }

    private static Collection<Command> sillyCommandList()
    {
        Collection<Command> commands = new ArrayList<>();
        Command command = mock( Command.class );
        commands.add( command );
        return commands;
    }

    private static TransactionCommitProcess newRememberingCommitProcess( final TransactionRepresentation[] slot )
            throws TransactionFailureException

    {
        TransactionCommitProcess commitProcess = mock( TransactionCommitProcess.class );

        when( commitProcess.commit(
                any( TransactionToApply.class ), any( CommitEvent.class ),
                any( TransactionApplicationMode.class ) ) )
                .then( invocation -> {
                    slot[0] = ((TransactionToApply) invocation.getArguments()[0]).transactionRepresentation();
                    return 1L;
                } );

        return commitProcess;
    }

    private static NeoStoreTransactionContextFactory newMockContextFactory()
    {
        NeoStoreTransactionContextFactory factory = mock( NeoStoreTransactionContextFactory.class );
        NeoStoreTransactionContext context = mock( NeoStoreTransactionContext.class, RETURNS_MOCKS );
        when( factory.newInstance( any( Locks.Client.class ) ) ).thenReturn( context );
        return factory;
    }

    @SuppressWarnings( "unchecked" )
    private static NeoStoreTransactionContextFactory newMockContextFactoryWithChanges()
    {
        NeoStoreTransactionContextFactory factory = mock( NeoStoreTransactionContextFactory.class );

        NeoStoreTransactionContext context = mock( NeoStoreTransactionContext.class, RETURNS_MOCKS );
        when( context.hasChanges() ).thenReturn( true );

        RecordAccess<Long,NodeRecord,Void> recordChanges = mock( RecordAccess.class );
        when( recordChanges.changeSize() ).thenReturn( 1 );

        RecordProxy<Long,NodeRecord,Void> recordChange = mock( RecordProxy.class );
        when( recordChange.forReadingLinkage() ).thenReturn( new NodeRecord( 1, false, 1, 1 ) );

        when( recordChanges.changes() ).thenReturn( Iterables.option( recordChange ) );
        when( context.getNodeRecords() ).thenReturn( recordChanges );

        when( factory.newInstance( any( Locks.Client.class ) ) ).thenReturn( context );
        return factory;
    }
}
