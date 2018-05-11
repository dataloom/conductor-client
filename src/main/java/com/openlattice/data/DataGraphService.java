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

package com.openlattice.data;

import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;
import com.google.common.collect.HashMultimap;
import com.google.common.collect.SetMultimap;
import com.google.common.eventbus.EventBus;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.ListeningExecutorService;
import com.hazelcast.core.HazelcastInstance;
import com.hazelcast.core.IMap;
import com.openlattice.analysis.requests.TopUtilizerDetails;
import com.openlattice.data.analytics.IncrementableWeightId;
import com.openlattice.data.events.EntityDataCreatedEvent;
import com.openlattice.data.requests.Association;
import com.openlattice.data.requests.AssociationRequest;
import com.openlattice.data.requests.Entity;
import com.openlattice.data.requests.EntityRequest;
import com.openlattice.data.storage.HazelcastEntityDatastore;
import com.openlattice.datastore.exceptions.ResourceNotFoundException;
import com.openlattice.edm.EntitySet;
import com.openlattice.edm.type.PropertyType;
import com.openlattice.graph.core.Graph;
import com.openlattice.graph.core.objects.NeighborTripletSet;
import com.openlattice.graph.edge.EdgeKey;
import com.openlattice.hazelcast.HazelcastMap;
import org.apache.commons.collections4.keyvalue.MultiKey;
import org.apache.olingo.commons.api.edm.EdmPrimitiveTypeKind;
import org.apache.olingo.commons.api.edm.FullQualifiedName;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * @author Matthew Tamayo-Rios &lt;matthew@openlattice.com&gt;
 */
public class DataGraphService implements DataGraphManager {
    private static final Logger logger = LoggerFactory
            .getLogger( DataGraphService.class );
    private final ListeningExecutorService executor;
    private final Cache<MultiKey, IncrementableWeightId[]> queryCache = CacheBuilder.newBuilder()
            .maximumSize( 1000 )
            .expireAfterWrite( 30, TimeUnit.SECONDS )
            .build();
    private EventBus                 eventBus;
    private Graph                    lm;
    private EntityKeyIdService       idService;
    private EntityDatastore          eds;
    // Get entity type id by entity set id, cached.
    // TODO HC: Local caching is needed because this would be called very often, so direct calls to IMap should be
    // minimized. Nonetheless, this certainly should be refactored into EdmService or something.
    private IMap<UUID, EntitySet>    entitySets;
    private LoadingCache<UUID, UUID> typeIds;

    public DataGraphService(
            HazelcastInstance hazelcastInstance,
            HazelcastEntityDatastore eds,
            Graph lm,
            EntityKeyIdService ids,
            ListeningExecutorService executor,
            EventBus eventBus ) {
        this.lm = lm;
        this.idService = ids;
        this.eds = eds;
        this.executor = executor;
        this.eventBus = eventBus;

        this.entitySets = hazelcastInstance.getMap( HazelcastMap.ENTITY_SETS.name() );
        this.typeIds = CacheBuilder.newBuilder()
                .maximumSize( 100000 ) // 100K * 16 = 16000K = 16MB
                .build( new CacheLoader<UUID, UUID>() {

                    @Override
                    public UUID load( UUID key ) throws Exception {
                        return entitySets.get( key ).getEntityTypeId();
                    }
                } );
    }

    @Override
    public EntitySetData<FullQualifiedName> getEntitySetData(
            UUID entitySetId,
            UUID syncId,
            LinkedHashSet<String> orderedPropertyNames,
            Map<UUID, PropertyType> authorizedPropertyTypes ) {
        return eds.getEntitySetData( entitySetId, syncId, orderedPropertyNames, authorizedPropertyTypes );
    }

    @Override
    public void deleteEntitySetData( UUID entitySetId ) {
        eds.deleteEntitySetData( entitySetId );
        // TODO delete all vertices
    }

    @Override
    public SetMultimap<FullQualifiedName, Object> getEntity(
            UUID entityKeyId, Map<UUID, PropertyType> authorizedPropertyTypes ) {
        EntityKey entityKey = idService.getEntityKey( entityKeyId );
        return eds.getEntity( entityKey.getEntitySetId(),
                entityKey.getSyncId(),
                entityKey.getEntityId(),
                authorizedPropertyTypes );
    }

    @Override
    public void updateEntity(
            UUID id,
            SetMultimap<UUID, Object> entityDetails,
            Map<UUID, EdmPrimitiveTypeKind> authorizedPropertiesWithDataType ) {
        EntityKey elementReference = idService.getEntityKey( id );
        updateEntity( elementReference, entityDetails, authorizedPropertiesWithDataType );
    }

    @Override
    public void updateEntity(
            EntityKey entityKey,
            SetMultimap<UUID, Object> entityDetails,
            Map<UUID, EdmPrimitiveTypeKind> authorizedPropertiesWithDataType ) {
        eds.updateEntity( entityKey, entityDetails, authorizedPropertiesWithDataType );
    }

    @Override
    public void deleteEntity( EntityDataKey edk ) {
        lm.deleteVertex( edk.getEntityKeyId() );
        eds.deleteEntity( edk );
    }

    @Override
    public void deleteAssociation( EdgeKey key ) {
        EntityKey entityKey = idService.getEntityKey( key.getEdgeEntityKeyId() );
        lm.deleteEdge( key );
        eds.deleteEntity( new EntityDataKey( entityKey.getEntitySetId(), key.getEdgeEntityKeyId() ) );
    }

    @Override
    public UUID createEntity(
            UUID entitySetId,
            UUID syncId,
            String entityId,
            SetMultimap<UUID, Object> entityDetails,
            Map<UUID, EdmPrimitiveTypeKind> authorizedPropertiesWithDataType )
            throws ExecutionException, InterruptedException {

        final EntityKey key = new EntityKey( entitySetId, entityId, syncId );
        createEntity( key, entityDetails, authorizedPropertiesWithDataType )
                .forEach( DataGraphService::tryGetAndLogErrors );
        return idService.getEntityKeyId( key );
    }

    @Override
    public void createEntities(
            UUID entitySetId,
            UUID syncId,
            Map<String, SetMultimap<UUID, Object>> entities,
            Map<UUID, EdmPrimitiveTypeKind> authorizedPropertiesWithDataType )
            throws ExecutionException, InterruptedException {
        entities.entrySet()
                .parallelStream()
                .flatMap(
                        entity -> {
                            final EntityKey key = new EntityKey( entitySetId, entity.getKey(), syncId );
                            return createEntity( key, entity.getValue(), authorizedPropertiesWithDataType );
                        } )
                .forEach( DataGraphService::tryGetAndLogErrors );
    }

    private Stream<ListenableFuture> createEntity(
            EntityKey key,
            SetMultimap<UUID, Object> details,
            Map<UUID, EdmPrimitiveTypeKind> authorizedPropertiesWithDataType ) {
        final ListenableFuture reservationAndVertex = idService.getEntityKeyIdAsync( key );
        final Stream<ListenableFuture> writes = eds.updateEntityAsync( key, details, authorizedPropertiesWithDataType );
        return Stream.concat( Stream.of( reservationAndVertex ), writes );
    }

    private Stream<ListenableFuture> createEntity(
            UUID entityKeyId,
            EntityKey key,
            SetMultimap<UUID, Object> details,
            Map<UUID, EdmPrimitiveTypeKind> authorizedPropertiesWithDataType ) {
        final Stream<ListenableFuture> writes = eds.updateEntityAsync( key, details, authorizedPropertiesWithDataType );
        return Stream.concat( Stream.of( Futures.immediateFuture( entityKeyId ) ), writes );
    }

    public void replaceEntity(
            EntityDataKey edk,
            SetMultimap<UUID, Object> entity,
            Map<UUID, EdmPrimitiveTypeKind> propertyTypes ) {
        EntityKey key = idService.getEntityKey( edk.getEntityKeyId() );
        eds.deleteEntity( edk );
        eds.updateEntityAsync( key, entity, propertyTypes ).forEach( DataGraphService::tryGetAndLogErrors );

        propertyTypes.entrySet().forEach( entry -> {
            if ( entry.getValue().equals( EdmPrimitiveTypeKind.Binary ) ) { entity.removeAll( entry.getKey() ); }
        } );

        eventBus.post( new EntityDataCreatedEvent( edk, entity, false ) );
    }

    @Override
    public void createAssociations(
            UUID entitySetId,
            UUID syncId,
            Set<Association> associations,
            Map<UUID, EdmPrimitiveTypeKind> authorizedPropertiesWithDataType )
            throws InterruptedException, ExecutionException {
        // List<ListenableFuture> futures = new ArrayList<ListenableFuture>( 2 * associations.size() );

        associations
                .parallelStream()
                .flatMap( association -> {
                    UUID edgeId = idService.getEntityKeyId( association.getKey() );

                    Stream<ListenableFuture> writes = eds.updateEntityAsync( association.getKey(),
                            association.getDetails(),
                            authorizedPropertiesWithDataType );

                    UUID srcId = idService.getEntityKeyId( association.getSrc() );
                    UUID srcTypeId = typeIds.getUnchecked( association.getSrc().getEntitySetId() );
                    UUID srcSetId = association.getSrc().getEntitySetId();
                    UUID srcSyncId = association.getSrc().getSyncId();
                    UUID dstId = idService.getEntityKeyId( association.getDst() );
                    UUID dstTypeId = typeIds.getUnchecked( association.getDst().getEntitySetId() );
                    UUID dstSetId = association.getDst().getEntitySetId();
                    UUID dstSyncId = association.getDst().getSyncId();
                    UUID edgeTypeId = typeIds.getUnchecked( association.getKey().getEntitySetId() );
                    UUID edgeSetId = association.getKey().getEntitySetId();

                    ListenableFuture addEdge = lm
                            .addEdgeAsync( srcId,
                                    srcTypeId,
                                    srcSetId,
                                    srcSyncId,
                                    dstId,
                                    dstTypeId,
                                    dstSetId,
                                    dstSyncId,
                                    edgeId,
                                    edgeTypeId,
                                    edgeSetId );
                    return Stream.concat( writes, Stream.of( addEdge ) );
                } ).forEach( DataGraphService::tryGetAndLogErrors );
    }

    private ListenableFuture createEdge(
            UUID associationId,
            UUID srcId,
            UUID dstId,
            EntityKey associationKey,
            EntityKey srcKey,
            EntityKey dstKey ) {

        UUID srcTypeId = typeIds.getUnchecked( srcKey.getEntitySetId() );
        UUID srcSetId = srcKey.getEntitySetId();
        UUID srcSyncId = srcKey.getSyncId();
        UUID dstTypeId = typeIds.getUnchecked( dstKey.getEntitySetId() );
        UUID dstSetId = dstKey.getEntitySetId();
        UUID dstSyncId = dstKey.getSyncId();
        UUID edgeTypeId = typeIds.getUnchecked( associationKey.getEntitySetId() );
        UUID edgeSetId = associationKey.getEntitySetId();

        return lm.addEdgeAsync(
                srcId,
                srcTypeId,
                srcSetId,
                srcSyncId,
                dstId,
                dstTypeId,
                dstSetId,
                dstSyncId,
                associationId,
                edgeTypeId,
                edgeSetId );
    }

    @Override
    public void createEntitiesAndAssociations(
            Set<Entity> entities,
            Set<Association> associations,
            Map<UUID, Map<UUID, EdmPrimitiveTypeKind>> authorizedPropertiesByEntitySetId )
            throws InterruptedException, ExecutionException {
        // Map<EntityKey, UUID> idsRegistered = new HashMap<>();

        entities.parallelStream()
                .flatMap( entity -> createEntity( entity.getKey(),
                        entity.getDetails(),
                        authorizedPropertiesByEntitySetId.get( entity.getKey().getEntitySetId() ) ) )
                .forEach( DataGraphService::tryGetAndLogErrors );

        associations.parallelStream().flatMap( association -> {
            UUID srcId = idService.getEntityKeyId( association.getSrc() );
            UUID dstId = idService.getEntityKeyId( association.getDst() );
            if ( srcId == null || dstId == null ) {
                String err = String.format(
                        "Edge %s cannot be created because some vertices failed to register for an id.",
                        association.toString() );
                logger.debug( err );
                return Stream.of( Futures.immediateFailedFuture( new ResourceNotFoundException( err ) ) );
            } else {
                UUID edgeId = idService.getEntityKeyId( association.getKey() );

                Stream<ListenableFuture> writes = eds.updateEntityAsync( association.getKey(),
                        association.getDetails(),
                        authorizedPropertiesByEntitySetId.get( association.getKey().getEntitySetId() ) );

                return Stream.concat( writes,
                        Stream.of( createEdge( edgeId,
                                srcId,
                                dstId,
                                association.getKey(),
                                association.getSrc(),
                                association.getDst() ) ) );
            }
        } ).forEach( DataGraphService::tryGetAndLogErrors );
    }

    @Override
    public void bulkCreateEntityData(
            Set<EntityRequest> entities,
            Set<AssociationRequest> associations,
            Map<UUID, Map<UUID, EdmPrimitiveTypeKind>> authorizedPropertiesByEntitySetId )
            throws ExecutionException, InterruptedException {

        Set<UUID> entityKeyIds = Stream.concat( entities.stream().map( entity -> entity.getEntityKeyId() ),
                associations.stream().flatMap( association -> Stream
                        .of( association.getEntityKeyId(), association.getSrc(), association.getDst() ) ) ).collect(
                        Collectors.toSet() );

        Map<UUID, EntityKey> entityKeys = idService.getEntityKeys( entityKeyIds );

        entities.parallelStream()
                .filter( entity -> entity.getDetails().size() > 0 )
                .flatMap( entity -> createEntity( entity.getEntityKeyId(),
                        entityKeys.get( entity.getEntityKeyId() ),
                        entity.getDetails(),
                        authorizedPropertiesByEntitySetId
                                .get( entityKeys.get( entity.getEntityKeyId() ).getEntitySetId() ) ) )
                .forEach( DataGraphService::tryGetAndLogErrors );

        associations.parallelStream().flatMap( association -> {
            EntityKey associationKey = entityKeys.get( association.getEntityKeyId() );
            EntityKey srcKey = entityKeys.get( association.getSrc() );
            EntityKey dstKey = entityKeys.get( association.getDst() );
            if ( srcKey == null || dstKey == null ) {
                String err = String.format(
                        "Edge %s cannot be created because some vertex ids do not exist.",
                        association.toString() );
                logger.debug( err );
                return Stream.of( Futures.immediateFailedFuture( new ResourceNotFoundException( err ) ) );
            } else {
                Stream<ListenableFuture> writes = eds.updateEntityAsync( associationKey,
                        association.getDetails(),
                        authorizedPropertiesByEntitySetId.get( associationKey.getEntitySetId() ) );

                return Stream.concat( writes,
                        Stream.of( createEdge( association.getEntityKeyId(),
                                association.getSrc(),
                                association.getDst(),
                                associationKey,
                                srcKey,
                                dstKey ) ) );
            }
        } ).forEach( DataGraphService::tryGetAndLogErrors );

    }

    @Override
    public Iterable<SetMultimap<Object, Object>> getTopUtilizers(
            UUID entitySetId,
            UUID syncId,
            List<TopUtilizerDetails> topUtilizerDetailsList,
            int numResults,
            Map<UUID, PropertyType> authorizedPropertyTypes )
            throws InterruptedException, ExecutionException {
        /*
         * ByteBuffer queryId; try { queryId = ByteBuffer.wrap( ObjectMappers.getSmileMapper().writeValueAsBytes(
         * topUtilizerDetailsList ) ); } catch ( JsonProcessingException e1 ) { logger.debug(
         * "Unable to generate query id." ); return null; }
         */
        IncrementableWeightId[] maybeUtilizers = queryCache
                .getIfPresent( new MultiKey( entitySetId, topUtilizerDetailsList ) );
        final IncrementableWeightId[] utilizers;
        // if ( !eds.queryAlreadyExecuted( queryId ) ) {
        if ( maybeUtilizers == null ) {
            //            utilizers = new TopUtilizers( numResults );
            SetMultimap<UUID, UUID> srcFilters = HashMultimap.create();
            SetMultimap<UUID, UUID> dstFilters = HashMultimap.create();

            topUtilizerDetailsList.forEach( details -> {
                ( details.getUtilizerIsSrc() ? srcFilters : dstFilters ).
                        putAll( details.getAssociationTypeId(), details.getNeighborTypeIds() );

            } );
            utilizers = lm.computeGraphAggregation( numResults, entitySetId, syncId, srcFilters, dstFilters );

            queryCache.put( new MultiKey( entitySetId, topUtilizerDetailsList ), utilizers );
        } else {
            utilizers = maybeUtilizers;
        }

        return eds.getEntities( entitySetId, utilizers, authorizedPropertyTypes )::iterator;
    }

    @Override
    public NeighborTripletSet getNeighborEntitySets( UUID entitySetId, UUID syncId ) {
        return lm.getNeighborEntitySets( entitySetId, syncId );
    }

    public static void tryGetAndLogErrors( ListenableFuture<?> f ) {
        try {
            f.get();
        } catch ( InterruptedException | ExecutionException e ) {
            logger.error( "Future execution failed.", e );
        }
    }

}
