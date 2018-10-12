

/*
 * Copyright (C) 2018. OpenLattice, Inc.
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

package com.openlattice.data.storage;

import com.codahale.metrics.annotation.Timed;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.collect.ArrayListMultimap;
import com.google.common.collect.HashMultimap;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSetMultimap;
import com.google.common.collect.ListMultimap;
import com.google.common.collect.Maps;
import com.google.common.collect.Multimaps;
import com.google.common.collect.SetMultimap;
import com.google.common.collect.Sets;
import com.google.common.eventbus.EventBus;
import com.google.common.util.concurrent.ListeningExecutorService;
import com.hazelcast.core.HazelcastInstance;
import com.openlattice.authorization.ForbiddenException;
import com.openlattice.data.Entity;
import com.openlattice.data.EntityDataKey;
import com.openlattice.data.EntityDataValue;
import com.openlattice.data.EntityDatastore;
import com.openlattice.data.EntityKey;
import com.openlattice.data.EntityKeyIdService;
import com.openlattice.data.EntitySetData;
import com.openlattice.data.PropertyMetadata;
import com.openlattice.data.events.EntitiesDeletedEvent;
import com.openlattice.data.events.EntitiesUpsertedEvent;
import com.openlattice.data.events.EntityDataCreatedEvent;
import com.openlattice.data.events.EntityDataDeletedEvent;
import com.openlattice.datastore.cassandra.CassandraSerDesFactory;
import com.openlattice.edm.events.EntitySetDeletedEvent;
import com.openlattice.edm.type.PropertyType;
import com.openlattice.postgres.JsonDeserializer;
import com.openlattice.postgres.streams.PostgresIterable.PostgresIterator;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;
import javax.inject.Inject;

import org.apache.olingo.commons.api.edm.EdmPrimitiveTypeKind;
import org.apache.olingo.commons.api.edm.FullQualifiedName;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static com.google.common.collect.Maps.transformValues;

public class HazelcastEntityDatastore implements EntityDatastore {
    private static final int    BATCH_INDEX_THRESHOLD = 256;
    private static final Logger logger                = LoggerFactory
            .getLogger( HazelcastEntityDatastore.class );

    private final ObjectMapper                   mapper;
    private final HazelcastInstance              hazelcastInstance;
    private final EntityKeyIdService             idService;
    private final ListeningExecutorService       executor;
    private final PostgresDataManager            pdm;
    private final PostgresEntityDataQueryService dataQueryService;

    @Inject
    private EventBus eventBus;

    public HazelcastEntityDatastore(
            HazelcastInstance hazelastInstance,
            ListeningExecutorService executor,
            ObjectMapper mapper,
            EntityKeyIdService idService,
            PostgresDataManager pdm,
            PostgresEntityDataQueryService dataQueryService ) {
        this.dataQueryService = dataQueryService;
        this.pdm = pdm;
        this.mapper = mapper;
        this.idService = idService;
        this.hazelcastInstance = hazelastInstance;
        this.executor = executor;
    }

    @Override
    @Timed
    public EntitySetData<FullQualifiedName> getEntitySetData(
            Set<UUID> entitySetIds,
            LinkedHashSet<String> orderedPropertyNames,
            Map<UUID, Map<UUID, PropertyType>> authorizedPropertyTypes,
            boolean isLinking ) {
        return new EntitySetData<>(
                orderedPropertyNames,
                dataQueryService.streamableEntitySet( entitySetIds,
                        authorizedPropertyTypes,
                        EnumSet.of( MetadataOption.VERSION, MetadataOption.LAST_WRITE, MetadataOption.LAST_INDEX ),
                        isLinking,
                        Optional.empty() ) );
    }

    @Timed
    @Override
    public int createOrUpdateEntities(
            UUID entitySetId,
            Map<UUID, Map<UUID, Set<Object>>> entities,
            Map<UUID, PropertyType> authorizedPropertyTypes ) {
        int count = dataQueryService.upsertEntities( entitySetId, entities, authorizedPropertyTypes );
        signalCreatedEntitiesCompat( entitySetId, entities );
        return count;
    }

    @Timed
    @Override
    public int integrateEntities(
            UUID entitySetId,
            Map<UUID, Map<UUID, Set<Object>>> entities,
            Map<UUID, PropertyType> authorizedPropertyTypes ) {
        int count = dataQueryService.upsertEntities( entitySetId, entities, authorizedPropertyTypes );
        signalCreatedEntities( entitySetId,
                dataQueryService.getEntitiesById( entitySetId, authorizedPropertyTypes, entities.keySet(), false ) );
        return count;
    }

    @Timed
    @Override public int replaceEntities(
            UUID entitySetId,
            Map<UUID, Map<UUID, Set<Object>>> entities,
            Map<UUID, PropertyType> authorizedPropertyTypes ) {
        final var count = dataQueryService.replaceEntities( entitySetId, entities, authorizedPropertyTypes );
        signalCreatedEntities( entitySetId,
                dataQueryService.getEntitiesById( entitySetId, authorizedPropertyTypes, entities.keySet(), false ) );
        return count;
    }

    @Timed
    @Override public int partialReplaceEntities(
            UUID entitySetId,
            Map<UUID, Map<UUID, Set<Object>>> entities,
            Map<UUID, PropertyType> authorizedPropertyTypes ) {
        final var count = dataQueryService.partialReplaceEntities( entitySetId, entities, authorizedPropertyTypes );
        signalCreatedEntities( entitySetId,
                dataQueryService.getEntitiesById( entitySetId, authorizedPropertyTypes, entities.keySet(), false ) );
        return count;
    }

    //TODO: Fix LATTICE-1185
    @Deprecated
    private void signalCreatedEntitiesCompat( UUID entitySetId, Map<UUID, Map<UUID, Set<Object>>> entities ) {
        signalCreatedEntities( entitySetId,
                ImmutableMap.copyOf( Maps.transformValues( entities, HazelcastEntityDatastore::setMultimapFromMap ) ) );

    }

    private static SetMultimap<UUID, Object> setMultimapFromMap( Map<UUID, Set<Object>> m ) {
        final SetMultimap<UUID, Object> entity = HashMultimap.create();
        m.forEach( entity::putAll );
        return entity;
    }

    private void signalCreatedEntities( UUID entitySetId, Map<UUID, SetMultimap<UUID, Object>> entities ) {
        if ( entities.size() < BATCH_INDEX_THRESHOLD ) {
            eventBus.post( new EntitiesUpsertedEvent( entitySetId, entities, false ) );
        }
    }

    private void signalUpdatedEntities( UUID entitySetId, Map<UUID, SetMultimap<UUID, Object>> entities ) {
        if ( entities.size() < BATCH_INDEX_THRESHOLD ) {
            eventBus.post( new EntitiesUpsertedEvent( entitySetId, entities, true ) );
        }
    }

    private void signalDeletedEntities( UUID entitySetId, Set<UUID> entityKeyIds ) {
        if ( entityKeyIds.size() < BATCH_INDEX_THRESHOLD ) {
            eventBus.post( new EntitiesDeletedEvent( entitySetId, entityKeyIds ) );
        }
    }

    private void signalEntitySetDeleted( UUID entitySetId ) {
        eventBus.post( new EntitySetDeletedEvent( entitySetId ) );
    }

    @Timed
    @Override public int replacePropertiesInEntities(
            UUID entitySetId,
            Map<UUID, SetMultimap<UUID, Map<ByteBuffer, Object>>> replacementProperties,
            Map<UUID, PropertyType> authorizedPropertyTypes ) {
        return dataQueryService
                .replacePropertiesInEntities( entitySetId, replacementProperties, authorizedPropertyTypes );
    }

    @Timed
    @Override public int clearEntitySet(
            UUID entitySetId, Map<UUID, PropertyType> authorizedPropertyTypes ) {
        final var count = dataQueryService.clearEntitySet( entitySetId, authorizedPropertyTypes );
        signalEntitySetDeleted( entitySetId );
        return count;
    }

    @Timed
    @Override public int clearEntities(
            UUID entitySetId, Set<UUID> entityKeyIds, Map<UUID, PropertyType> authorizedPropertyTypes ) {
        final var count = dataQueryService.clearEntities( entitySetId, entityKeyIds, authorizedPropertyTypes );
        signalDeletedEntities( entitySetId, entityKeyIds );
        return count;
    }

    @Override
    @Timed public EntitySetData<FullQualifiedName> getEntities(
            Map<UUID, Optional<Set<UUID>>> entityKeyIds,
            LinkedHashSet<String> orderedPropertyTypes,
            Map<UUID, Map<UUID, PropertyType>> authorizedPropertyTypes,
            boolean isLinking ) {
        //If the query generated exceed 33.5M UUIDs good chance that it exceed Postgres's 1 GB max query buffer size
        return new EntitySetData<>(
                orderedPropertyTypes,
                dataQueryService.streamableEntitySet(
                        entityKeyIds,
                        authorizedPropertyTypes,
                        EnumSet.noneOf( MetadataOption.class ),
                        isLinking,
                        Optional.empty()) );
    }

    @Override
    @Timed
    public Stream<SetMultimap<FullQualifiedName, Object>> getEntities(
            UUID entitySetId,
            Set<UUID> ids,
            Map<UUID, Map<UUID, PropertyType>> authorizedPropertyTypes,
            boolean isLinking ) {
        //If the query generated exceed 33.5M UUIDs good chance that it exceed Postgres's 1 GB max query buffer size
        return dataQueryService.streamableEntitySet(
                entitySetId,
                ids,
                authorizedPropertyTypes,
                EnumSet.noneOf( MetadataOption.class ),
                isLinking,
                Optional.empty() ).stream();
    }

    @Override
    @Timed
    public ListMultimap<UUID, SetMultimap<FullQualifiedName, Object>> getEntitiesAcrossEntitySets(
            SetMultimap<UUID, UUID> entitySetIdsToEntityKeyIds,
            Map<UUID, Map<UUID, PropertyType>> authorizedPropertyTypesByEntitySet ) {

        final int keyCount = entitySetIdsToEntityKeyIds.keySet().size();
        final int avgValuesPerKey =
                entitySetIdsToEntityKeyIds.size() == 0 ? 0 : entitySetIdsToEntityKeyIds.size() / keyCount;
        final ListMultimap<UUID, SetMultimap<FullQualifiedName, Object>> entities
                = ArrayListMultimap.create( keyCount, avgValuesPerKey );

        Multimaps
                .asMap( entitySetIdsToEntityKeyIds )
                .forEach( ( entitySetId, entityKeyIds ) -> entities
                        .putAll( entitySetId,
                                dataQueryService.streamableEntitySet(
                                        entitySetId,
                                        entityKeyIds,
                                        Map.of( entitySetId, authorizedPropertyTypesByEntitySet.get( entitySetId ) ), //TODO
                                        EnumSet.noneOf( MetadataOption.class ),
                                        false, // TODO: do we need linked entity sets here??
                                        Optional.empty() )
                        )
                );

        return entities;
    }

    private EntityDataKey fromEntityKey( EntityKey entityKey ) {
        UUID entityKeyId = idService.getEntityKeyId( entityKey );
        return new EntityDataKey( entityKey.getEntitySetId(), entityKeyId );
    }

    public SetMultimap<FullQualifiedName, Object> fromEntityBytes(
            UUID id,
            SetMultimap<UUID, ByteBuffer> properties,
            Map<UUID, PropertyType> propertyType ) {
        SetMultimap<FullQualifiedName, Object> entityData = HashMultimap.create();
        if ( properties == null ) {
            logger.error( "Properties retreived from aggregator for id {} are null.", id );
            return HashMultimap.create();
        }
        properties.entries().forEach( prop -> {
            PropertyType pt = propertyType.get( prop.getKey() );
            if ( pt != null ) {
                entityData.put( pt.getType(), CassandraSerDesFactory.deserializeValue( mapper,
                        prop.getValue(),
                        pt.getDatatype(),
                        id::toString ) );
            }
        } );
        return entityData;
    }

    public SetMultimap<Object, Object> untypedFromEntityBytes(
            UUID id,
            SetMultimap<UUID, ByteBuffer> properties,
            Map<UUID, PropertyType> propertyType ) {
        if ( properties == null ) {
            logger.error( "Data for id {} was null", id );
            return HashMultimap.create();
        }
        SetMultimap<Object, Object> entityData = HashMultimap.create();

        properties.entries().forEach( prop -> {
            PropertyType pt = propertyType.get( prop.getKey() );
            if ( pt != null ) {
                entityData.put( pt.getType(), CassandraSerDesFactory.deserializeValue( mapper,
                        prop.getValue(),
                        pt.getDatatype(),
                        id::toString ) );
            }
        } );
        return entityData;
    }

    @Deprecated
    @Timed
    public Stream<UUID> createEntityData(
            UUID entitySetId,
            Map<String, SetMultimap<UUID, Object>> entities,
            Map<UUID, PropertyType> authorizedPropertyTypes ) {

        return entities.entrySet().stream().map(
                entity ->
                        createData(
                                entitySetId,
                                authorizedPropertyTypes,
                                entity.getKey(),
                                entity.getValue() ) );

    }

    /*creating

     */
    @Timed
    public UUID createData(
            UUID entitySetId,
            Map<UUID, PropertyType> authorizedPropertyTypes,
            String entityId,
            SetMultimap<UUID, Object> entityDetails ) {
        //TODO: Keep full local copy of PropertyTypes EDM
        Map<UUID, EdmPrimitiveTypeKind> authorizedPropertiesWithDataType = transformValues( authorizedPropertyTypes,
                PropertyType::getDatatype );
        Set<UUID> authorizedProperties = authorizedPropertiesWithDataType.keySet();
        // does not write the row if some property values that user is trying to write to are not authorized.
        //TODO: Don't fail silently
        //TODO: Move all access checks up to controller.
        if ( !authorizedProperties.containsAll( entityDetails.keySet() ) ) {
            String msg = String
                    .format( "Entity %s not written because the following properties are not authorized: %s",
                            entityId,
                            Sets.difference( entityDetails.keySet(), authorizedProperties ) );
            logger.error( msg );
            throw new ForbiddenException( msg );
        }

        SetMultimap<UUID, Object> normalizedPropertyValues;
        try {
            normalizedPropertyValues = JsonDeserializer.validateFormatAndNormalize( Multimaps.asMap( entityDetails ),
                    authorizedPropertiesWithDataType );
        } catch ( Exception e ) {
            logger.error( "Entity {} not written because some property values are of invalid format.",
                    entityId,
                    e );
            return null;
        }

        //Get an id for this object and write that data.
        //TODO: Push the getting id layer up.
        final UUID id = idService.getEntityKeyId( entitySetId, entityId );
        final EntityDataKey edk = new EntityDataKey( entitySetId, id );
        dataQueryService.upsertEntities( entitySetId,
                ImmutableMap.of( id, Multimaps.asMap( normalizedPropertyValues ) ),
                authorizedPropertyTypes );
        eventBus.post( new EntityDataCreatedEvent( edk, normalizedPropertyValues, true ) );
        return id;
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
    public int deleteEntitySetData( UUID entitySetId, Map<UUID, PropertyType> authorizedPropertyTypes ) {
        logger.info( "Deleting data of entity set: {}", entitySetId );
        int deleteCount = dataQueryService.deleteEntitySet( entitySetId, authorizedPropertyTypes );
        logger.info( "Finished deletion of entity set {}. Deleted {} rows.", entitySetId, deleteCount );
        return deleteCount;
    }

    @Override
    public int deleteEntities(
            UUID entitySetId,
            Set<UUID> entityKeyId,
            Map<UUID, PropertyType> authorizedPropertyTypes ) {

        int deleteCount = dataQueryService.deleteEntities( entitySetId, entityKeyId, authorizedPropertyTypes );

        entityKeyId.forEach( id -> {
            eventBus.post( new EntityDataDeletedEvent( new EntityDataKey( entitySetId, id ) ) );
        } );
        return deleteCount;
    }

    public static SetMultimap<Object, Object> fromEntityDataValue(
            EntityDataKey dataKey,
            EntityDataValue dataValue,
            long count,
            Map<UUID, PropertyType> propertyTypes ) {
        SetMultimap entityData = fromEntityDataValue( dataValue, propertyTypes );
        entityData.put( "id", dataKey.getEntityKeyId() );
        entityData.put( "count", count );
        return entityData;
    }

    public static SetMultimap<Object, Object> fromEntityDataValue(
            EntityDataKey dataKey,
            EntityDataValue dataValue,
            Map<UUID, PropertyType> propertyTypes ) {
        SetMultimap entityData = fromEntityDataValue( dataValue, propertyTypes );
        entityData.put( "id", dataKey.getEntityKeyId() );
        return entityData;
    }

    public static SetMultimap<FullQualifiedName, Object> fromEntity(
            Entity edv,
            Map<UUID, PropertyType> propertyTypes ) {
        SetMultimap<FullQualifiedName, Object> entityData = HashMultimap.create();
        SetMultimap<UUID, Object> entityDataByUUID = edv.getProperties();

        for ( Entry<UUID, PropertyType> propertyTypeEntry : propertyTypes.entrySet() ) {
            UUID propertyTypeId = propertyTypeEntry.getKey();
            entityData.put( propertyTypeEntry.getValue().getType(), entityDataByUUID.get( propertyTypeId ) );
        }
        return entityData;
    }

    public static SetMultimap<FullQualifiedName, Object> fromEntityDataValue(
            EntityDataValue edv,
            Map<UUID, PropertyType> propertyTypes ) {
        SetMultimap<FullQualifiedName, Object> entityData = HashMultimap.create();
        Map<UUID, Map<Object, PropertyMetadata>> properties = edv.getProperties();
        for ( Entry<UUID, PropertyType> propertyTypeEntry : propertyTypes.entrySet() ) {
            UUID propertyTypeId = propertyTypeEntry.getKey();
            Map<Object, PropertyMetadata> valueMap = properties.get( propertyTypeId );
            if ( valueMap != null ) {
                PropertyType propertyType = propertyTypeEntry.getValue();
                entityData.putAll( propertyType.getType(), valueMap.keySet() );
            }
        }
        return entityData;
    }

    public static SetMultimap<UUID, Object> fromEntityDataValue(
            EntityDataValue edv,
            Set<UUID> authorizedPropertyTypes ) {
        SetMultimap<UUID, Object> entityData = HashMultimap.create();
        Map<UUID, Map<Object, PropertyMetadata>> properties = edv.getProperties();
        for ( UUID propertyTypeId : authorizedPropertyTypes ) {
            Map<Object, PropertyMetadata> valueMap = properties.get( propertyTypeId );
            if ( valueMap != null ) {
                entityData.putAll( propertyTypeId, valueMap.keySet() );
            }
        }
        return entityData;
    }
}
