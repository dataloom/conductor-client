/*
 * Copyright (C) 2017. Kryptnostic, Inc (dba Loom)
 *
 * This program is free software: you can redistribute it and/or modify
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
 *
 * You can contact the owner of the copyright at support@thedataloom.com
 */

package com.dataloom.data.storage;

import static com.kryptnostic.datastore.cassandra.CommonColumns.ENTITYID;

import com.codahale.metrics.annotation.Timed;
import com.dataloom.data.DatasourceManager;
import com.dataloom.data.EntityDatastore;
import com.dataloom.data.EntityKey;
import com.dataloom.data.EntitySetData;
import com.dataloom.data.events.EntityDataCreatedEvent;
import com.dataloom.data.events.EntityDataDeletedEvent;
import com.dataloom.edm.type.PropertyType;
import com.dataloom.graph.core.LoomGraph;
import com.dataloom.linking.HazelcastLinkingGraphs;
import com.dataloom.streams.StreamUtil;
import com.datastax.driver.core.BoundStatement;
import com.datastax.driver.core.PreparedStatement;
import com.datastax.driver.core.ResultSet;
import com.datastax.driver.core.ResultSetFuture;
import com.datastax.driver.core.Session;
import com.datastax.driver.core.querybuilder.Clause;
import com.datastax.driver.core.querybuilder.Insert;
import com.datastax.driver.core.querybuilder.QueryBuilder;
import com.datastax.driver.core.querybuilder.Select;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.base.Optional;
import com.google.common.base.Stopwatch;
import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;
import com.google.common.collect.Iterables;
import com.google.common.collect.SetMultimap;
import com.google.common.eventbus.EventBus;
import com.google.common.hash.HashFunction;
import com.google.common.hash.Hashing;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.kryptnostic.conductor.rpc.odata.Table;
import com.kryptnostic.datastore.cassandra.CassandraSerDesFactory;
import com.kryptnostic.datastore.cassandra.CommonColumns;
import com.kryptnostic.datastore.cassandra.RowAdapters;
import com.kryptnostic.rhizome.cassandra.CassandraTableBuilder;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import javax.inject.Inject;
import org.apache.commons.lang3.StringUtils;
import org.apache.olingo.commons.api.edm.EdmPrimitiveTypeKind;
import org.apache.olingo.commons.api.edm.FullQualifiedName;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class CassandraEntityDatastore implements EntityDatastore {
    private static final int          NUM_PARTITIONS = 256;
    private static final Logger       logger         = LoggerFactory
            .getLogger( CassandraEntityDatastore.class );
    private static final HashFunction hf             = Hashing.murmur3_128();

    private static final LoadingCache<UUID, AtomicInteger> PARTTIONS = CacheBuilder.newBuilder()
            .expireAfterAccess( 1, TimeUnit.MINUTES ).build( new CacheLoader<UUID, AtomicInteger>() {
                @Override public AtomicInteger load( UUID key ) throws Exception {
                    return new AtomicInteger();
                }
            } );

    public static List<Byte> PARTITION_INDEXES = new ArrayList( NUM_PARTITIONS );

    static {
        for ( int i = 0; i < NUM_PARTITIONS; ++i ) {
            PARTITION_INDEXES.add( (byte) i );
        }
    }

    private final Session                session;
    private final ObjectMapper           mapper;
    private final HazelcastLinkingGraphs linkingGraph;
    private final DatasourceManager      dsm;
    private final PreparedStatement      writeDataQuery;
    private final PreparedStatement      entityQuery;
    private final PreparedStatement      entityQueryWithoutPropertyTypes;
    //    private final PreparedStatement      entitySetQuery;
    private final PreparedStatement      entityIdsQuery;
    private final PreparedStatement      deleteEntityQuery;
    private final PreparedStatement      deleteEntitySetQuery;
    private final PreparedStatement      readNumRPCRowsQuery;
    private final PreparedStatement      readEntityKeysForEntitySetQuery;
    private final PreparedStatement      writeUtilizerScoreQuery;
    private final PreparedStatement      readNumTopUtilizerRowsQuery;
    private final PreparedStatement      topUtilizersQueryIdExistsQuery;
    @Inject
    private       EventBus               eventBus;

    public CassandraEntityDatastore(
            Session session,
            ObjectMapper mapper,
            HazelcastLinkingGraphs linkingGraph,
            LoomGraph loomGraph,
            DatasourceManager dsm ) {
        this.session = session;
        this.mapper = mapper;
        this.linkingGraph = linkingGraph;
        this.dsm = dsm;
        CassandraTableBuilder dataTableDefinitions = Table.DATA.getBuilder();

        this.entityQuery = prepareEntityQuery( session, dataTableDefinitions );
        this.entityQueryWithoutPropertyTypes = prepareEntityQueryWithoutPropertyTypes( session, dataTableDefinitions );
        //        this.entitySetQuery = prepareEntitySetQuery( session, dataTableDefinitions );
        this.entityIdsQuery = prepareEntityIdsQuery( session );
        this.writeDataQuery = prepareWriteQuery( session, dataTableDefinitions );

        this.deleteEntityQuery = prepareDeleteEntityQuery( session );
        this.deleteEntitySetQuery = prepareDeleteEntitySetPartitionQuery( session );
        this.readNumRPCRowsQuery = prepareReadNumRPCRowsQuery( session );
        this.readEntityKeysForEntitySetQuery = prepareReadEntityKeysForEntitySetQuery( session );
        this.writeUtilizerScoreQuery = prepareWriteUtilizerScoreQuery( session );
        this.readNumTopUtilizerRowsQuery = prepareReadNumTopUtilizerRowsQuery( session );
        this.topUtilizersQueryIdExistsQuery = prepareTopUtilizersQueryIdExistsQuery( session );
    }

    @Override
    @Timed
    public EntitySetData<FullQualifiedName> getEntitySetData(
            UUID entitySetId,
            UUID syncId,
            LinkedHashSet<String> orderedPropertyNames,
            Map<UUID, PropertyType> authorizedPropertyTypes ) {
        return new EntitySetData<>( orderedPropertyNames,
                getRows( entitySetId, syncId, authorizedPropertyTypes.keySet() )
                        .map( rs -> rowToEntity( rs, authorizedPropertyTypes ) )::iterator );
    }

    @Override
    @Timed
    public SetMultimap<FullQualifiedName, Object> getEntity(
            UUID entitySetId,
            UUID syncId,
            String entityId,
            Map<UUID, PropertyType> authorizedPropertyTypes ) {
        return RowAdapters.entity(
                asyncLoadEntity( entitySetId, entityId, syncId, authorizedPropertyTypes.keySet() ).getUninterruptibly(),
                authorizedPropertyTypes,
                mapper );
    }

    @Override
    @Timed
    public SetMultimap<FullQualifiedName, Object> getEntityPostFiltered(
            UUID entitySetId,
            UUID syncId,
            String entityId,
            Map<UUID, PropertyType> authorizedPropertyTypes ) {
        return RowAdapters.entity(
                asyncLoadEntity( entitySetId, entityId, syncId ).getUninterruptibly(),
                authorizedPropertyTypes,
                mapper );
    }

    @Override
    @Timed
    public ResultSetFuture getEntityAsync(
            UUID entitySetId,
            UUID syncId,
            String entityId,
            Map<UUID, PropertyType> authorizedPropertyTypes ) {
        return asyncLoadEntity( entitySetId, entityId, syncId, authorizedPropertyTypes.keySet() );
    }

    @Override
    public void updateEntity(
            EntityKey entityKey,
            SetMultimap<UUID, Object> entityDetails,
            Map<UUID, EdmPrimitiveTypeKind> authorizedPropertiesWithDataType ) {
        createData( entityKey.getEntitySetId(),
                entityKey.getSyncId(),
                authorizedPropertiesWithDataType,
                authorizedPropertiesWithDataType.keySet(),
                entityKey.getEntityId(),
                entityDetails );
    }

    @Override
    public ListenableFuture<List<ResultSet>> updateEntityAsync(
            EntityKey entityKey,
            SetMultimap<UUID, Object> entityDetails,
            Map<UUID, EdmPrimitiveTypeKind> authorizedPropertiesWithDataType ) {
        return Futures.allAsList( createDataAsync( entityKey.getEntitySetId(),
                entityKey.getSyncId(),
                authorizedPropertiesWithDataType,
                authorizedPropertiesWithDataType.keySet(),
                entityKey.getEntityId(),
                entityDetails ) );
    }

    public Stream<SetMultimap<UUID, Object>> getEntitySetDataIndexedById(
            UUID entitySetId,
            UUID syncId,
            Map<UUID, PropertyType> authorizedPropertyTypes ) {
        return getRows( entitySetId, syncId, authorizedPropertyTypes.keySet() )
                .map( rs -> rowToEntityIndexedById( rs, authorizedPropertyTypes ) );
    }

    public SetMultimap<FullQualifiedName, Object> rowToEntity(
            ResultSet rs,
            Map<UUID, PropertyType> authorizedPropertyTypes ) {
        return RowAdapters.entity( rs, authorizedPropertyTypes, mapper );
    }

    public SetMultimap<UUID, Object> rowToEntityIndexedById(
            ResultSet rs,
            Map<UUID, PropertyType> authorizedPropertyTypes ) {
        return RowAdapters.entityIndexedById( rs, authorizedPropertyTypes, mapper );
    }

    private Stream<ResultSet> getRows(
            UUID entitySetId,
            UUID syncId,
            Set<UUID> authorizedProperties ) {
        // If syncId is not specified, retrieve latest snapshot of entity
        final UUID finalSyncId;
        if ( syncId == null ) {
            finalSyncId = dsm.getCurrentSyncId( entitySetId );
        } else {
            finalSyncId = syncId;
        }

        //        return StreamUtil
        //                .stream( asyncLoadEntitySet( entitySetId, finalSyncId, authorizedProperties ).getUninterruptibly() )
        //                .map( row -> RowAdapters.entity(  ) );
        return getEntityIds( entitySetId, finalSyncId )
                .map( entityId -> asyncLoadEntity( entitySetId, entityId, finalSyncId ) )
                .map( rsf -> {
                    Stopwatch w = Stopwatch.createStarted();
                    ResultSet rs = rsf.getUninterruptibly();
                    logger.info( "Load entity took: {}", w.elapsed( TimeUnit.MILLISECONDS ) );
                    return rs;
                });
                //.map( ResultSetFuture::getUninterruptibly );
    }

    public Stream<String> getEntityIds( UUID entitySetId, UUID syncId ) {
        BoundStatement boundEntityIdsQuery = entityIdsQuery.bind()
                .setUUID( CommonColumns.ENTITY_SET_ID.cql(), entitySetId )
                .setUUID( CommonColumns.SYNCID.cql(), syncId );
        ResultSet entityIds = session.execute( boundEntityIdsQuery );
        return StreamUtil
                .stream( entityIds )
                .parallel()
                .unordered()
                .map( RowAdapters::entityId )
                .distinct()
                .filter( StringUtils::isNotBlank );
    }

    //    public ResultSetFuture asyncLoadEntitySet( UUID entitySetId, UUID syncId, Set<UUID> authorizedProperties ) {
    //        return session.executeAsync( entitySetQuery.bind()
    //                .setUUID( CommonColumns.ENTITY_SET_ID.cql(), entitySetId )
    //                .setUUID( CommonColumns.SYNCID.cql(), syncId )
    //                .setSet( CommonColumns.PRINCIPAL_TYPE.cql(), authorizedProperties ) );
    //    }

    @Override
    public ResultSetFuture asyncLoadEntity(
            UUID entitySetId,
            String entityId,
            UUID syncId,
            Set<UUID> authorizedProperties ) {
        return session.executeAsync( entityQuery.bind()
                .setUUID( CommonColumns.ENTITY_SET_ID.cql(), entitySetId )
                .setString( CommonColumns.ENTITYID.cql(), entityId )
                .setSet( CommonColumns.PROPERTY_TYPE_ID.cql(), authorizedProperties )
                .setUUID( CommonColumns.SYNCID.cql(), syncId ) );
    }

    /*
     * Warning: this loads ALL the properties of the entity, authorized or not.
     */
    @Override
    public ResultSetFuture asyncLoadEntity(
            UUID entitySetId,
            String entityId,
            UUID syncId ) {
        return session.executeAsync( entityQueryWithoutPropertyTypes.bind()
                .setUUID( CommonColumns.ENTITY_SET_ID.cql(), entitySetId )
                .setUUID( CommonColumns.SYNCID.cql(), syncId )
                .setString( CommonColumns.ENTITYID.cql(), entityId ) );
    }

    @Deprecated
    public void createEntityData(
            UUID entitySetId,
            UUID syncId,
            Map<String, SetMultimap<UUID, Object>> entities,
            Map<UUID, EdmPrimitiveTypeKind> authorizedPropertiesWithDataType ) {
        Set<UUID> authorizedProperties = authorizedPropertiesWithDataType.keySet();

        List<ResultSetFuture> results = new ArrayList<ResultSetFuture>();

        entities.entrySet().stream().forEach( entity -> {
            results.addAll( createDataAsync( entitySetId,
                    syncId,
                    authorizedPropertiesWithDataType,
                    authorizedProperties,
                    entity.getKey(),
                    entity.getValue() ) );
        } );

        results.forEach( ResultSetFuture::getUninterruptibly );
    }

    public void createData(
            UUID entitySetId,
            UUID syncId,
            Map<UUID, EdmPrimitiveTypeKind> authorizedPropertiesWithDataType,
            Set<UUID> authorizedProperties,
            String entityId,
            SetMultimap<UUID, Object> entityDetails ) {
        createDataAsync(
                entitySetId,
                syncId,
                authorizedPropertiesWithDataType,
                authorizedProperties,
                entityId,
                entityDetails ).forEach( ResultSetFuture::getUninterruptibly );
    }

    public List<ResultSetFuture> createDataAsync(
            UUID entitySetId,
            UUID syncId,
            Map<UUID, EdmPrimitiveTypeKind> authorizedPropertiesWithDataType,
            Set<UUID> authorizedProperties,
            String entityId,
            SetMultimap<UUID, Object> entityDetails ) {
        List<ResultSetFuture> results = new ArrayList<>();

        // does not write the row if some property values that user is trying to write to are not authorized.
        if ( !authorizedProperties.containsAll( entityDetails.keySet() ) ) {
            logger.error( "Entity {} not written because not all property values are authorized.", entityId );
            return results;
        }

        SetMultimap<UUID, Object> normalizedPropertyValues = null;
        try {
            normalizedPropertyValues = CassandraSerDesFactory.validateFormatAndNormalize( entityDetails,
                    authorizedPropertiesWithDataType );
        } catch ( Exception e ) {
            logger.error( "Entity {} not written because some property values are of invalid format.",
                    entityId, e );
            return results;
        }

        // Stream<Entry<UUID, Object>> authorizedPropertyValues = propertyValues.entries().stream().filter( entry ->
        // authorizedProperties.contains( entry.getKey() ) );
        normalizedPropertyValues.entries().stream()
                .forEach( entry -> {
                    EdmPrimitiveTypeKind datatype = authorizedPropertiesWithDataType
                            .get( entry.getKey() );
                    ByteBuffer pValue = CassandraSerDesFactory.serializeValue(
                            mapper,
                            entry.getValue(),
                            datatype,
                            entityId );
                    /*
                     * Plan here is to use entity_set_id, sync_id, and bucket to distribute data in data table.
                     *
                     */
                    AtomicInteger partitionIndex = PARTTIONS.getUnchecked( entitySetId );

                    //TODO: Considering using hash for all properties.
                    //                    if ( datatype.equals( EdmPrimitiveTypeKind.Binary ) ) {
                    results.add( session.executeAsync(
                            writeDataQuery.bind()
                                    .setUUID( CommonColumns.ENTITY_SET_ID.cql(), entitySetId )
                                    .setByte( CommonColumns.PARTITION_INDEX.cql(),
                                            (byte) partitionIndex.getAndIncrement() )
                                    .setUUID( CommonColumns.SYNCID.cql(), syncId )
                                    .setString( CommonColumns.ENTITYID.cql(), entityId )
                                    .setUUID( CommonColumns.PROPERTY_TYPE_ID.cql(), entry.getKey() )
                                    .setBytes( CommonColumns.PROPERTY_BUFFER.cql(), pValue )
                                    .setBytes( CommonColumns.PROPERTY_VALUE.cql(),
                                            ByteBuffer.wrap( hf.hashBytes( pValue.array() ).asBytes() ) ) ) );
                    //                    } else {
                    //                        results.add( session.executeAsync(
                    //                                writeDataQuery.bind()
                    //                                        .setUUID( CommonColumns.ENTITY_SET_ID.cql(), entitySetId )
                    //                                        .setUUID( CommonColumns.SYNCID.cql(), syncId )
                    //                                        .setByte( CommonColumns.PARTITION_INDEX.cql(),
                    //                                                (byte) partitionIndex.getAndIncrement() )
                    //                                        .setUUID( CommonColumns.PROPERTY_TYPE_ID.cql(), entry.getKey() )
                    //                                        .setBytes( CommonColumns.PROPERTY_VALUE.cql(), pValue )
                    //                                        .setString( CommonColumns.ENTITYID.cql(), entityId ) ) );
                    //                    }
                } );

        Map<UUID, Object> normalizedPropertyValuesAsMap = normalizedPropertyValues.asMap().entrySet().stream().filter(
                entry -> !authorizedPropertiesWithDataType.get( entry.getKey() ).equals( EdmPrimitiveTypeKind.Binary ) )
                .collect( Collectors.toMap( e -> e.getKey(), e -> new HashSet<>( e.getValue() ) ) );

        eventBus.post( new EntityDataCreatedEvent(
                entitySetId,
                Optional.of( syncId ),
                entityId,
                normalizedPropertyValuesAsMap ) );

        return results;
    }

    public void createOrderedRPCData( UUID requestId, double weight, byte[] value ) {
        session.executeAsync( writeDataQuery.bind().setUUID( CommonColumns.RPC_REQUEST_ID.cql(), requestId )
                .setDouble( CommonColumns.RPC_WEIGHT.cql(), weight )
                .setBytes( CommonColumns.RPC_VALUE.cql(), ByteBuffer.wrap( value ) ) );
    }

    public Stream<byte[]> readNumRPCRows( UUID requestId, int numResults ) {
        logger.info( "Reading {} rows of RPC data for request id {}", numResults, requestId );
        BoundStatement bs = readNumRPCRowsQuery.bind().setUUID( CommonColumns.RPC_REQUEST_ID.cql(), requestId )
                .setInt( "numResults", numResults );
        ResultSet rs = session.execute( bs );
        return StreamUtil.stream( rs )
                .map( r -> r.getBytes( CommonColumns.RPC_VALUE.cql() ).array() );
    }

    @Override
    public boolean queryAlreadyExecuted( ByteBuffer queryId ) {
        ResultSet rs = session
                .execute( topUtilizersQueryIdExistsQuery.bind().setBytes( CommonColumns.QUERY_ID.cql(), queryId ) );
        return ( rs.one() != null );
    }

    @Override
    public void writeVertexCount( ByteBuffer queryId, UUID vertexId, double score ) {
        session.execute( writeUtilizerScoreQuery.bind().setBytes( CommonColumns.QUERY_ID.cql(), queryId )
                .setUUID( CommonColumns.VERTEX_ID.cql(), vertexId ).setDouble( CommonColumns.WEIGHT.cql(), score ) );
    }

    @Override
    public Iterable<UUID> readTopUtilizers( ByteBuffer queryId, int numResults ) {
        ResultSet rs = session
                .execute( readNumTopUtilizerRowsQuery.bind().setBytes( CommonColumns.QUERY_ID.cql(), queryId )
                        .setInt( "numResults", numResults ) );
        return Iterables.transform( rs, row -> row.getUUID( CommonColumns.VERTEX_ID.cql() ) );
    }

    /**
     * Delete data of an entity set across ALL sync Ids.
     * <p>
     * Note: this is currently only used when deleting an entity set, which takes care of deleting the data in
     * elasticsearch. If this is ever called without deleting the entity set, logic must be added to delete the data
     * from elasticsearch.
     */
    @SuppressFBWarnings(
            value = "UC_USELESS_OBJECT",
            justification = "results Object is used to execute deletes in batches" )
    public void deleteEntitySetData( UUID entitySetId ) {
        logger.info( "Deleting data of entity set: {}", entitySetId );

        asyncDeleteEntitySet( entitySetId ).forEach( ResultSetFuture::getUninterruptibly );

        logger.info( "Finished deletion of entity set data: {}", entitySetId );
    }

    public Stream<ResultSetFuture> asyncDeleteEntitySet( UUID entitySetId ) {
        return StreamUtil.stream( dsm.getAllSyncIds( entitySetId ) )
                .parallel()
                .flatMap( syncId ->
                        PARTITION_INDEXES
                                .stream()
                                .map( partitionIndex -> deleteEntitySetQuery.bind()
                                        .setByte( CommonColumns.PARTITION_INDEX.cql(), partitionIndex )
                                        .setUUID( CommonColumns.ENTITY_SET_ID.cql(), entitySetId )
                                        .setUUID( CommonColumns.SYNCID.cql(), syncId ) ) )
                .map( session::executeAsync );
    }

    public ResultSetFuture asyncDeleteEntity( UUID entitySetId, String entityId, UUID syncId ) {
        return session.executeAsync( deleteEntityQuery.bind()
                .setUUID( CommonColumns.ENTITY_SET_ID.cql(), entitySetId )
                .setString( CommonColumns.ENTITYID.cql(), entityId )
                .setUUID( CommonColumns.SYNCID.cql(), syncId ) );
    }

    @Override
    public void deleteEntity( EntityKey entityKey ) {
        asyncDeleteEntity( entityKey.getEntitySetId(), entityKey.getEntityId(), entityKey.getSyncId() )
                .getUninterruptibly();
        eventBus.post( new EntityDataDeletedEvent(
                entityKey.getEntitySetId(),
                entityKey.getEntityId(),
                Optional.of( entityKey.getSyncId() ) ) );
    }

    @Override
    public Stream<EntityKey> getEntityKeysForEntitySet( UUID entitySetId, UUID syncId ) {
        return StreamUtil.stream( Iterables.transform( session.execute(
                readEntityKeysForEntitySetQuery.bind()
                        .setUUID( CommonColumns.ENTITY_SET_ID.cql(), entitySetId )
                        .setUUID( CommonColumns.SYNCID.cql(), syncId ) ),
                RowAdapters::entityKeyFromData ) )
                .distinct();
    }

    private static PreparedStatement prepareEntityQuery(
            Session session,
            CassandraTableBuilder ctb ) {
        return session.prepare( entityQuery( ctb ) );
    }

    private static PreparedStatement prepareEntityQueryWithoutPropertyTypes(
            Session session,
            CassandraTableBuilder ctb ) {
        return session.prepare( entityQueryWithoutPropertyTypes( ctb ) );
    }

    //    private static PreparedStatement prepareEntitySetQuery(
    //            Session session,
    //            CassandraTableBuilder ctb ) {
    //        return session.prepare( entitySetQuery( ctb ) );
    //    }

    private static PreparedStatement prepareWriteQuery(
            Session session,
            CassandraTableBuilder ctb ) {
        return session.prepare( writeQuery( ctb ) );
    }

    private static Insert writeQuery( CassandraTableBuilder ctb ) {
        return ctb.buildStoreQuery();
    }

    private static Select.Where entityQuery( CassandraTableBuilder ctb ) {
        return ctb.buildLoadAllQuery()
                .where( CommonColumns.ENTITY_SET_ID.eq() )
                .and( CommonColumns.SYNCID.eq() )
                .and( QueryBuilder.in( CommonColumns.PARTITION_INDEX.cql(), PARTITION_INDEXES ) )
                .and( CommonColumns.ENTITYID.eq() )
                .and( QueryBuilder.in( CommonColumns.PROPERTY_TYPE_ID.cql(),
                        CommonColumns.PROPERTY_TYPE_ID.bindMarker() ) );
    }

    private static Select.Where entityQueryWithoutPropertyTypes( CassandraTableBuilder ctb ) {
        return ctb.buildLoadAllQuery()
                .where( CommonColumns.ENTITY_SET_ID.eq() )
                .and( CommonColumns.SYNCID.eq() )
                .and( QueryBuilder.in( CommonColumns.PARTITION_INDEX.cql(), PARTITION_INDEXES ) )
                .and( CommonColumns.ENTITYID.eq() );
    }

    //    @Deprecated
    //    private static Select.Where entitySetQuery( CassandraTableBuilder ctb ) {
    //        return ctb.buildLoadAllQuery()
    //                .where( CommonColumns.ENTITY_SET_ID.eq() )
    //                .and( CommonColumns.SYNCID.eq() )
    //                .and( partitionIndexClause() )
    //                .and( QueryBuilder.in( CommonColumns.PROPERTY_TYPE_ID.cql(),
    //                        CommonColumns.PROPERTY_TYPE_ID.bindMarker() ) );
    //    }

    private static PreparedStatement prepareEntityIdsQuery( Session session ) {
        return session.prepare( QueryBuilder
                .select()
                //.column( CommonColumns.ENTITY_SET_ID.cql() )
                //.column( CommonColumns.SYNCID.cql() )
                .column( CommonColumns.ENTITYID.cql() )
                .from( Table.DATA.getKeyspace(), Table.DATA.getName() )
                .where( CommonColumns.ENTITY_SET_ID.eq() )
                .and( CommonColumns.SYNCID.eq() )
                .and( partitionIndexClause() ) );
    }

    private static Clause partitionIndexClause() {
        return QueryBuilder.in( CommonColumns.PARTITION_INDEX.cql(), PARTITION_INDEXES );
    }

    private static PreparedStatement prepareReadNumRPCRowsQuery( Session session ) {
        return session.prepare(
                QueryBuilder.select().from( Table.RPC_DATA_ORDERED.getKeyspace(), Table.RPC_DATA_ORDERED.getName() )
                        .where( QueryBuilder.eq( CommonColumns.RPC_REQUEST_ID.cql(),
                                CommonColumns.RPC_REQUEST_ID.bindMarker() ) )
                        .limit( QueryBuilder.bindMarker( "numResults" ) ) );
    }

    private static PreparedStatement prepareReadEntityKeysForEntitySetQuery( Session session ) {
        return session.prepare( QueryBuilder.select( ENTITYID.cql() )
                .from( Table.DATA.getKeyspace(), Table.DATA.getName() )
                .where( CommonColumns.ENTITY_SET_ID.eq() )
                .and( CommonColumns.SYNCID.eq() )
                .and( partitionIndexClause() ) );
    }

    private static PreparedStatement prepareWriteUtilizerScoreQuery( Session session ) {
        return session.prepare( Table.TOP_UTILIZER_DATA.getBuilder().buildStoreQuery() );
    }

    private static PreparedStatement prepareReadNumTopUtilizerRowsQuery( Session session ) {
        return session.prepare(
                QueryBuilder.select().from( Table.TOP_UTILIZER_DATA.getKeyspace(), Table.TOP_UTILIZER_DATA.getName() )
                        .where( QueryBuilder.eq( CommonColumns.QUERY_ID.cql(), CommonColumns.QUERY_ID.bindMarker() ) )
                        .limit( QueryBuilder.bindMarker( "numResults" ) ) );
    }

    private static PreparedStatement prepareTopUtilizersQueryIdExistsQuery( Session session ) {
        return session.prepare(
                QueryBuilder.select().from( Table.TOP_UTILIZER_DATA.getKeyspace(), Table.TOP_UTILIZER_DATA.getName() )
                        .where( QueryBuilder.eq( CommonColumns.QUERY_ID.cql(), CommonColumns.QUERY_ID.bindMarker() ) )
                        .limit( 1 ) );
    }

    private static PreparedStatement prepareDeleteEntitySetPartitionQuery( Session session ) {
        return session.prepare( Table.DATA.getBuilder().buildDeleteByPartitionKeyQuery() );
    }

    private static PreparedStatement prepareDeleteEntityQuery( Session session ) {
        return session.prepare( Table.DATA.getBuilder().buildDeleteByPartitionKeyQuery() );
    }
}
