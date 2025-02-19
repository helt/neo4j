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
package org.neo4j.coreedge.discovery;

import java.io.File;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.locks.LockSupport;

import org.neo4j.cluster.ClusterSettings;
import org.neo4j.cluster.InstanceId;
import org.neo4j.coreedge.server.CoreEdgeClusterSettings;
import org.neo4j.coreedge.server.AdvertisedSocketAddress;
import org.neo4j.coreedge.server.core.CoreGraphDatabase;
import org.neo4j.coreedge.server.edge.EdgeGraphDatabase;
import org.neo4j.coreedge.raft.NoLeaderTimeoutException;
import org.neo4j.coreedge.raft.roles.Role;
import org.neo4j.graphdb.GraphDatabaseService;
import org.neo4j.graphdb.factory.GraphDatabaseSettings;
import org.neo4j.kernel.GraphDatabaseDependencies;
import org.neo4j.kernel.configuration.Config;
import org.neo4j.kernel.ha.HaSettings;
import org.neo4j.kernel.impl.factory.GraphDatabaseFacade;

import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static java.util.stream.Collectors.joining;

import static org.neo4j.concurrent.Futures.combine;
import static org.neo4j.helpers.collection.Iterables.first;
import static org.neo4j.helpers.collection.MapUtil.stringMap;

public class Cluster
{
    public static final String CLUSTER_NAME = "core-neo4j";

    private final File parentDir;
    private final DiscoveryServiceFactory discoveryServiceFactory;
    private Set<CoreGraphDatabase> coreServers = new HashSet<>();
    private Set<EdgeGraphDatabase> edgeServers = new HashSet<>();

    Cluster( File parentDir, int noOfCoreServers, int noOfEdgeServers, DiscoveryServiceFactory discoveryServiceFactory )
            throws ExecutionException, InterruptedException
    {
        this.discoveryServiceFactory = discoveryServiceFactory;
        List<AdvertisedSocketAddress> initialHosts = buildAddresses( noOfCoreServers );
        this.parentDir = parentDir;

        ExecutorService executor = Executors.newCachedThreadPool();
        try
        {
            startCoreServers( executor, noOfCoreServers, initialHosts );
            startEdgeServers( executor, noOfEdgeServers, initialHosts );
        }
        finally
        {
            executor.shutdownNow();
        }
    }

    public static Cluster start( File parentDir, int noOfCoreServers, int noOfEdgeServers,
                                 DiscoveryServiceFactory discoveryServiceFactory )
            throws ExecutionException, InterruptedException
    {
        return new Cluster( parentDir, noOfCoreServers, noOfEdgeServers, discoveryServiceFactory );
    }

    public static Cluster start( File parentDir, int noOfCoreServers, int noOfEdgeServers )
            throws ExecutionException, InterruptedException
    {
        return new Cluster( parentDir, noOfCoreServers, noOfEdgeServers, new HazelcastDiscoveryServiceFactory() );
    }

    private static File coreServerStoreDirectory( File parentDir, int serverId )
    {
        return new File( parentDir, "server-core-" + serverId );
    }

    public static File edgeSeverStoreDirectory( File parentDir, int serverId )
    {
        return new File( parentDir, "server-edge-" + serverId );
    }

    private static Map<String, String> serverParams( String serverType, int serverId, String initialHosts )
    {
        Map<String, String> params = stringMap();
        params.put( "org.neo4j.server.database.mode", serverType );
        params.put( ClusterSettings.cluster_name.name(), CLUSTER_NAME );
        params.put( ClusterSettings.server_id.name(), String.valueOf( serverId ) );
        params.put( CoreEdgeClusterSettings.initial_core_cluster_members.name(), initialHosts );
        return params;
    }

    private static List<AdvertisedSocketAddress> buildAddresses( int noOfCoreServers )
    {
        List<AdvertisedSocketAddress> addresses = new ArrayList<>();
        for ( int i = 0; i < noOfCoreServers; i++ )
        {
            int port = 5000 + i;
            addresses.add( new AdvertisedSocketAddress( new InetSocketAddress( "localhost", port ) ) );
        }
        return addresses;
    }

    private void startCoreServers( ExecutorService executor, final int noOfCoreServers, List<AdvertisedSocketAddress>
            addresses )
            throws InterruptedException, ExecutionException
    {
        List<Callable<CoreGraphDatabase>> coreServerSuppliers = new ArrayList<>();
        for ( int i = 0; i < noOfCoreServers; i++ )
        {
            // start up a core server
            final int serverId = i;
            coreServerSuppliers.add( () -> startCoreServer( serverId, noOfCoreServers, addresses ) );
        }

        Future<List<CoreGraphDatabase>> coreServerFutures = combine( executor.invokeAll( coreServerSuppliers ) );
        this.coreServers.addAll( coreServerFutures.get() );
    }

    private void startEdgeServers( ExecutorService executor, int noOfEdgeServers, final List<AdvertisedSocketAddress>
            addresses )
            throws InterruptedException, ExecutionException
    {
        List<Callable<EdgeGraphDatabase>> edgeServerSuppliers = new ArrayList<>();

        for ( int i = 0; i < noOfEdgeServers; i++ )
        {
            // start up an edge server
            final int serverId = i;
            edgeServerSuppliers.add( () -> startEdgeServer( serverId, addresses ) );
        }

        Future<List<EdgeGraphDatabase>> edgeServerFutures = combine( executor.invokeAll( edgeServerSuppliers ) );
        this.edgeServers.addAll( edgeServerFutures.get() );
    }

    public CoreGraphDatabase startCoreServer( int serverId, int clusterSize, List<AdvertisedSocketAddress> addresses )
    {
        int clusterPort = 5000 + serverId;
        int txPort = 6000 + serverId;
        int raftPort = 7000 + serverId;

        String initialHosts = addresses.stream().map( AdvertisedSocketAddress::toString ).collect( joining( "," ) );

        final Map<String, String> params = serverParams( "CORE", serverId, initialHosts );
        params.put( CoreEdgeClusterSettings.cluster_listen_address.name(), "localhost:" + clusterPort );

        params.put( CoreEdgeClusterSettings.transaction_advertised_address.name(), "localhost:" + txPort );
        params.put( CoreEdgeClusterSettings.transaction_listen_address.name(), "127.0.0.1:" + txPort );
        params.put( CoreEdgeClusterSettings.raft_advertised_address.name(), "localhost:" + raftPort );
        params.put( CoreEdgeClusterSettings.raft_listen_address.name(), "127.0.0.1:" + raftPort );

        params.put( CoreEdgeClusterSettings.expected_core_cluster_size.name(), String.valueOf( clusterSize ) );
        params.put( HaSettings.pull_interval.name(), String.valueOf( 5 ) );
        params.put( GraphDatabaseSettings.pagecache_memory.name(), "100M" );

        final File storeDir = coreServerStoreDirectory( parentDir, serverId );
        return new CoreGraphDatabase( storeDir, params, GraphDatabaseDependencies.newDependencies(),
                discoveryServiceFactory );
    }

    public EdgeGraphDatabase startEdgeServer( int serverId, List<AdvertisedSocketAddress> addresses )
    {
        final File storeDir = edgeSeverStoreDirectory( parentDir, serverId );
        return startEdgeServer( serverId, storeDir, addresses );
    }

    private EdgeGraphDatabase startEdgeServer( int serverId, File storeDir, List<AdvertisedSocketAddress> addresses )
    {
        String initialHosts = addresses.stream().map( AdvertisedSocketAddress::toString ).collect( joining( "," ) );

        final Map<String, String> params = serverParams( "EDGE", serverId, initialHosts );
        params.put( HaSettings.pull_interval.name(), String.valueOf( 5 ) );
        params.put( GraphDatabaseSettings.pagecache_memory.name(), "100M" );
        return new EdgeGraphDatabase( storeDir, params, GraphDatabaseDependencies.newDependencies(),
                discoveryServiceFactory );
    }

    public void shutdown()
    {
        ExecutorService executor = Executors.newCachedThreadPool();
        List<Callable<Object>> serverShutdownSuppliers = new ArrayList<>();
        for ( final CoreGraphDatabase coreServer : coreServers )
        {
            serverShutdownSuppliers.add( () -> {
                coreServer.shutdown();
                return null;
            } );
        }

        try
        {
            combine( executor.invokeAll( serverShutdownSuppliers ) ).get();
        }
        catch ( InterruptedException | ExecutionException e )
        {
            e.printStackTrace();
        }
        finally
        {
            executor.shutdown();
        }

        for ( EdgeGraphDatabase edgeServer : edgeServers )
        {
            edgeServer.shutdown();
        }
    }

    public CoreGraphDatabase getCoreServerById( int serverId )
    {
        for ( CoreGraphDatabase coreServer : coreServers )
        {
            if ( serverIdFor( coreServer ).toIntegerIndex() == serverId )
            {
                return coreServer;
            }
        }
        return null;
    }

    public void removeCoreServerWithServerId( int serverId )
    {
        CoreGraphDatabase serverToRemove = getCoreServerById( serverId );

        if (serverToRemove != null )
        {
            removeCoreServer( serverToRemove );
        }
        else
        {
            throw new RuntimeException( "Could not remove core server with server id " + serverId );
        }
    }

    public void removeCoreServer( CoreGraphDatabase serverToRemove )
    {
        serverToRemove.shutdown();
        coreServers.remove( serverToRemove );
    }

    public void removeEdgeServerWithServerId( int serverId )
    {
        EdgeGraphDatabase serverToRemove = null;
        for ( EdgeGraphDatabase edgeServer : edgeServers )
        {
            if ( serverIdFor( edgeServer ).toIntegerIndex() == serverId )
            {
                edgeServer.shutdown();
                serverToRemove = edgeServer;
            }
        }

        if ( serverToRemove == null )
        {
            throw new RuntimeException( "Could not remove edge server with server id " + serverId );
        }
        else
        {
            edgeServers.remove( serverToRemove );
        }
    }

    public void addCoreServerWithServerId( int serverId, int intendedClusterSize )
    {
        Config config = first( coreServers ).getDependencyResolver().resolveDependency( Config.class );
        List<AdvertisedSocketAddress> advertisedAddress = config.get( CoreEdgeClusterSettings
                .initial_core_cluster_members );

        coreServers.add( startCoreServer( serverId, intendedClusterSize, advertisedAddress ) );
    }

    public void addEdgeServerWithFileLocation( int serverId )
    {
        Config config = coreServers.iterator().next().getDependencyResolver().resolveDependency( Config.class );
        List<AdvertisedSocketAddress> advertisedAddresses = config.get( CoreEdgeClusterSettings
                .initial_core_cluster_members );

        edgeServers.add( startEdgeServer( serverId, advertisedAddresses ) );
    }

    private InstanceId serverIdFor( GraphDatabaseFacade graphDatabaseFacade )
    {
        return graphDatabaseFacade.getDependencyResolver().resolveDependency( Config.class )
                .get( ClusterSettings.server_id );
    }

    public Set<CoreGraphDatabase> coreServers()
    {
        return coreServers;
    }

    public Set<EdgeGraphDatabase> edgeServers()
    {
        return edgeServers;
    }

    public GraphDatabaseService findAnEdgeServer()
    {
        return edgeServers.iterator().next();
    }

    public CoreGraphDatabase getLeader()
    {
        for ( CoreGraphDatabase coreServer : coreServers )
        {
            if ( coreServer.getRole().equals( Role.LEADER ) )
            {
                return coreServer;
            }
        }
        return null;
    }

    public CoreGraphDatabase findLeader( long leaderWaitTimeout ) throws NoLeaderTimeoutException
    {
        long leaderWaitEndTime = leaderWaitTimeout + System.currentTimeMillis();
        CoreGraphDatabase leader;
        while ( (leader = getLeader()) == null && (System.currentTimeMillis() < leaderWaitEndTime) )
        {
            LockSupport.parkNanos( MILLISECONDS.toNanos( 1000 ) );
        }

        if ( leader == null )
        {
            throw new NoLeaderTimeoutException();
        }

        return leader;
    }

    public int numberOfCoreServers()
    {
        CoreGraphDatabase aCoreGraphDb = coreServers.iterator().next();
        CoreDiscoveryService coreDiscoveryService = aCoreGraphDb.getDependencyResolver()
                .resolveDependency( CoreDiscoveryService.class );
        return coreDiscoveryService.currentTopology().getNumberOfCoreServers();
    }

    public int numberOfEdgeServers()
    {
        EdgeGraphDatabase edge = edgeServers.iterator().next();
        EdgeDiscoveryService lifecycle = edge.getDependencyResolver()
                .resolveDependency( EdgeDiscoveryService.class );
        return lifecycle.currentTopology().getNumberOfEdgeServers();
    }

    public void addEdgeServerWithFileLocation( File edgeDatabaseStoreFileLocation )
    {
        Config config = coreServers.iterator().next().getDependencyResolver().resolveDependency( Config.class );
        List<AdvertisedSocketAddress> advertisedAddresses = config.get( CoreEdgeClusterSettings
                .initial_core_cluster_members );


        edgeServers.add( startEdgeServer( 999, edgeDatabaseStoreFileLocation, advertisedAddresses ) );
    }
}
