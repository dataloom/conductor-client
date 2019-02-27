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
 *
 */

package com.openlattice.data

import com.codahale.metrics.annotation.Timed
import com.google.common.cache.CacheBuilder
import com.google.common.cache.CacheLoader
import com.google.common.cache.LoadingCache
import com.google.common.collect.*
import com.google.common.eventbus.EventBus
import com.google.common.util.concurrent.ListenableFuture
import com.hazelcast.core.HazelcastInstance
import com.hazelcast.core.IMap
import com.openlattice.analysis.AuthorizedFilteredNeighborsRanking
import com.openlattice.analysis.requests.FilteredNeighborsRankingAggregation
import com.openlattice.data.integration.Association
import com.openlattice.data.integration.Entity
import com.openlattice.datastore.services.EdmManager
import com.openlattice.edm.EntitySet
import com.openlattice.edm.type.PropertyType
import com.openlattice.graph.core.GraphService
import com.openlattice.graph.core.NeighborSets
import com.openlattice.graph.edge.Edge
import com.openlattice.graph.edge.EdgeKey
import com.openlattice.hazelcast.HazelcastMap
import org.apache.commons.collections4.keyvalue.MultiKey
import org.apache.commons.lang3.tuple.Pair
import org.apache.olingo.commons.api.edm.FullQualifiedName
import org.slf4j.LoggerFactory
import java.nio.ByteBuffer
import java.util.*
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.stream.Stream
import kotlin.collections.HashMap
import kotlin.collections.HashSet

/**
 *
 * @author Matthew Tamayo-Rios &lt;matthew@openlattice.com&gt;
 */
private val logger = LoggerFactory.getLogger(DataGraphService::class.java)

open class DataGraphService(
        hazelcastInstance: HazelcastInstance,
        private val eventBus: EventBus,
        private val graphService: GraphService,
        private val idService: EntityKeyIdService,
        private val eds: EntityDatastore,
        private val edm: EdmManager
) : DataGraphManager {
    override fun getEntityKeyIds(entityKeys: Set<EntityKey>): Set<UUID> {
        return idService.reserveEntityKeyIds(entityKeys)
    }


    //TODO: Move to a utility class
    companion object {

        @JvmStatic
        fun tryGetAndLogErrors(f: ListenableFuture<*>) {
            try {
                f.get()
            } catch (e: InterruptedException) {
                logger.error("Future execution failed.", e)
            } catch (e: ExecutionException) {
                logger.error("Future execution failed.", e)
            }
        }
    }

    private val entitySets: IMap<UUID, EntitySet> = hazelcastInstance.getMap(HazelcastMap.ENTITY_SETS.name)

    private val typeIds: LoadingCache<UUID, UUID> = CacheBuilder.newBuilder()
            .maximumSize(100000) // 100K * 16 = 16000K = 16MB
            .build(
                    object : CacheLoader<UUID, UUID>() {

                        @Throws(Exception::class)
                        override fun load(key: UUID): UUID {
                            return entitySets[key]!!.entityTypeId
                        }
                    }
            )
    private val queryCache = CacheBuilder.newBuilder()
            .maximumSize(1000)
            .expireAfterWrite(30, TimeUnit.SECONDS)
            .build<MultiKey<*>, Map<String, Object>>()


    /* Select */

    override fun getEntitySetData(
            entityKeyIds: Map<UUID, Optional<Set<UUID>>>,
            orderedPropertyNames: LinkedHashSet<String>,
            authorizedPropertyTypes: Map<UUID, Map<UUID, PropertyType>>,
            linking: Boolean
    ): EntitySetData<FullQualifiedName> {
        return eds.getEntities(entityKeyIds, orderedPropertyNames, authorizedPropertyTypes, linking)
    }

    override fun getLinkingEntitySetSize(linkedEntitySetIds: Set<UUID>): Long {
        if (linkedEntitySetIds.isEmpty()) {
            return 0
        }

        return eds.getLinkingEntities(
                linkedEntitySetIds.map { it to Optional.empty<Set<UUID>>() }.toMap(),
                mapOf()
        ).count()
    }

    override fun getEntitySetSize(entitySetId: UUID): Long {
        return eds.getEntities(entitySetId, setOf(), mapOf()).count()
    }

    override fun getEntity(
            entitySetId: UUID,
            entityKeyId: UUID,
            authorizedPropertyTypes: Map<UUID, PropertyType>
    ): SetMultimap<FullQualifiedName, Any> {
        return eds.getEntities(
                entitySetId,
                setOf(entityKeyId),
                mapOf(entitySetId to authorizedPropertyTypes)
        ).iterator().next()
    }

    override fun getLinkingEntity(
            entitySetIds: Set<UUID>,
            entityKeyId: UUID,
            authorizedPropertyTypes: Map<UUID, Map<UUID, PropertyType>>
    ): SetMultimap<FullQualifiedName, Any> {
        return eds.getLinkingEntities(
                entitySetIds.map { it to Optional.of(setOf(entityKeyId)) }.toMap(),
                authorizedPropertyTypes
        ).iterator().next()
    }

    override fun getNeighborEntitySets(entitySetIds: Set<UUID>): List<NeighborSets> {
        return graphService.getNeighborEntitySets(entitySetIds)
    }

    override fun getNeighborEntitySetIds(entitySetIds: Set<UUID>): Set<UUID> {
        return getNeighborEntitySets(entitySetIds)
                .flatMap { listOf(it.srcEntitySetId, it.edgeEntitySetId, it.dstEntitySetId) }
                .toSet()
    }

    override fun getEdgesAndNeighborsForVertex(entitySetId: UUID, entityKeyId: UUID): Stream<Edge> {
        return graphService.getEdgesAndNeighborsForVertex(entitySetId, entityKeyId)
    }


    /* Delete */

    override fun clearEntitySet(entitySetId: UUID, authorizedPropertyTypes: Map<UUID, PropertyType>): WriteEvent {
        return eds.clearEntitySet(entitySetId, authorizedPropertyTypes)
    }

    override fun clearEntities(
            entitySetId: UUID,
            entityKeyIds: Set<UUID>,
            authorizedPropertyTypes: Map<UUID, PropertyType>
    ): WriteEvent {
        val edgeKeys = entityKeyIds
                .flatMap { graphService.getEdgeKeysContainingEntity(entitySetId, it) }
                .toSet()
        graphService.clearEdges(edgeKeys)
        edgeKeys.groupBy({ it.edge.entitySetId }, { it.edge.entityKeyId })
                .forEach { eds.clearEntities(it.key, it.value.toSet(), authorizedPropertyTypes) }
        return eds.clearEntities(entitySetId, entityKeyIds, authorizedPropertyTypes)
    }

    override fun clearAssociations(key: Set<EdgeKey>): WriteEvent {
        return WriteEvent(0, 0) // TODO
    }

    override fun clearEntityProperties(
            entitySetId: UUID, entityKeyIds: Set<UUID>, authorizedPropertyTypes: Map<UUID, PropertyType>
    ): WriteEvent {
        val propertyCount = eds.clearEntityData(entitySetId, entityKeyIds, authorizedPropertyTypes)
        logger.info("Cleared properties {} of {} entities.",
                authorizedPropertyTypes.values.map(PropertyType::getType), propertyCount)
        return propertyCount
    }

    override fun deleteEntitySet(entitySetId: UUID, authorizedPropertyTypes: Map<UUID, PropertyType>): WriteEvent {
        logger.info("Deleting edges of entity set: {}.", entitySetId)
        val edgesDeletedCount = graphService.deleteVerticesInEntitySet(entitySetId)
        logger.info("Finished deleting {} edges.", edgesDeletedCount)
        return eds.deleteEntitySetData(entitySetId, authorizedPropertyTypes)
    }

    override fun deleteEntities(
            entitySetId: UUID,
            entityKeyIds: Set<UUID>,
            authorizedPropertyTypes: Map<UUID, PropertyType>
    ): WriteEvent {
        val verticesCount = graphService.deleteVertices(entitySetId, entityKeyIds)
        val entityCount = eds.deleteEntities(entitySetId, entityKeyIds, authorizedPropertyTypes)
        logger.info("Deleted {} entities and {} vertices.", entityCount, verticesCount)
        return entityCount
    }

    @Timed
    override fun deleteAssociation(keys: Set<EdgeKey>, authorizedPropertyTypes: Map<UUID, PropertyType>): WriteEvent {
        val entitySetsToEntityKeyIds = HashMultimap.create<UUID, UUID>()

        keys.forEach {
            entitySetsToEntityKeyIds.put(it.edge.entitySetId, it.edge.entityKeyId)
        }

        Multimaps.asMap(entitySetsToEntityKeyIds)
                .entries
                .stream()
                .forEach { e -> eds.deleteEntities(e.key, e.value, authorizedPropertyTypes) }

        return graphService.deleteEdges(keys)

    }

    override fun deleteEntityProperties(
            entitySetId: UUID,
            entityKeyIds: Set<UUID>,
            authorizedPropertyTypes: Map<UUID, PropertyType>
    ): WriteEvent {
        val propertyCount = eds.deleteEntityProperties(entitySetId, entityKeyIds, authorizedPropertyTypes)
        logger.info("Deleted properties {} of {} entities.",
                authorizedPropertyTypes.values.map(PropertyType::getType), propertyCount)
        return propertyCount
    }


    /* Create */

    override fun integrateEntities(
            entitySetId: UUID,
            entities: Map<String, Map<UUID, Set<Any>>>,
            authorizedPropertyTypes: Map<UUID, PropertyType>
    ): Map<String, UUID> {
        //We need to fix this to avoid remapping. Skipping for expediency.
        return doIntegrateEntities(entitySetId, entities, authorizedPropertyTypes)
                .map { it.key.entityId to it.value }.toMap()
    }

    private fun doIntegrateEntities(
            entitySetId: UUID,
            entities: Map<String, Map<UUID, Set<Any>>>,
            authorizedPropertyTypes: Map<UUID, PropertyType>
    ): Map<EntityKey, UUID> {
        val ids = idService.getEntityKeyIds(entities.keys.map { EntityKey(entitySetId, it) }.toSet())
        val identifiedEntities = ids.map { it.value to entities[it.key.entityId] }.toMap()
        eds.integrateEntities(entitySetId, identifiedEntities, authorizedPropertyTypes)
        return ids
    }

    override fun createEntities(
            entitySetId: UUID,
            entities: List<Map<UUID, Set<Any>>>,
            authorizedPropertyTypes: Map<UUID, PropertyType>
    ): Pair<List<UUID>, WriteEvent> {
        val ids = idService.reserveIds(entitySetId, entities.size)
        val entityMap = ids.mapIndexed { i, id -> id to entities[i] }.toMap()
        val writeEvent = eds.createOrUpdateEntities(entitySetId, entityMap, authorizedPropertyTypes)
        return Pair.of(ids, writeEvent)
    }

    override fun mergeEntities(
            entitySetId: UUID,
            entities: Map<UUID, Map<UUID, Set<Any>>>,
            authorizedPropertyTypes: Map<UUID, PropertyType>
    ): WriteEvent {
        return eds.createOrUpdateEntities(entitySetId, entities, authorizedPropertyTypes)
    }

    override fun replaceEntities(
            entitySetId: UUID,
            entities: Map<UUID, Map<UUID, Set<Any>>>,
            authorizedPropertyTypes: Map<UUID, PropertyType>
    ): WriteEvent {
        return eds.replaceEntities(entitySetId, entities, authorizedPropertyTypes)
    }

    override fun partialReplaceEntities(
            entitySetId: UUID,
            entities: Map<UUID, Map<UUID, Set<Any>>>,
            authorizedPropertyTypes: Map<UUID, PropertyType>
    ): WriteEvent {
        return eds.partialReplaceEntities(entitySetId, entities, authorizedPropertyTypes)
    }

    override fun replacePropertiesInEntities(
            entitySetId: UUID,
            replacementProperties: Map<UUID, SetMultimap<UUID, Map<ByteBuffer, Any>>>,
            authorizedPropertyTypes: Map<UUID, PropertyType>
    ): WriteEvent {
        return eds.replacePropertiesInEntities(entitySetId, replacementProperties, authorizedPropertyTypes)
    }

    override fun createAssociations(associations: Set<DataEdgeKey>): WriteEvent {
        return graphService.createEdges(associations)
    }

    override fun createAssociations(
            associations: ListMultimap<UUID, DataEdge>,
            authorizedPropertiesByEntitySetId: Map<UUID, Map<UUID, PropertyType>>
    ): Map<UUID, CreateAssociationEvent> {

        val associationCreateEvents: MutableMap<UUID, CreateAssociationEvent> = mutableMapOf()

        Multimaps
                .asMap(associations)
                .forEach {
                    val entitySetId = it.key
                    val entities = it.value.map { it.data }
                    val (ids, entityWrite) = createEntities(entitySetId, entities, authorizedPropertiesByEntitySetId[entitySetId]!!)

                    val edgeKeys = it.value.asSequence().mapIndexed { index, dataEdge ->
                        DataEdgeKey(dataEdge.src, dataEdge.dst, EntityDataKey(entitySetId, ids[index]))
                    }.toSet()
                    val edgeWrite = graphService.createEdges(edgeKeys)

                    associationCreateEvents[entitySetId] = CreateAssociationEvent(ids, entityWrite, edgeWrite)
                }

        return associationCreateEvents
    }

    override fun integrateAssociations(
            associations: Set<Association>,
            authorizedPropertiesByEntitySet: Map<UUID, Map<UUID, PropertyType>>
    ): Map<UUID, Map<String, UUID>> {
        val associationsByEntitySet = associations.groupBy { it.key.entitySetId }
        val entityKeys = HashSet<EntityKey>(3 * associations.size)
        val entityKeyIds = HashMap<EntityKey, UUID>(3 * associations.size)

        //Create the entities for the association and build list of required entity keys
        val integrationResults = associationsByEntitySet
                .asSequence()
                .map {
                    val entitySetId = it.key
                    val entities = it.value.asSequence()
                            .map {
                                entityKeys.add(it.src)
                                entityKeys.add(it.dst)
                                it.key.entityId to it.details
                            }.toMap()
                    val ids = doIntegrateEntities(entitySetId, entities, authorizedPropertiesByEntitySet[entitySetId]!!)
                    entityKeyIds.putAll(ids)
                    entitySetId to ids.asSequence().map { it.key.entityId to it.value }.toMap()
                }.toMap()
        //Retrieve the src/dst keys
        idService.getEntityKeyIds(entityKeys, entityKeyIds)

        //Create graph structure.
        val edges = associations
                .asSequence()
                .map { association ->
                    val srcId = entityKeyIds[association.src]
                    val dstId = entityKeyIds[association.dst]
                    val edgeId = entityKeyIds[association.key]

                    val src = EntityDataKey(association.src.entitySetId, srcId)
                    val dst = EntityDataKey(association.dst.entitySetId, dstId)
                    val edge = EntityDataKey(association.key.entitySetId, edgeId)

                    DataEdgeKey(src, dst, edge)
                }
                .toSet()
        graphService.createEdges(edges)

        return integrationResults
    }

    override fun integrateEntitiesAndAssociations(
            entities: Set<Entity>,
            associations: Set<Association>,
            authorizedPropertiesByEntitySetId: Map<UUID, Map<UUID, PropertyType>>
    ): IntegrationResults? {
        val entitiesByEntitySet = HashMap<UUID, MutableMap<String, MutableMap<UUID, MutableSet<Any>>>>()

        for (entity in entities) {
            val entitiesToCreate = entitiesByEntitySet.getOrPut(entity.entitySetId) { mutableMapOf() }
            val entityDetails = entitiesToCreate.getOrPut(entity.entityId) { entity.details }
            if (entityDetails !== entity.details) {
                entity.details.forEach { propertyTypeId, values ->
                    entityDetails.getOrPut(propertyTypeId) { mutableSetOf() }.addAll(values)

                }
            }
        }

        entitiesByEntitySet
                .forEach { entitySetId, entitySet ->
                    integrateEntities(
                            entitySetId,
                            entitySet,
                            authorizedPropertiesByEntitySetId[entitySetId]!!
                    )
                }

        integrateAssociations(associations, authorizedPropertiesByEntitySetId)
        return null
    }

    override fun createEdges(edges: Set<DataEdgeKey>): WriteEvent {
        return graphService.createEdges(edges);
    }


    /* Top utilizers */

    override fun getFilteredRankings(
            entitySetIds: Set<UUID>,
            numResults: Int,
            filteredRankings: List<AuthorizedFilteredNeighborsRanking>,
            authorizedPropertyTypes: Map<UUID, Map<UUID, PropertyType>>,
            linked: Boolean,
            linkingEntitySetId: Optional<UUID>
    ): Iterable<Map<String, Any>> {
//        val maybeUtilizers = queryCache
//                .getIfPresent(MultiKey(entitySetIds, filteredRankings))
//        val utilizers: PostgresIterable<Map<String, Object>>
//
//
//        if (maybeUtilizers == null) {

        return graphService.computeTopEntities(
                numResults,
                entitySetIds,
                authorizedPropertyTypes,
                filteredRankings,
                linked,
                linkingEntitySetId
        )

//            queryCache.put(MultiKey(entitySetIds, filteredRankings), utilizers)
//        } else {
//            utilizers = maybeUtilizers
//        }

//        val entities = eds
//                .getEntities(entitySetIds.first(), utilizers.map { it.id }.toSet(), authorizedPropertyTypes)
//                .map { it[ID_FQN].first() as UUID to it }
//                .toList()
//                .toMap()

//        return utilizers.map {
//            val entity = entities[it.id]!!
//            entity.put(COUNT_FQN, it.weight)
//            entity
//        }.stream()


    }

    override fun getTopUtilizers(
            entitySetId: UUID,
            filteredNeighborsRankingList: List<FilteredNeighborsRankingAggregation>,
            numResults: Int,
            authorizedPropertyTypes: Map<UUID, PropertyType>
    ): Stream<SetMultimap<FullQualifiedName, Any>> {
//        val maybeUtilizers = queryCache
//                .getIfPresent(MultiKey(entitySetId, filteredNeighborsRankingList))
//        val utilizers: Array<IncrementableWeightId>
//
//
//        if (maybeUtilizers == null) {
//            utilizers = graphService.computeTopEntities(
//                    numResults, entitySetId, authorizedPropertyTypes, filteredNeighborsRankingList
//            )
//            //            utilizers = new TopUtilizers( numResults );
//            val srcFilters = HashMultimap.create<UUID, UUID>()
//            val dstFilters = HashMultimap.create<UUID, UUID>()
//
//            val associationPropertyTypeFilters = HashMultimap.create<UUID, Optional<SetMultimap<UUID, RangeFilter<Comparable<Any>>>>>()
//            val srcPropertyTypeFilters = HashMultimap.create<UUID, Optional<SetMultimap<UUID, RangeFilter<Comparable<Any>>>>>()
//            val dstPropertyTypeFilters = HashMultimap.create<UUID, Optional<SetMultimap<UUID, RangeFilter<Comparable<Any>>>>>()
//            filteredNeighborsRankingList.forEach { details ->
//                val associationSets = edm.getEntitySetsOfType(details.associationTypeId).map { it.id }
//                val neighborSets = edm.getEntitySetsOfType(details.neighborTypeId).map { it.id }
//
//                associationSets.forEach {
//                    (if (details.utilizerIsSrc) srcFilters else dstFilters).putAll(it, neighborSets)
//                    (if (details.utilizerIsSrc) srcPropertyTypeFilters else dstPropertyTypeFilters).putAll(
//                            it, details.neighborFilters
//                    )
//                }
//
//            }
//
//            utilizers = graphService.computeGraphAggregation(numResults, entitySetId, srcFilters, dstFilters)
//
//            queryCache.put(MultiKey(entitySetId, filteredNeighborsRankingList), utilizers)
//        } else {
//            utilizers = maybeUtilizers
//        }
//
//        val entities = eds
//                .getEntities(entitySetId, utilizers.map { it.id }.toSet(), authorizedPropertyTypes)
//                .map { it[ID_FQN].first() as UUID to it }
//                .toList()
//                .toMap()
//
//        return utilizers.map {
//            val entity = entities[it.id]!!
//            entity.put(COUNT_FQN, it.weight)
//            entity
//        }.stream()
        return Stream.empty()
    }
}