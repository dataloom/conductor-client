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

package com.openlattice.search;

import static com.openlattice.authorization.EdmAuthorizationHelper.READ_PERMISSION;

import com.codahale.metrics.annotation.Timed;
import com.dataloom.streams.StreamUtil;
import com.google.common.base.Stopwatch;
import com.google.common.collect.HashMultimap;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ListMultimap;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.collect.SetMultimap;
import com.google.common.collect.Sets;
import com.google.common.eventbus.EventBus;
import com.google.common.eventbus.Subscribe;
import com.openlattice.apps.App;
import com.openlattice.apps.AppType;
import com.openlattice.authorization.AccessCheck;
import com.openlattice.authorization.AclKey;
import com.openlattice.authorization.AuthorizationManager;
import com.openlattice.authorization.Permission;
import com.openlattice.authorization.Principal;
import com.openlattice.authorization.Principals;
import com.openlattice.authorization.SecurableObjectResolveTypeService;
import com.openlattice.authorization.securable.SecurableObjectType;
import com.openlattice.conductor.rpc.ConductorElasticsearchApi;
import com.openlattice.data.EntityDataKey;
import com.openlattice.data.EntityKeyIdService;
import com.openlattice.data.events.EntitiesDeletedEvent;
import com.openlattice.data.events.EntitiesUpsertedEvent;
import com.openlattice.data.events.LinkedEntitiesDeletedEvent;
import com.openlattice.data.requests.NeighborEntityDetails;
import com.openlattice.data.requests.NeighborEntityIds;
import com.openlattice.data.storage.EntityDatastore;
import com.openlattice.data.storage.IndexingMetadataManager;
import com.openlattice.data.storage.MetadataOption;
import com.openlattice.datastore.services.EdmManager;
import com.openlattice.edm.EdmConstants;
import com.openlattice.edm.EntitySet;
import com.openlattice.edm.events.AppCreatedEvent;
import com.openlattice.edm.events.AppDeletedEvent;
import com.openlattice.edm.events.AppTypeCreatedEvent;
import com.openlattice.edm.events.AppTypeDeletedEvent;
import com.openlattice.edm.events.AssociationTypeCreatedEvent;
import com.openlattice.edm.events.AssociationTypeDeletedEvent;
import com.openlattice.edm.events.ClearAllDataEvent;
import com.openlattice.edm.events.EntitySetCreatedEvent;
import com.openlattice.edm.events.EntitySetDataDeletedEvent;
import com.openlattice.edm.events.EntitySetDeletedEvent;
import com.openlattice.edm.events.EntitySetMetadataUpdatedEvent;
import com.openlattice.edm.events.EntityTypeCreatedEvent;
import com.openlattice.edm.events.EntityTypeDeletedEvent;
import com.openlattice.edm.events.LinkedEntitySetRemovedEvent;
import com.openlattice.edm.events.PropertyTypeCreatedEvent;
import com.openlattice.edm.events.PropertyTypeDeletedEvent;
import com.openlattice.edm.events.PropertyTypesAddedToEntityTypeEvent;
import com.openlattice.edm.events.PropertyTypesInEntitySetUpdatedEvent;
import com.openlattice.edm.type.AssociationType;
import com.openlattice.edm.type.EntityType;
import com.openlattice.edm.type.PropertyType;
import com.openlattice.graph.core.GraphService;
import com.openlattice.graph.edge.Edge;
import com.openlattice.organization.Organization;
import com.openlattice.organizations.events.OrganizationCreatedEvent;
import com.openlattice.organizations.events.OrganizationDeletedEvent;
import com.openlattice.organizations.events.OrganizationUpdatedEvent;
import com.openlattice.postgres.streams.PostgresIterable;
import com.openlattice.rhizome.hazelcast.DelegatedUUIDSet;
import com.openlattice.search.requests.DataSearchResult;
import com.openlattice.search.requests.EntityDataKeySearchResult;
import com.openlattice.search.requests.EntityNeighborsFilter;
import com.openlattice.search.requests.SearchConstraints;
import com.openlattice.search.requests.SearchResult;
import com.openlattice.search.requests.SearchTerm;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.stream.Collectors;
import javax.inject.Inject;
import kotlin.Pair;
import org.apache.olingo.commons.api.edm.FullQualifiedName;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class SearchService {
    private static final Logger logger = LoggerFactory.getLogger( SearchService.class );

    @Inject
    private EventBus eventBus;

    @Inject
    private AuthorizationManager authorizations;

    @Inject
    private SecurableObjectResolveTypeService securableObjectTypes;

    @Inject
    private ConductorElasticsearchApi elasticsearchApi;

    @Inject
    private EdmManager dataModelService;

    @Inject
    private GraphService graphService;

    @Inject
    private EntityDatastore dataManager;

    @Inject
    private EntityKeyIdService entityKeyService;

    @Inject
    private IndexingMetadataManager indexingMetadataManager;

    public SearchService( EventBus eventBus ) {
        eventBus.register( this );
    }

    @Timed
    public SearchResult executeEntitySetKeywordSearchQuery(
            Optional<String> optionalQuery,
            Optional<UUID> optionalEntityType,
            Optional<Set<UUID>> optionalPropertyTypes,
            int start,
            int maxHits ) {

        Set<AclKey> authorizedEntitySetIds = authorizations
                .getAuthorizedObjectsOfType( Principals.getCurrentPrincipals(),
                        SecurableObjectType.EntitySet,
                        READ_PERMISSION ).collect( Collectors.toSet() );
        if ( authorizedEntitySetIds.size() == 0 ) {
            return new SearchResult( 0, Lists.newArrayList() );
        }

        return elasticsearchApi.executeEntitySetMetadataSearch(
                optionalQuery,
                optionalEntityType,
                optionalPropertyTypes,
                authorizedEntitySetIds,
                start,
                maxHits );
    }

    @Timed
    public DataSearchResult executeSearch(
            SearchConstraints searchConstraints,
            Map<UUID, Map<UUID, PropertyType>> authorizedPropertyTypesByEntitySet ) {

        final Set<UUID> entitySetIds = Sets.newHashSet( Arrays.asList( searchConstraints.getEntitySetIds() ) );
        final Map<UUID, EntitySet> entitySetsById = dataModelService.getEntitySetsAsMap( entitySetIds );
        final var linkingEntitySets = entitySetsById.values().stream()
                .filter( EntitySet::isLinking )
                .collect( Collectors.toMap(
                        EntitySet::getId,
                        linkingEntitySet -> DelegatedUUIDSet.wrap( linkingEntitySet.getLinkedEntitySets() ) ) );


        final Map<UUID, DelegatedUUIDSet> authorizedPropertiesByEntitySet = authorizedPropertyTypesByEntitySet
                .entrySet().stream().collect( Collectors.toMap( Map.Entry::getKey,
                        entry -> DelegatedUUIDSet.wrap( entry.getValue().keySet() ) ) );

        if ( authorizedPropertiesByEntitySet.isEmpty() ) {
            return new DataSearchResult( 0, Lists.newArrayList() );
        }

        Map<UUID, UUID> entityTypesByEntitySet = dataModelService
                .getEntitySetsAsMap( Sets.newHashSet( Arrays.asList( searchConstraints.getEntitySetIds() ) ) ).values()
                .stream()
                .collect( Collectors.toMap( EntitySet::getId, EntitySet::getEntityTypeId ) );

        EntityDataKeySearchResult result = elasticsearchApi.executeSearch(
                searchConstraints,
                entityTypesByEntitySet,
                authorizedPropertiesByEntitySet,
                linkingEntitySets );

        SetMultimap<UUID, UUID> entityKeyIdsByEntitySetId = HashMultimap.create();
        result.getEntityDataKeys()
                .forEach( edk -> entityKeyIdsByEntitySetId.put( edk.getEntitySetId(), edk.getEntityKeyId() ) );

        List<Map<FullQualifiedName, Set<Object>>> results = entityKeyIdsByEntitySetId.keySet().parallelStream()
                .map( entitySetId -> getResults(
                        entitySetsById.get( entitySetId ),
                        entityKeyIdsByEntitySetId.get( entitySetId ),
                        authorizedPropertyTypesByEntitySet,
                        entitySetsById.get( entitySetId ).isLinking() ) )
                .flatMap( List::stream )
                .collect( Collectors.toList() );

        return new DataSearchResult( result.getNumHits(), results );
    }

    @Timed
    @Subscribe
    public void createEntitySet( EntitySetCreatedEvent event ) {
        EntityType entityType = dataModelService.getEntityType( event.getEntitySet().getEntityTypeId() );
        elasticsearchApi.saveEntitySetToElasticsearch( entityType, event.getEntitySet(), event.getPropertyTypes() );

        // If a linking entity set is created (and it has linked entity sets, so linked data),
        // we have to explicitly create the index and mappings for each linking id,
        // because it won't get picked up by indexer
        if ( event.getEntitySet().isLinking() && !event.getEntitySet().getLinkedEntitySets().isEmpty() ) {
            indexLinkedEntities(
                    event.getEntitySet().getId(),
                    dataManager.getLinkingIdsByEntitySetIds( event.getEntitySet().getLinkedEntitySets() ),
                    event.getPropertyTypes().stream()
                            .collect( Collectors.toMap( PropertyType::getId, Function.identity() ) ) );
        }
    }

    @Timed
    @Subscribe
    public void deleteEntitySet( EntitySetDeletedEvent event ) {
        elasticsearchApi.deleteEntitySet( event.getEntitySetId(), event.getEntityTypeId() );
    }

    @Timed
    @Subscribe
    public void deleteEntities( EntitiesDeletedEvent event ) {
        UUID entityTypeId = dataModelService.getEntityTypeByEntitySetId( event.getEntitySetId() ).getId();
        elasticsearchApi.deleteEntityDataBulk( event.getEntitySetId(), entityTypeId, event.getEntityKeyIds() );
    }

    @Timed
    @Subscribe
    public void deleteLinkedEntities( LinkedEntitiesDeletedEvent event ) {
        if ( event.getLinkedEntitySetIds().size() > 0 ) {
            Map<UUID, UUID> entitySetIdsToEntityTypeIds = dataModelService
                    .getEntitySetsAsMap( event.getLinkedEntitySetIds() ).values().stream()
                    .collect( Collectors.toMap( EntitySet::getId, EntitySet::getEntityTypeId ) );

            event.getLinkedEntitySetIds().forEach( entitySetId -> {
                        UUID entityTypeId = entitySetIdsToEntityTypeIds.get( entitySetId );
                        event.getEntityKeyIds()
                                .stream()
                                .map( id -> new EntityDataKey( entitySetId, id ) )
                                .forEach( edk -> elasticsearchApi.deleteEntityData( edk, entityTypeId ) );
                    }
            );
        }
    }

    @Timed
    @Subscribe
    public void entitySetDataCleared( EntitySetDataDeletedEvent event ) {
        UUID entityTypeId = dataModelService.getEntityTypeByEntitySetId( event.getEntitySetId() ).getId();
        elasticsearchApi.clearEntitySetData( event.getEntitySetId(), entityTypeId );
    }

    @Timed
    @Subscribe
    public void createOrganization( OrganizationCreatedEvent event ) {
        elasticsearchApi.createOrganization( event.getOrganization() );
    }

    @Timed
    public SearchResult executeOrganizationKeywordSearch( SearchTerm searchTerm ) {
        Set<AclKey> authorizedOrganizationIds = authorizations
                .getAuthorizedObjectsOfType( Principals.getCurrentPrincipals(),
                        SecurableObjectType.Organization,
                        READ_PERMISSION ).collect( Collectors.toSet() );
        if ( authorizedOrganizationIds.size() == 0 ) {
            return new SearchResult( 0, Lists.newArrayList() );
        }

        return elasticsearchApi.executeOrganizationSearch( searchTerm.getSearchTerm(),
                authorizedOrganizationIds,
                searchTerm.getStart(),
                searchTerm.getMaxHits() );
    }

    @Timed
    @Subscribe
    public void updateOrganization( OrganizationUpdatedEvent event ) {
        elasticsearchApi.updateOrganization( event.getId(),
                event.getOptionalTitle(),
                event.getOptionalDescription() );
    }

    @Subscribe
    public void deleteOrganization( OrganizationDeletedEvent event ) {
        elasticsearchApi.deleteOrganization( event.getOrganizationId() );
    }

    /**
     * Handles when entities are created or updated.
     * In both cases it is enough to re-index the document, ElasticSearch will mark the old document as deleted.
     */
    @Subscribe
    public void indexEntities( EntitiesUpsertedEvent event ) {
        UUID entityTypeId = dataModelService.getEntityTypeByEntitySetId( event.getEntitySetId() ).getId();
        elasticsearchApi.createBulkEntityData( entityTypeId, event.getEntitySetId(), event.getEntities() );
    }

    private void indexLinkedEntities(
            UUID linkingEntitySetId, Map<UUID, Set<UUID>> linkingIds, Map<UUID, PropertyType> propertyTypes ) {
        if ( !linkingIds.isEmpty() ) {
            UUID entityTypeId = dataModelService.getEntityTypeByEntitySetId( linkingEntitySetId ).getId();

            // linking_id/(normal)entity_set_id/property_type_id
            Map<UUID, Map<UUID, Map<UUID, Set<Object>>>> linkedData = dataManager
                    .getLinkedEntityDataByLinkingIdWithMetadata(
                            linkingIds.entrySet().stream().collect(
                                    Collectors.toMap( Map.Entry::getKey, entry -> Optional.of( entry.getValue() ) ) ),
                            linkingIds.keySet().stream().collect(
                                    Collectors.toMap( Function.identity(), entitySetId -> propertyTypes ) ),
                            EnumSet.of( MetadataOption.LAST_WRITE ) );

            elasticsearchApi.createBulkLinkedData( entityTypeId, linkedData );
        }
    }

    @Subscribe
    public void updateEntitySetMetadata( EntitySetMetadataUpdatedEvent event ) {
        elasticsearchApi.updateEntitySetMetadata( event.getEntitySet() );
    }

    @Subscribe
    public void updatePropertyTypesInEntitySet( PropertyTypesInEntitySetUpdatedEvent event ) {
        elasticsearchApi.updatePropertyTypesInEntitySet( event.getEntitySetId(), event.getUpdatedPropertyTypes() );
    }

    /**
     * If 1 or more property types are added to an entity type, the corresponding mapping needs to be updated
     */
    @Subscribe
    public void addPropertyTypesToEntityType( PropertyTypesAddedToEntityTypeEvent event ) {
        elasticsearchApi.addPropertyTypesToEntityType( event.getEntityType(), event.getNewPropertyTypes() );
    }

    /**
     * Handles indexing when 1 or more entity sets are unlinked/removed from linking entity set.
     * If there are no linked entity sets remaining, the index for that linking entity set needs to be deleted,
     * otherwise indexing needs to be triggered on the remaining linking ids, and documents with removed linking ids
     * need to be deleted.
     */
    @Subscribe
    public void removeLinkedEntitySetsFromEntitySet( LinkedEntitySetRemovedEvent event ) {
        EntityType entityType = dataModelService.getEntityTypeByEntitySetId( event.getLinkingEntitySetId() );

        if ( event.getRemainingLinkingIdsByEntitySetId().isEmpty() ) {
            elasticsearchApi.deleteEntitySet( event.getLinkingEntitySetId(), entityType.getId() );
        } else {
            UUID linkingEntitySetId = event.getLinkingEntitySetId();

            Set<UUID> removedLinkingIds = event.getRemovedLinkingIds();
            Map<UUID, Set<UUID>> remainingLinkingIdsByEntitySetId = event.getRemainingLinkingIdsByEntitySetId();
            Set<UUID> interSection = Sets.intersection(
                    removedLinkingIds,
                    remainingLinkingIdsByEntitySetId.values().stream()
                            .flatMap( Set::stream ).collect( Collectors.toSet() ) );

            Map<UUID, Set<UUID>> sharedLinkingIdsByEntitySets = remainingLinkingIdsByEntitySetId.entrySet().stream()
                    .collect( Collectors.toMap(
                            it -> it.getKey(),
                            it -> Sets.intersection( it.getValue(), removedLinkingIds ) ) );
            Map<UUID, PropertyType> propertyTypes = dataModelService
                    .getPropertyTypesAsMap( entityType.getProperties() );

            // Reindex documents(linking id) which are partially removed
            indexLinkedEntities( linkingEntitySetId, sharedLinkingIdsByEntitySets, propertyTypes );

            // Delete documents(linking id) which are fully removed
            Sets.difference( removedLinkingIds, interSection ).forEach( linkingId ->
                    elasticsearchApi.deleteEntityData( new EntityDataKey( linkingEntitySetId, linkingId ),
                            entityType.getId() ) );
        }
    }

    @Subscribe
    public void createEntityType( EntityTypeCreatedEvent event ) {
        EntityType entityType = event.getEntityType();
        List<PropertyType> propertyTypes = Lists
                .newArrayList( dataModelService.getPropertyTypes( entityType.getProperties() ) );
        elasticsearchApi.saveEntityTypeToElasticsearch( entityType, propertyTypes );
    }

    @Subscribe
    public void createAssociationType( AssociationTypeCreatedEvent event ) {
        AssociationType associationType = event.getAssociationType();
        List<PropertyType> propertyTypes = Lists
                .newArrayList( dataModelService
                        .getPropertyTypes( associationType.getAssociationEntityType().getProperties() ) );
        elasticsearchApi.saveAssociationTypeToElasticsearch( associationType, propertyTypes );
    }

    @Subscribe
    public void createPropertyType( PropertyTypeCreatedEvent event ) {
        PropertyType propertyType = event.getPropertyType();
        elasticsearchApi.savePropertyTypeToElasticsearch( propertyType );
    }

    @Subscribe
    public void createApp( AppCreatedEvent event ) {
        App app = event.getApp();
        elasticsearchApi.saveAppToElasticsearch( app );
    }

    @Subscribe
    public void createAppType( AppTypeCreatedEvent event ) {
        AppType appType = event.getAppType();
        elasticsearchApi.saveAppTypeToElasticsearch( appType );
    }

    @Subscribe
    public void deleteEntityType( EntityTypeDeletedEvent event ) {
        UUID entityTypeId = event.getEntityTypeId();
        elasticsearchApi.deleteEntityType( entityTypeId );
    }

    @Subscribe
    public void deleteAssociationType( AssociationTypeDeletedEvent event ) {
        UUID associationTypeId = event.getAssociationTypeId();
        elasticsearchApi.deleteAssociationType( associationTypeId );
    }

    /**
     * Handle deleting the index for that property type.
     * At this point, none of the entity sets should contain this property type anymore, so the entity set data mappings
     * are not affected.
     */
    @Subscribe
    public void deletePropertyType( PropertyTypeDeletedEvent event ) {
        UUID propertyTypeId = event.getPropertyTypeId();
        elasticsearchApi.deletePropertyType( propertyTypeId );
    }

    @Subscribe
    public void deleteApp( AppDeletedEvent event ) {
        UUID appId = event.getAppId();
        elasticsearchApi.deleteApp( appId );
    }

    @Subscribe
    public void deleteAppType( AppTypeDeletedEvent event ) {
        UUID appTypeId = event.getAppTypeId();
        elasticsearchApi.deleteAppType( appTypeId );
    }

    public SearchResult executeEntityTypeSearch( String searchTerm, int start, int maxHits ) {
        return elasticsearchApi.executeEntityTypeSearch( searchTerm, start, maxHits );
    }

    public SearchResult executeAssociationTypeSearch( String searchTerm, int start, int maxHits ) {
        return elasticsearchApi.executeAssociationTypeSearch( searchTerm, start, maxHits );
    }

    public SearchResult executePropertyTypeSearch( String searchTerm, int start, int maxHits ) {
        return elasticsearchApi.executePropertyTypeSearch( searchTerm, start, maxHits );
    }

    public SearchResult executeAppSearch( String searchTerm, int start, int maxHits ) {
        return elasticsearchApi.executeAppSearch( searchTerm, start, maxHits );
    }

    public SearchResult executeAppTypeSearch( String searchTerm, int start, int maxHits ) {
        return elasticsearchApi.executeAppTypeSearch( searchTerm, start, maxHits );
    }

    public SearchResult executeFQNEntityTypeSearch( String namespace, String name, int start, int maxHits ) {
        return elasticsearchApi.executeFQNEntityTypeSearch( namespace, name, start, maxHits );
    }

    public SearchResult executeFQNPropertyTypeSearch( String namespace, String name, int start, int maxHits ) {
        return elasticsearchApi.executeFQNPropertyTypeSearch( namespace, name, start, maxHits );
    }

    @Timed
    public Map<UUID, List<NeighborEntityDetails>> executeEntityNeighborSearch(
            Set<UUID> entitySetIds,
            EntityNeighborsFilter filter,
            Set<Principal> principals ) {
        final Stopwatch sw1 = Stopwatch.createStarted();
        final Stopwatch sw2 = Stopwatch.createStarted();

        logger.info( "Starting Entity Neighbor Search..." );
        if ( filter.getAssociationEntitySetIds().isPresent() && filter.getAssociationEntitySetIds().get().isEmpty() ) {
            logger.info( "Missing association entity set ids.. returning empty result" );
            return ImmutableMap.of();
        }

        Collection<EntitySet> linkingEntitySets = dataModelService.getEntitySetsAsMap( entitySetIds ).values();
        linkingEntitySets.removeIf( entitySet -> !entitySet.isLinking() );
        Map<UUID, Set<UUID>> entityKeyIdsByLinkingId = ImmutableMap.of();

        Set<UUID> entityKeyIds = Sets.newHashSet( filter.getEntityKeyIds() );
        Set<UUID> allBaseEntitySetIds = Sets.newHashSet( entitySetIds );

        if ( linkingEntitySets.size() > 0 ) {
            entityKeyIdsByLinkingId = getEntityKeyIdsByLinkingIds( entityKeyIds ).stream()
                    .collect( Collectors.toMap( Pair::getFirst, Pair::getSecond ) );
            entityKeyIdsByLinkingId.values().forEach( entityKeyIds::addAll );
            entityKeyIds.removeAll( entityKeyIdsByLinkingId.keySet() ); // remove linking ids

            // normal entity sets within 1 linking entity set are only authorized if all of them is authorized
            var authorizedNormalEntitySetIds = linkingEntitySets.stream()
                    .map( EntitySet::getLinkedEntitySets )
                    .filter( esIds -> esIds.stream()
                            .allMatch( esId -> authorizations
                                    .checkIfHasPermissions( new AclKey( esId ), principals, READ_PERMISSION ) ) )
                    .flatMap( Set::stream )
                    .collect( Collectors.toSet() );

            allBaseEntitySetIds.addAll( authorizedNormalEntitySetIds );
        }

        List<Edge> edges = Lists.newArrayList();
        Set<UUID> allEntitySetIds = Sets.newHashSet();
        Map<UUID, Set<UUID>> authorizedEdgeESIdsToVertexESIds = Maps.newHashMap();
        SetMultimap<UUID, UUID> entitySetIdToEntityKeyId = HashMultimap.create();
        Map<UUID, Map<UUID, PropertyType>> entitySetsIdsToAuthorizedProps = Maps.newHashMap();

        graphService.getEdgesAndNeighborsForVerticesBulk( allBaseEntitySetIds,
                new EntityNeighborsFilter( entityKeyIds,
                        filter.getSrcEntitySetIds(),
                        filter.getDstEntitySetIds(),
                        filter.getAssociationEntitySetIds() ) ).forEach( edge -> {
            edges.add( edge );
            allEntitySetIds.add( edge.getEdge().getEntitySetId() );
            allEntitySetIds.add( entityKeyIds.contains( edge.getSrc().getEntityKeyId() ) ?
                    edge.getDst().getEntitySetId() : edge.getSrc().getEntitySetId() );
        } );
        logger.info( "Get edges and neighbors for vertices query finished in {} ms",
                sw1.elapsed( TimeUnit.MILLISECONDS ) );
        sw1.reset().start();

        Set<UUID> authorizedEntitySetIds = authorizations.accessChecksForPrincipals( allEntitySetIds.stream()
                .map( esId -> new AccessCheck( new AclKey( esId ), READ_PERMISSION ) )
                .collect( Collectors.toSet() ), principals )
                .filter( auth -> auth.getPermissions().get( Permission.READ ) ).map( auth -> auth.getAclKey().get( 0 ) )
                .collect( Collectors.toSet() );

        Map<UUID, EntitySet> entitySetsById = dataModelService.getEntitySetsAsMap( authorizedEntitySetIds );

        Map<UUID, EntityType> entityTypesById = dataModelService
                .getEntityTypesAsMap( entitySetsById.values().stream().map( entitySet -> {
                    entitySetsIdsToAuthorizedProps.put( entitySet.getId(), Maps.newHashMap() );
                    authorizedEdgeESIdsToVertexESIds.put( entitySet.getId(), Sets.newHashSet() );
                    return entitySet.getEntityTypeId();
                } ).collect( Collectors.toSet() ) );

        Map<UUID, PropertyType> propertyTypesById = dataModelService
                .getPropertyTypesAsMap( entityTypesById.values().stream()
                        .flatMap( entityType -> entityType.getProperties().stream() ).collect(
                                Collectors.toSet() ) );

        Set<AccessCheck> accessChecks = entitySetsById.values().stream()
                .flatMap( entitySet -> entityTypesById.get( entitySet.getEntityTypeId() ).getProperties().stream()
                        .map( propertyTypeId -> new AccessCheck( new AclKey( entitySet.getId(), propertyTypeId ),
                                READ_PERMISSION ) ) ).collect( Collectors.toSet() );

        authorizations.accessChecksForPrincipals( accessChecks, principals ).forEach( auth -> {
            if ( auth.getPermissions().get( Permission.READ ) ) {
                UUID esId = auth.getAclKey().get( 0 );
                UUID propertyTypeId = auth.getAclKey().get( 1 );
                entitySetsIdsToAuthorizedProps.get( esId )
                        .put( propertyTypeId, propertyTypesById.get( propertyTypeId ) );
            }
        } );
        logger.info( "Access checks for entity sets and their properties finished in {} ms",
                sw1.elapsed( TimeUnit.MILLISECONDS ) );
        sw1.reset().start();

        edges.forEach( edge -> {
            UUID edgeEntityKeyId = edge.getEdge().getEntityKeyId();
            UUID neighborEntityKeyId = ( entityKeyIds.contains( edge.getSrc().getEntityKeyId() ) ) ? edge.getDst()
                    .getEntityKeyId()
                    : edge.getSrc().getEntityKeyId();
            UUID edgeEntitySetId = edge.getEdge().getEntitySetId();
            UUID neighborEntitySetId = ( entityKeyIds.contains( edge.getSrc().getEntityKeyId() ) ) ? edge.getDst()
                    .getEntitySetId()
                    : edge.getSrc().getEntitySetId();

            if ( entitySetsIdsToAuthorizedProps.containsKey( edgeEntitySetId ) ) {
                entitySetIdToEntityKeyId.put( edgeEntitySetId, edgeEntityKeyId );

                if ( entitySetsIdsToAuthorizedProps.containsKey( neighborEntitySetId ) ) {
                    authorizedEdgeESIdsToVertexESIds.get( edgeEntitySetId ).add( neighborEntitySetId );
                    entitySetIdToEntityKeyId.put( neighborEntitySetId, neighborEntityKeyId );
                }
            }

        } );
        logger.info( "Edge and neighbor entity key ids collected in {} ms", sw1.elapsed( TimeUnit.MILLISECONDS ) );
        sw1.reset().start();

        ListMultimap<UUID, Map<FullQualifiedName, Set<Object>>> entitiesByEntitySetId = dataManager
                .getEntitiesAcrossEntitySets( entitySetIdToEntityKeyId, entitySetsIdsToAuthorizedProps );
        logger.info( "Get entities across entity sets query finished in {} ms", sw1.elapsed( TimeUnit.MILLISECONDS ) );
        sw1.reset().start();

        Map<UUID, Map<FullQualifiedName, Set<Object>>> entities = Maps.newHashMap();
        entitiesByEntitySetId.entries().forEach( entry -> entities
                .put( UUID.fromString( entry.getValue().get( EdmConstants.ID_FQN ).iterator()
                                .next().toString() ),
                        entry.getValue() ) );

        Map<UUID, List<NeighborEntityDetails>> entityNeighbors = Maps.newConcurrentMap();

        // create a NeighborEntityDetails object for each edge based on authorizations
        edges.parallelStream().forEach( edge -> {
            boolean vertexIsSrc = entityKeyIds.contains( edge.getKey().getSrc().getEntityKeyId() );
            UUID entityId = ( vertexIsSrc )
                    ? edge.getKey().getSrc().getEntityKeyId()
                    : edge.getKey().getDst().getEntityKeyId();
            if ( !entityNeighbors.containsKey( entityId ) ) {
                entityNeighbors.put( entityId, Collections.synchronizedList( Lists.newArrayList() ) );
            }
            NeighborEntityDetails neighbor = getNeighborEntityDetails( edge,
                    authorizedEdgeESIdsToVertexESIds,
                    entitySetsById,
                    vertexIsSrc,
                    entities );
            if ( neighbor != null ) {
                entityNeighbors.get( entityId ).add( neighbor );
            }
        } );
        logger.info( "Neighbor entity details collected in {} ms", sw1.elapsed( TimeUnit.MILLISECONDS ) );

        /* Map linkingIds to the collection of neighbors for all entityKeyIds in the cluster */
        entityKeyIdsByLinkingId.forEach( (linkingId, normalEntityKeyIds) ->
            entityNeighbors.put( linkingId,
                    normalEntityKeyIds.stream()
                            .flatMap( entityKeyId -> entityNeighbors
                                    .getOrDefault( entityKeyId, Lists.newArrayList() ).stream() )
                            .collect( Collectors.toList() ) )
        );

        entityNeighbors.entrySet().removeIf( entry -> !filter.getEntityKeyIds().contains( entry.getKey() ) );

        logger.info( "Finished entity neighbor search in {} ms", sw2.elapsed( TimeUnit.MILLISECONDS ) );
        return entityNeighbors;
    }

    private NeighborEntityDetails getNeighborEntityDetails(
            Edge edge,
            Map<UUID, Set<UUID>> authorizedEdgeESIdsToVertexESIds,
            Map<UUID, EntitySet> entitySetsById,
            boolean vertexIsSrc,
            Map<UUID, Map<FullQualifiedName, Set<Object>>> entities ) {

        UUID edgeEntitySetId = edge.getEdge().getEntitySetId();
        if ( authorizedEdgeESIdsToVertexESIds.containsKey( edgeEntitySetId ) ) {
            UUID neighborEntityKeyId = ( vertexIsSrc )
                    ? edge.getDst().getEntityKeyId()
                    : edge.getSrc().getEntityKeyId();
            UUID neighborEntitySetId = ( vertexIsSrc )
                    ? edge.getDst().getEntitySetId()
                    : edge.getSrc().getEntitySetId();

            Map<FullQualifiedName, Set<Object>> edgeDetails = entities.get( edge.getEdge().getEntityKeyId() );
            if ( edgeDetails != null ) {
                if ( authorizedEdgeESIdsToVertexESIds.get( edgeEntitySetId )
                        .contains( neighborEntitySetId ) ) {
                    Map<FullQualifiedName, Set<Object>> neighborDetails = entities.get( neighborEntityKeyId );

                    if ( neighborDetails != null ) {
                        return new NeighborEntityDetails(
                                entitySetsById.get( edgeEntitySetId ),
                                edgeDetails,
                                entitySetsById.get( neighborEntitySetId ),
                                neighborEntityKeyId,
                                neighborDetails,
                                vertexIsSrc );
                    }

                } else {
                    return new NeighborEntityDetails(
                            entitySetsById.get( edgeEntitySetId ),
                            edgeDetails,
                            vertexIsSrc );
                }
            }
        }
        return null;
    }

    @Timed
    public Map<UUID, Map<UUID, SetMultimap<UUID, NeighborEntityIds>>> executeLinkingEntityNeighborIdsSearch(
            Set<UUID> linkedEntitySetIds,
            EntityNeighborsFilter filter,
            Set<Principal> principals ) {
        if ( filter.getAssociationEntitySetIds().isPresent() && filter.getAssociationEntitySetIds().get().isEmpty() ) {
            return ImmutableMap.of();
        }

        Set<UUID> linkingIds = filter.getEntityKeyIds();

        PostgresIterable<Pair<UUID, Set<UUID>>> entityKeyIdsByLinkingIds = getEntityKeyIdsByLinkingIds( linkingIds );

        Set<UUID> entityKeyIds = entityKeyIdsByLinkingIds.stream()
                .flatMap( entityKeyIdsOfLinkingId -> entityKeyIdsOfLinkingId.getSecond().stream() )
                .collect( Collectors.toSet() );

        // Will return only entries, where there is at least 1 neighbor
        Map<UUID, Map<UUID, SetMultimap<UUID, NeighborEntityIds>>> entityNeighbors = executeEntityNeighborIdsSearch(
                linkedEntitySetIds,
                new EntityNeighborsFilter( entityKeyIds,
                        filter.getSrcEntitySetIds(),
                        filter.getDstEntitySetIds(),
                        filter.getAssociationEntitySetIds() ),
                principals );

        if ( entityNeighbors.isEmpty() ) {
            return entityNeighbors;
        }

        return entityKeyIdsByLinkingIds.stream()
                .filter( entityKeyIdsOfLinkingId ->
                        entityNeighbors.keySet().stream().anyMatch( entityKeyIdsOfLinkingId.getSecond()::contains ) )
                .collect( Collectors.toMap(
                        Pair::getFirst, // linking_id
                        entityKeyIdsOfLinkingId -> {
                            Map<UUID, SetMultimap<UUID, NeighborEntityIds>> neighborIds = Maps.newHashMap();
                            entityKeyIdsOfLinkingId.getSecond().stream()
                                    .filter( entityNeighbors::containsKey )
                                    .forEach( entityKeyId ->
                                            entityNeighbors.get( entityKeyId ).forEach( ( entityKey, neighbors ) -> {
                                                if ( !neighborIds.containsKey( entityKey ) ) {
                                                    neighborIds.put( entityKey, HashMultimap.create() );
                                                }
                                                neighborIds.get( entityKey ).putAll( neighbors );
                                            } ) );
                            return neighborIds;
                        }
                ) );
    }

    @Timed
    public Map<UUID, Map<UUID, SetMultimap<UUID, NeighborEntityIds>>> executeEntityNeighborIdsSearch(
            Set<UUID> entitySetIds,
            EntityNeighborsFilter filter,
            Set<Principal> principals ) {
        final Stopwatch sw1 = Stopwatch.createStarted();

        logger.info( "Starting Reduced Entity Neighbor Search..." );
        if ( filter.getAssociationEntitySetIds().isPresent() && filter.getAssociationEntitySetIds().get().isEmpty() ) {
            logger.info( "Missing association entity set ids. Returning empty result." );
            return ImmutableMap.of();
        }

        Set<UUID> entityKeyIds = filter.getEntityKeyIds();
        Set<UUID> allEntitySetIds = Sets.newHashSet();

        Map<UUID, Map<UUID, SetMultimap<UUID, NeighborEntityIds>>> neighbors = Maps.newHashMap();

        Stopwatch sw = Stopwatch.createStarted();
        graphService.getEdgesAndNeighborsForVerticesBulk( entitySetIds, filter ).forEach( edge -> {

            boolean isSrc = entityKeyIds.contains( edge.getSrc().getEntityKeyId() );
            UUID entityKeyId = isSrc ? edge.getSrc().getEntityKeyId() : edge.getDst().getEntityKeyId();
            EntityDataKey neighborEntityDataKey = isSrc ? edge.getDst() : edge.getSrc();

            NeighborEntityIds neighborEntityIds = new NeighborEntityIds( edge.getEdge().getEntityKeyId(),
                    neighborEntityDataKey.getEntityKeyId(),
                    isSrc );

            if ( !neighbors.containsKey( entityKeyId ) ) {
                neighbors.put( entityKeyId, Maps.newHashMap() );
            }

            if ( !neighbors.get( entityKeyId ).containsKey( edge.getEdge().getEntitySetId() ) ) {
                neighbors.get( entityKeyId ).put( edge.getEdge().getEntitySetId(), HashMultimap.create() );
            }

            neighbors.get( entityKeyId ).get( edge.getEdge().getEntitySetId() )
                    .put( neighborEntityDataKey.getEntitySetId(), neighborEntityIds );

            allEntitySetIds.add( edge.getEdge().getEntitySetId() );
            allEntitySetIds.add( neighborEntityDataKey.getEntitySetId() );

        } );
        logger.info( "Edge query took {}", sw.elapsed( TimeUnit.MILLISECONDS ) );

        Set<UUID> unauthorizedEntitySetIds = authorizations.accessChecksForPrincipals( allEntitySetIds.stream()
                .map( esId -> new AccessCheck( new AclKey( esId ), READ_PERMISSION ) )
                .collect( Collectors.toSet() ), principals )
                .filter( auth -> !auth.getPermissions().get( Permission.READ ) )
                .map( auth -> auth.getAclKey().get( 0 ) )
                .collect( Collectors.toSet() );

        if ( unauthorizedEntitySetIds.size() > 0 ) {

            neighbors.values().forEach( associationMap -> {
                associationMap.values().forEach( neighborsMap -> neighborsMap.entries()
                        .removeIf( neighborEntry -> unauthorizedEntitySetIds.contains( neighborEntry.getKey() ) ) );
                associationMap.entrySet().removeIf( entry -> unauthorizedEntitySetIds.contains( entry.getKey() )
                        || entry.getValue().size() == 0 );

            } );

        }

        logger.info( "Reduced entity neighbor search took {} ms", sw1.elapsed( TimeUnit.MILLISECONDS ) );

        return neighbors;
    }

    private List<Map<FullQualifiedName, Set<Object>>> getResults(
            EntitySet entitySet,
            Set<UUID> entityKeyIds,
            Map<UUID, Map<UUID, PropertyType>> authorizedPropertyTypes,
            boolean linking ) {
        if ( entityKeyIds.size() == 0 ) { return ImmutableList.of(); }
        if ( linking ) {
            final var linkingIdsByEntitySetIds = entitySet.getLinkedEntitySets().stream().collect(
                    Collectors.toMap( Function.identity(), normalEntitySetId -> Optional.of( entityKeyIds ) ) );
            final var authorizedPropertiesOfNormalEntitySets = entitySet.getLinkedEntitySets().stream().collect(
                    Collectors.toMap(
                            Function.identity(),
                            normalEntitySetId -> authorizedPropertyTypes.get( entitySet.getId() ) ) );

            return dataManager.getLinkingEntitiesWithMetadata( linkingIdsByEntitySetIds,
                    authorizedPropertiesOfNormalEntitySets,
                    EnumSet.of( MetadataOption.LAST_WRITE ) )
                    .collect( Collectors.toList() );
        } else {
            return dataManager
                    .getEntitiesWithMetadata( entitySet.getId(),
                            ImmutableSet.copyOf( entityKeyIds ),
                            authorizedPropertyTypes,
                            EnumSet.of( MetadataOption.LAST_WRITE ) )
                    .collect( Collectors.toList() );
        }
    }

    private PostgresIterable<Pair<UUID, Set<UUID>>> getEntityKeyIdsByLinkingIds(
            Set<UUID> linkingIds ) {
        return dataManager.getEntityKeyIdsOfLinkingIds( linkingIds );
    }

    @Subscribe
    public void clearAllData( ClearAllDataEvent event ) {
        elasticsearchApi.clearAllData();
    }

    public void triggerPropertyTypeIndex( List<PropertyType> propertyTypes ) {
        elasticsearchApi.triggerPropertyTypeIndex( propertyTypes );
    }

    public void triggerEntityTypeIndex( List<EntityType> entityTypes ) {
        elasticsearchApi.triggerEntityTypeIndex( entityTypes );
    }

    public void triggerAssociationTypeIndex( List<AssociationType> associationTypes ) {
        elasticsearchApi.triggerAssociationTypeIndex( associationTypes );
    }

    public void triggerEntitySetIndex() {
        Map<EntitySet, Set<UUID>> entitySets = StreamUtil.stream( dataModelService.getEntitySets() ).collect( Collectors
                .toMap( entitySet -> entitySet,
                        entitySet -> dataModelService.getEntityType( entitySet.getEntityTypeId() ).getProperties() ) );
        Map<UUID, PropertyType> propertyTypes = StreamUtil.stream( dataModelService.getPropertyTypes() )
                .collect( Collectors.toMap( PropertyType::getId, pt -> pt ) );
        elasticsearchApi.triggerEntitySetIndex( entitySets, propertyTypes );
    }

    public void triggerEntitySetDataIndex( UUID entitySetId ) {
        EntityType entityType = dataModelService.getEntityTypeByEntitySetId( entitySetId );
        Map<UUID, PropertyType> propertyTypes = dataModelService.getPropertyTypesAsMap( entityType.getProperties() );
        List<PropertyType> propertyTypeList = Lists.newArrayList( propertyTypes.values() );

        elasticsearchApi.deleteEntitySet( entitySetId, entityType.getId() );
        elasticsearchApi.saveEntitySetToElasticsearch(
                entityType,
                dataModelService.getEntitySet( entitySetId ),
                propertyTypeList );

        EntitySet entitySet = dataModelService.getEntitySet( entitySetId );
        Set<UUID> entitySetIds = ( entitySet.isLinking() ) ? entitySet.getLinkedEntitySets() : Set.of( entitySetId );
        indexingMetadataManager.markEntitySetsAsNeedsToBeIndexed( entitySetIds, entitySet.isLinking() );
    }

    public void triggerAllEntitySetDataIndex() {
        dataModelService.getEntitySets().forEach( entitySet -> triggerEntitySetDataIndex( entitySet.getId() ) );
    }

    public void triggerAppIndex( List<App> apps ) {
        elasticsearchApi.triggerAppIndex( apps );
    }

    public void triggerAppTypeIndex( List<AppType> appTypes ) {
        elasticsearchApi.triggerAppTypeIndex( appTypes );
    }

    public void triggerAllOrganizationsIndex( List<Organization> allOrganizations ) {
        elasticsearchApi.triggerOrganizationIndex( allOrganizations );
    }

    public void triggerOrganizationIndex( Organization organization ) {
        elasticsearchApi.triggerOrganizationIndex( Lists.newArrayList( organization ) );
    }

}
