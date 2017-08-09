package com.dataloom.data.mapstores;

import com.codahale.metrics.annotation.Timed;
import com.dataloom.data.EntityKey;
import com.dataloom.data.hazelcast.EntitySets;
import com.dataloom.mapstores.TestDataFactory;
import com.datastax.driver.core.BoundStatement;
import com.datastax.driver.core.RegularStatement;
import com.datastax.driver.core.ResultSet;
import com.datastax.driver.core.Row;
import com.datastax.driver.core.Session;
import com.datastax.driver.core.querybuilder.Insert;
import com.hazelcast.config.MapConfig;
import com.hazelcast.config.MapIndexConfig;
import com.hazelcast.config.MapStoreConfig;
import com.kryptnostic.datastore.cassandra.CommonColumns;
import com.kryptnostic.datastore.cassandra.RowAdapters;
import com.kryptnostic.rhizome.cassandra.CassandraTableBuilder;
import com.kryptnostic.rhizome.mapstores.SelfRegisteringMapStore;
import com.kryptnostic.rhizome.mapstores.cassandra.AbstractStructuredCassandraPartitionKeyValueStore;
import java.util.Collection;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class EntityKeyIdsMapstore extends AbstractStructuredCassandraPartitionKeyValueStore<EntityKey, UUID> {
    private static final Logger logger = LoggerFactory.getLogger( EntityKeyIdsMapstore.class );
    private final SelfRegisteringMapStore<UUID, EntityKey> ekm;

    public EntityKeyIdsMapstore(
            SelfRegisteringMapStore<UUID, EntityKey> ekm,
            String mapName,
            Session session,
            CassandraTableBuilder tableBuilder ) {
        super( mapName, session, tableBuilder );
        this.ekm = ekm;
    }

    @Override
    public EntityKey generateTestKey() {
        return TestDataFactory.entityKey();
    }

    @Override
    public UUID generateTestValue() {
        return UUID.randomUUID();
    }

    @Override
    protected BoundStatement bind( EntityKey key, BoundStatement bs ) {
        return bs.set( CommonColumns.ENTITY_KEY.cql(), key, EntityKey.class );
    }

    @Override
    protected BoundStatement bind( EntityKey key, UUID value, BoundStatement bs ) {
        return bs.set( CommonColumns.ENTITY_KEY.cql(), key, EntityKey.class ).setUUID( CommonColumns.ID.cql(), value );
    }

    @Override
    protected RegularStatement loadAllKeysQuery() {
        return tableBuilder.buildLoadAllPartitionKeysQuery();
    }

    @Override
    protected EntityKey mapKey( Row rs ) {
        return rs.get( CommonColumns.ENTITY_KEY.cql(), EntityKey.class );
    }

    @Override
    protected UUID mapValue( ResultSet rs ) {
        Row r = rs.one();
        return r == null ? null : RowAdapters.id( r );
    }

    @Override
    @Timed
    public UUID load( EntityKey key ) {
        UUID id = super.load( key );

        if ( id != null ) {
            return id;
        }

        do {
            id = UUID.randomUUID();
        } while ( ekm.load( id ) != null );

        store( key, id );

        return id;
    }

    @Override
    public Map<EntityKey, UUID> loadAll( Collection<EntityKey> keys ) {
        return keys.stream()
                .parallel()
                .unordered()
                .distinct()
                .collect( Collectors.toMap( Function.identity(), this::load ) );
    }

    //Only safe to not do LWT as long as everything is going through idService
    @Override
    protected Insert storeQuery() {
        return tableBuilder.buildStoreQuery();//.ifNotExists();
    }

    @Override public MapStoreConfig getMapStoreConfig() {
        return super.getMapStoreConfig().setWriteDelaySeconds( 5 );
    }

    @Override public MapConfig getMapConfig() {
        return super.getMapConfig()
                .addMapIndexConfig( new MapIndexConfig( DataMapstore.KEY_ENTITY_SET_ID, false ) )
                .addMapIndexConfig( new MapIndexConfig( DataMapstore.KEY_SYNC_ID, false ) );
    }
}