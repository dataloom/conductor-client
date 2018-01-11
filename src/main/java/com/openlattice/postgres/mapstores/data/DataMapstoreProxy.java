/*
 * Copyright (C) 2018. OpenLattice, Inc
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

package com.openlattice.postgres.mapstores.data;

import com.dataloom.edm.EntitySet;
import com.dataloom.edm.type.EntityType;
import com.dataloom.edm.type.PropertyType;
import com.dataloom.hazelcast.HazelcastMap;
import com.dataloom.streams.StreamUtil;
import com.hazelcast.config.MapConfig;
import com.hazelcast.config.MapIndexConfig;
import com.hazelcast.config.MapStoreConfig;
import com.hazelcast.core.MapStore;
import com.kryptnostic.rhizome.mapstores.TestableSelfRegisteringMapStore;
import com.openlattice.data.EntityDataKey;
import com.openlattice.data.EntityDataMetadata;
import com.openlattice.data.EntityDataValue;
import com.openlattice.data.PropertyMetadata;
import com.openlattice.postgres.DataTables;
import com.openlattice.postgres.PostgresTableDefinition;
import com.zaxxer.hikari.HikariDataSource;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.UUID;

/**
 * @author Matthew Tamayo-Rios &lt;matthew@openlattice.com&gt;
 */
public class DataMapstoreProxy implements TestableSelfRegisteringMapStore<EntityDataKey, EntityDataValue> {
    public static final String VERSION    = "version";
    public static final String LAST_WRITE = "lastWrite";
    public static final String LAST_INDEX = "lastIndex";
    public static final String KEY_ENTITY_SET_ID = "key#entitySetId";

    private final Map<UUID, EntityDataMapstore>              entitySetMapstores; //Entity Set ID -> Mapstore for Entity Set Table
    private final Map<UUID, Map<UUID, PropertyDataMapstore>> propertyDataMapstores;

    private final HikariDataSource             hds;
    private final MapStore<UUID, PropertyType> propertyTypes;
    private final MapStore<UUID, EntitySet>    entitySets;
    private final MapStore<UUID, EntityType>   entityTypes;

    public DataMapstoreProxy(
            // Map<UUID, EntityDataMapstore> entitySetMapstores,
            //            Map<UUID, Map<UUID, PropertyDataMapstore>> propertyDataMapstores,
            HikariDataSource hds,
            MapStore<UUID, PropertyType> propertyTypes,
            MapStore<UUID, EntitySet> entitySets,
            MapStore<UUID, EntityType> entityTypes ) {
        this.entitySetMapstores = new HashMap<>();
        this.propertyDataMapstores = new HashMap<>();
        this.hds = hds;
        this.propertyTypes = propertyTypes;
        this.entitySets = entitySets;
        this.entityTypes = entityTypes;
    }

    public EntityDataMapstore getMapstore( UUID entitySetId ) {
        return entitySetMapstores.computeIfAbsent( entitySetId, this::newEntitySetMapStore );
    }

    protected EntityDataMapstore newEntitySetMapStore( UUID entitySetId ) {
        PostgresTableDefinition table = DataTables.buildEntitySetTableDefinition( entitySetId );
        return new EntityDataMapstore( hds, table );
    }

    @Override public String getMapName() {
        return HazelcastMap.ENTITY_DATA.name();
    }

    @Override public String getTable() {
        return getMapName();
    }

    @Override public EntityDataKey generateTestKey() {
        return null;
    }

    @Override public EntityDataValue generateTestValue() {
        return null;
    }

    @Override
    public MapStoreConfig getMapStoreConfig() {
        return new MapStoreConfig()
                .setImplementation( this )
                .setEnabled( true )
                .setWriteDelaySeconds( 5 );
    }

    @Override
    public MapConfig getMapConfig() {
        return new MapConfig( getMapName() )
                .setMapStoreConfig( getMapStoreConfig() )
                .addMapIndexConfig( new MapIndexConfig( KEY_ENTITY_SET_ID,false ) )
                .addMapIndexConfig( new MapIndexConfig( VERSION, true ) )
                .addMapIndexConfig( new MapIndexConfig( LAST_WRITE, true ) )
                .addMapIndexConfig( new MapIndexConfig( LAST_INDEX, true ) );
    }

    @Override public void store( EntityDataKey key, EntityDataValue value ) {
        final UUID entitySetId = key.getEntitySetId();
        final UUID entityKeyId = key.getEntityKeyId();
        final EntityDataMapstore edms = getMapstore( key.getEntitySetId() );

        //Store the metadata
        edms.store( entityKeyId, value.getMetadata() );

        //Store the property values
        final Map<UUID, Map<Object, PropertyMetadata>> properties = value.getProperties();
        for ( Entry<UUID, Map<Object, PropertyMetadata>> propertyEntry : properties.entrySet() ) {
            final UUID propertyTypeId = propertyEntry.getKey();
            final PropertyDataMapstore propertyDataMapstore = propertyDataMapstores
                    .computeIfAbsent( entitySetId, esId -> new HashMap<>() )
                    .computeIfAbsent( propertyTypeId, ptId -> newPropertyDataMapstore( entitySetId, ptId ) );
            propertyDataMapstore.store( entityKeyId, propertyEntry.getValue() );
        }

    }

    @Override public void storeAll( Map<EntityDataKey, EntityDataValue> map ) {
        for ( Entry<EntityDataKey, EntityDataValue> e : map.entrySet() ) {
            store( e.getKey(), e.getValue() );
        }
    }

    @Override public void delete( EntityDataKey key ) {
        EntityDataMapstore edms = getMapstore( key.getEntitySetId() );
        edms.delete( key.getEntityKeyId() );
    }

    @Override public void deleteAll( Collection<EntityDataKey> keys ) {
        for ( EntityDataKey key : keys ) {
            delete( key );
        }
    }

    @Override public EntityDataValue load( EntityDataKey key ) {
        final UUID entitySetId = key.getEntitySetId();
        final UUID entityKeyId = key.getEntityKeyId();
        final EntitySet es = entitySets.load( entitySetId );

        //If entity set is not found return immediately.
        if ( es == null ) {
            return null;
        }

        final EntityType entityType = entityTypes.load( es.getEntityTypeId() );

        if ( entityType == null ) {
            return null;
        }

        final Set<UUID> propertyTypes = entityType.getProperties();

        final EntityDataMapstore edms = getMapstore( entitySetId );
        final EntityDataMetadata metadata = edms.load( entityKeyId );
        final Map<UUID, Map<Object, PropertyMetadata>> properties = new HashMap<>();
        for ( UUID propertyTypeId : propertyTypes ) {
            final PropertyDataMapstore propertyDataMapstore = propertyDataMapstores
                    .computeIfAbsent( entitySetId, esId -> new HashMap<>() )
                    .computeIfAbsent( propertyTypeId, ptId -> newPropertyDataMapstore( entitySetId, ptId ) );
            final Map<Object, PropertyMetadata> propertiesOfType = propertyDataMapstore.load( entityKeyId );
            properties.put( propertyTypeId, propertiesOfType );
        }

        return new EntityDataValue( metadata, properties );

    }

    @Override public Map<EntityDataKey, EntityDataValue> loadAll( Collection<EntityDataKey> keys ) {
        Map<EntityDataKey, EntityDataValue> m = new HashMap<>();
        keys.forEach( k -> {
            EntityDataValue edv = load( k );
            if ( k != null ) {
                m.put( k, edv );
            }
        } );
        return m;
    }

    @Override public Iterable<EntityDataKey> loadAllKeys() {
        return () -> StreamUtil.stream( entitySets.loadAllKeys() )
                .flatMap( entitySetId -> StreamUtil
                        .stream( getMapstore( entitySetId ).loadAllKeys() )
                        .map( entityKeyId -> new EntityDataKey( entitySetId, entityKeyId ) ) ).iterator();

    }

    protected PropertyDataMapstore newPropertyDataMapstore( UUID entitySetId, UUID propertyTypeId ) {
        PropertyType propertyType = propertyTypes.load( propertyTypeId );
        PostgresTableDefinition table = DataTables.buildPropertyTableDefinition( entitySetId, propertyType );
        return new PropertyDataMapstore( table, hds );
    }
}
