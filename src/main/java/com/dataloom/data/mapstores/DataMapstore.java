/*
 * Copyright (C) 2017. OpenLattice, Inc
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
 * You can contact the owner of the copyright at support@openlattice.com
 *
 */

package com.dataloom.data.mapstores;

import static com.kryptnostic.datastore.cassandra.CommonColumns.ENTITYID;
import static com.kryptnostic.datastore.cassandra.CommonColumns.ENTITY_SET_ID;
import static com.kryptnostic.datastore.cassandra.CommonColumns.SYNCID;

import com.dataloom.data.EntityKey;
import com.dataloom.data.storage.CassandraEntityDatastore;
import com.dataloom.data.storage.EntityBytes;
import com.dataloom.edm.type.PropertyType;
import com.datastax.driver.core.BoundStatement;
import com.datastax.driver.core.PreparedStatement;
import com.datastax.driver.core.ResultSet;
import com.datastax.driver.core.ResultSetFuture;
import com.datastax.driver.core.Row;
import com.datastax.driver.core.Session;
import com.datastax.driver.core.querybuilder.QueryBuilder;
import com.datastax.driver.core.querybuilder.Select;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.collect.HashMultimap;
import com.google.common.collect.SetMultimap;
import com.google.common.hash.HashFunction;
import com.google.common.hash.Hashing;
import com.hazelcast.config.InMemoryFormat;
import com.hazelcast.config.MapConfig;
import com.hazelcast.config.MapIndexConfig;
import com.hazelcast.config.MapStoreConfig;
import com.kryptnostic.conductor.rpc.odata.Table;
import com.kryptnostic.datastore.cassandra.CommonColumns;
import com.kryptnostic.datastore.cassandra.RowAdapters;
import com.kryptnostic.rhizome.cassandra.CassandraTableBuilder;
import com.kryptnostic.rhizome.mapstores.SelfRegisteringMapStore;
import com.kryptnostic.rhizome.mapstores.cassandra.AbstractStructuredCassandraMapstore;
import java.nio.ByteBuffer;
import java.util.Map.Entry;
import java.util.UUID;
import java.util.stream.Stream;

/**
 * @author Matthew Tamayo-Rios &lt;matthew@openlattice.com&gt;
 */
public class DataMapstore
        extends AbstractStructuredCassandraMapstore<UUID, EntityBytes> {
    private static final HashFunction hf = Hashing.murmur3_128();
    private final PreparedStatement readEntityKeysForEntitySetQuery;
    //    private final LoadingCache<UUID, PropertyType> propertyTypes;
    private final ObjectMapper      mapper;

    public DataMapstore(
            String mapName,
            CassandraTableBuilder tableBuilder,
            Session session,
            SelfRegisteringMapStore<UUID, PropertyType> ptMapStore,
            ObjectMapper mapper ) {
        super( mapName, session, tableBuilder );
        this.readEntityKeysForEntitySetQuery = prepareReadEntityKeysForEntitySetQuery( session );
        //        this.propertyTypes = CacheBuilder.newBuilder().expireAfterAccess( 1, TimeUnit.MINUTES )
        //                .build( new CacheLoader<UUID, PropertyType>() {
        //                    @Override public PropertyType load( UUID key ) throws Exception {
        //                        return ptMapStore.load( key );
        //                    }
        //                } );
        this.mapper = mapper;
    }

    @Override public UUID generateTestKey() {
        return null;

    }

    @Override public EntityBytes generateTestValue() {
        return null;
    }

    @Override protected BoundStatement bind( UUID key, BoundStatement bs ) {
        return bs.setUUID( CommonColumns.ID.cql(), key );
    }

    @Override
    protected PreparedStatement prepareLoadQuery() {
        return session.prepare( Table.DATA.getBuilder().buildLoadAllQuery().where( CommonColumns.ENTITY_SET_ID.eq() )
                .and( SYNCID.eq() )
                .and( CassandraEntityDatastore.partitionIndexClause() )
                .and( CommonColumns.ENTITYID.eq() ) );

    }

    @Override protected Stream<ResultSetFuture> asyncStore( UUID key, EntityBytes value ) {

        SetMultimap<UUID, byte[]> properties = value.getDetails();
        return properties
                .entries()
                .parallelStream()
                .map( this::bindProperty )
                .map( bs -> bind( key, value, bs ) )
                .map( session::executeAsync );
    }

    @Override
    protected BoundStatement bind(
            UUID key, EntityBytes value, BoundStatement bs ) {
        final EntityKey entityKey = value.getKey();
        return bind( key, bs )
                .setUUID( CommonColumns.ENTITY_SET_ID.cql(), entityKey.getEntitySetId() )
                .setUUID( SYNCID.cql(), entityKey.getSyncId() )
                .setString( CommonColumns.ENTITYID.cql(), entityKey.getEntityId() );
    }

    private BoundStatement bindProperty( Entry<UUID, byte[]> property ) {
        return getStoreQuery().bind()
                .setUUID( CommonColumns.PROPERTY_TYPE_ID.cql(), property.getKey() )
                .setBytes( CommonColumns.PROPERTY_VALUE.cql(),
                        ByteBuffer.wrap( hf.hashBytes( property.getValue() ).asBytes() ) )
                .setBytes( CommonColumns.PROPERTY_BUFFER.cql(), ByteBuffer.wrap( property.getValue() ) );

    }

    @Override
    public Iterable<UUID> loadAllKeys() {

        return null;
        //        return StreamUtil.stream( session
        //                .execute( currentSyncs(  ) ) )
        //                .parallel()
        //                .map( this::getEntityKeys )
        //                .map( ResultSetFuture::getUninterruptibly )
        //                .flatMap( StreamUtil::stream )
        //                .map( RowAdapters::entityKeyFromData )
        //                .unordered()
        //                .distinct()::iterator;
    }

    @Override protected UUID mapKey( Row row ) {
        return RowAdapters.id( row );
    }

    @Override protected EntityBytes mapValue( ResultSet rs ) {

        final SetMultimap<UUID, byte[]> m = HashMultimap.create();
        EntityKey ek = null;
        for ( Row row : rs ) {
            if ( ek == null ) {
                ek = RowAdapters.entityKeyFromData( row );
            }
            UUID propertyTypeId = row.getUUID( CommonColumns.PROPERTY_TYPE_ID.cql() );
            String entityId = row.getString( CommonColumns.ENTITYID.cql() );
            ByteBuffer property = row.getBytes( CommonColumns.PROPERTY_BUFFER.cql() );
            m.put( propertyTypeId, property.array() );
            //            if ( propertyTypeId != null ) {
            //                PropertyType pt = propertyTypes.getUnchecked( propertyTypeId );
            //                m.put( propertyTypeId,
            //                        CassandraSerDesFactory.deserializeValue( mapper,
            //                                row.getBytes( CommonColumns.PROPERTY_BUFFER.cql() ),
            //                                pt.getDatatype(),
            //                                entityId ) );
            //
            //            }
        }
        if ( ek == null ) {
            return null;
        }
        return new EntityBytes( ek, m );
    }

    public ResultSetFuture getEntityKeys( Row row ) {
        final UUID entitySetId = RowAdapters.entitySetId( row );
        final UUID syncId = RowAdapters.currentSyncId( row );
        return session.executeAsync( readEntityKeysForEntitySetQuery.bind()
                .setUUID( CommonColumns.ENTITY_SET_ID.cql(), entitySetId )
                .setUUID( SYNCID.cql(), syncId ) );
    }

    @Override public MapStoreConfig getMapStoreConfig() {
        return super.getMapStoreConfig();//.setInitialLoadMode( InitialLoadMode.EAGER );
    }

    @Override
    public MapConfig getMapConfig() {
        return super.getMapConfig()
                .setInMemoryFormat( InMemoryFormat.OBJECT )
                .addMapIndexConfig( new MapIndexConfig( "entitySetId", false ) )
                .addMapIndexConfig( new MapIndexConfig( "syncId", false ) );
    }

    public static Select currentSyncs( ) {
        return QueryBuilder.select( CommonColumns.ENTITY_SET_ID.cql(), CommonColumns.CURRENT_SYNC_ID.cql() )
                .distinct().from( Table.SYNC_IDS.getKeyspace(), Table.SYNC_IDS.getName() );

    }

    public static PreparedStatement prepareReadEntityKeysForEntitySetQuery( Session session ) {
        return session.prepare( QueryBuilder.select( ENTITY_SET_ID.cql(), SYNCID.cql(), ENTITYID.cql() )
                .from( Table.DATA.getKeyspace(), Table.DATA.getName() )
                .where( ENTITY_SET_ID.eq() )
                .and( SYNCID.eq() )
                .and( CassandraEntityDatastore.partitionIndexClause() ) );
    }

}
