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

import com.google.common.base.Stopwatch
import com.google.common.collect.ListMultimap
import com.google.common.collect.Multimaps
import com.google.common.collect.SetMultimap
import com.openlattice.analysis.AuthorizedFilteredNeighborsRanking
import com.openlattice.analysis.requests.FilteredNeighborsRankingAggregation
import com.openlattice.data.integration.Association
import com.openlattice.data.integration.Entity
import com.openlattice.data.storage.EntityDatastore
import com.openlattice.data.storage.PostgresEntitySetSizesTask
import com.openlattice.edm.set.ExpirationBase
import com.openlattice.edm.type.PropertyType
import com.openlattice.graph.core.GraphService
import com.openlattice.graph.core.NeighborSets
import com.openlattice.graph.edge.Edge
import com.openlattice.postgres.DataTables
import com.openlattice.postgres.PostgresColumn
import com.openlattice.postgres.PostgresDataTables
import com.openlattice.postgres.streams.BasePostgresIterable
import com.openlattice.postgres.streams.PostgresIterable
import org.apache.commons.lang3.tuple.Pair
import org.apache.olingo.commons.api.edm.EdmPrimitiveTypeKind
import org.apache.olingo.commons.api.edm.FullQualifiedName
import org.slf4j.LoggerFactory
import java.nio.ByteBuffer
import java.sql.Types
import java.time.OffsetDateTime
import java.time.ZoneId
import java.util.*
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
        private val graphService: GraphService,
        private val idService: EntityKeyIdService,
        private val eds: EntityDatastore,
        private val entitySetSizesTask: PostgresEntitySetSizesTask

) : DataGraphManager {
    override fun getEntityKeyIds(entityKeys: Set<EntityKey>): Set<UUID> {
        return idService.reserveEntityKeyIds(entityKeys)
    }

    companion object {
        const val ASSOCIATION_SIZE = 30000
    }


    /* Select */

    override fun getEntitySetData(
            entityKeyIds: Map<UUID, Optional<Set<UUID>>>,
            orderedPropertyNames: LinkedHashSet<String>,
            authorizedPropertyTypes: Map<UUID, Map<UUID, PropertyType>>,
            linking: Boolean
    ): EntitySetData<FullQualifiedName> {
        return eds.getEntities(entityKeyIds, orderedPropertyNames, authorizedPropertyTypes, linking)
    }

    override fun getEntitySetSize(entitySetId: UUID): Long {
        return entitySetSizesTask.getEntitySetSize(entitySetId)
    }

    override fun getEntity(
            entitySetId: UUID,
            entityKeyId: UUID,
            authorizedPropertyTypes: Map<UUID, PropertyType>
    ): Map<FullQualifiedName, Set<Any>> {
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
    ): Map<FullQualifiedName, Set<Any>> {
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

    override fun getEdgeKeysOfEntitySet(entitySetId: UUID): PostgresIterable<DataEdgeKey> {
        return graphService.getEdgeKeysOfEntitySet(entitySetId)
    }

    override fun getEdgesConnectedToEntities(entitySetId: UUID, entityKeyIds: Set<UUID>, includeClearedEdges: Boolean)
            : PostgresIterable<DataEdgeKey> {
        return graphService.getEdgeKeysContainingEntities(entitySetId, entityKeyIds, includeClearedEdges)
    }


    /* Delete */

    private val groupEdges: (List<DataEdgeKey>) -> Map<UUID, Set<UUID>> = { edges ->
        edges.map { it.edge }.groupBy { it.entitySetId }.mapValues { it.value.map { it.entityKeyId }.toSet() }
    }

    override fun clearEntitySet(entitySetId: UUID, authorizedPropertyTypes: Map<UUID, PropertyType>): WriteEvent {
        // clear edges
        var verticesCount = 0L
        val dataEdgeKeys = getEdgeKeysOfEntitySet(entitySetId)
        // since we cannot lock on entity set just on individual DataEdgeKeys, we do the delete in chunks
        dataEdgeKeys.asSequence().chunked(ASSOCIATION_SIZE).forEach {
            verticesCount += graphService.clearEdges(it)
        }

        //clear entities
        val entityWriteEvent = eds.clearEntitySet(entitySetId, authorizedPropertyTypes)

        logger.info("Cleared {} entities and {} vertices.", entityWriteEvent.numUpdates, verticesCount)
        return entityWriteEvent
    }

    override fun clearEntities(
            entitySetId: UUID,
            entityKeyIds: Set<UUID>,
            authorizedPropertyTypes: Map<UUID, PropertyType>
    ): WriteEvent {
        return clearEntityDataAndVertices(entitySetId, entityKeyIds, authorizedPropertyTypes)
    }

    private fun clearEntityDataAndVertices(
            entitySetId: UUID,
            entityKeyIds: Set<UUID>,
            authorizedPropertyTypes: Map<UUID, PropertyType>
    ): WriteEvent {
        // clear edges
        val dataEdgeKeys = getEdgesConnectedToEntities(entitySetId, entityKeyIds, false)
        val verticesCount = graphService.clearEdges(dataEdgeKeys)

        //clear entities
        val entityWriteEvent = eds.clearEntities(entitySetId, entityKeyIds, authorizedPropertyTypes)

        logger.info("Cleared {} entities and {} vertices.", entityWriteEvent.numUpdates, verticesCount)
        return entityWriteEvent
    }

    override fun clearAssociationsBatch(
            entitySetId: UUID,
            associationsEdgeKeys: Iterable<DataEdgeKey>,
            authorizedPropertyTypes: Map<UUID, Map<UUID, PropertyType>>
    ): List<WriteEvent> {
        var associationClearCount = 0
        val writeEvents = ArrayList<WriteEvent>()

        associationsEdgeKeys.asSequence().chunked(ASSOCIATION_SIZE).forEach { dataEdgeKeys ->
            val entityKeyIds = groupEdges(dataEdgeKeys)
            entityKeyIds.entries.forEach {
                val writeEvent = clearEntityDataAndVerticesOfAssociations(
                        dataEdgeKeys, it.key, it.value, authorizedPropertyTypes.getValue(it.key)
                )
                writeEvents.add(writeEvent)
                associationClearCount += writeEvent.numUpdates
            }
        }

        logger.info(
                "Cleared {} associations when deleting entities from entity set {}", associationClearCount,
                entitySetId
        )

        return writeEvents
    }

    private fun clearEntityDataAndVerticesOfAssociations(
            dataEdgeKeys: List<DataEdgeKey>,
            entitySetId: UUID,
            entityKeyIds: Set<UUID>,
            authorizedPropertyTypes: Map<UUID, PropertyType>
    ): WriteEvent {
        // clear edges
        val verticesCount = graphService.clearEdges(dataEdgeKeys)

        //clear entities
        val entityWriteEvent = eds.clearEntities(entitySetId, entityKeyIds, authorizedPropertyTypes)

        logger.info("Cleared {} entities and {} vertices.", entityWriteEvent.numUpdates, verticesCount)
        return entityWriteEvent
    }

    override fun clearEntityProperties(
            entitySetId: UUID, entityKeyIds: Set<UUID>, authorizedPropertyTypes: Map<UUID, PropertyType>
    ): WriteEvent {
        val propertyWriteEvent = eds.clearEntityData(entitySetId, entityKeyIds, authorizedPropertyTypes)
        logger.info(
                "Cleared properties {} of {} entities.",
                authorizedPropertyTypes.values.map(PropertyType::getType), propertyWriteEvent.numUpdates
        )

        return propertyWriteEvent
    }

    override fun deleteEntitySet(entitySetId: UUID, authorizedPropertyTypes: Map<UUID, PropertyType>): WriteEvent {
        // delete edges
        var verticesCount = 0L
        val dataEdgeKeys = getEdgeKeysOfEntitySet(entitySetId)
        // since we cannot lock on entity set just on individual DataEdgeKeys, we do the delete in chunks
        dataEdgeKeys.asSequence().chunked(ASSOCIATION_SIZE).forEach {
            verticesCount += graphService.deleteEdges(it).numUpdates
        }

        // delete entities
        val entityWriteEvent = eds.deleteEntitySetData(entitySetId, authorizedPropertyTypes)

        logger.info("Deleted {} entities and {} vertices.", entityWriteEvent.numUpdates, verticesCount)
        return entityWriteEvent
    }

    override fun deleteEntities(
            entitySetId: UUID,
            entityKeyIds: Set<UUID>,
            authorizedPropertyTypes: Map<UUID, PropertyType>
    ): WriteEvent {
        return deleteEntityDataAnVertices(entitySetId, entityKeyIds, authorizedPropertyTypes)
    }

    private fun deleteEntityDataAnVertices(
            entitySetId: UUID,
            entityKeyIds: Set<UUID>,
            authorizedPropertyTypes: Map<UUID, PropertyType>
    ): WriteEvent {
        // delete edges
        val dataEdgeKeys = getEdgesConnectedToEntities(entitySetId, entityKeyIds, true)
        val verticesCount = graphService.deleteEdges(dataEdgeKeys).numUpdates

        // delete entities
        val entityWriteEvent = eds.deleteEntities(entitySetId, entityKeyIds, authorizedPropertyTypes)

        logger.info("Deleted {} entities and {} vertices.", entityWriteEvent.numUpdates, verticesCount)

        return entityWriteEvent
    }

    override fun deleteAssociationsBatch(
            entitySetId: UUID,
            associationsEdgeKeys: Iterable<DataEdgeKey>,
            authorizedPropertyTypes: Map<UUID, Map<UUID, PropertyType>>
    ): List<WriteEvent> {
        var associationDeleteCount = 0
        val writeEvents = ArrayList<WriteEvent>()

        associationsEdgeKeys.asSequence().chunked(ASSOCIATION_SIZE).forEach { dataEdgeKeys ->
            val entityKeyIds = groupEdges(dataEdgeKeys)
            entityKeyIds.entries.forEach {
                val writeEvent = deleteEntityDataAndVerticesOfAssociations(
                        dataEdgeKeys, it.key, it.value, authorizedPropertyTypes.getValue(it.key)
                )
                writeEvents.add(writeEvent)
                associationDeleteCount += writeEvent.numUpdates
            }
        }

        logger.info(
                "Deleted {} associations when deleting entities from entity set {}", associationDeleteCount,
                entitySetId
        )

        return writeEvents
    }

    private fun deleteEntityDataAndVerticesOfAssociations(
            dataEdgeKeys: List<DataEdgeKey>,
            entitySetId: UUID,
            entityKeyIds: Set<UUID>,
            authorizedPropertyTypes: Map<UUID, PropertyType>
    ): WriteEvent {
        // delete edges
        val verticesCount = graphService.deleteEdges(dataEdgeKeys)

        // delete entities
        val entityWriteEvent = eds.deleteEntities(entitySetId, entityKeyIds, authorizedPropertyTypes)

        logger.info("Deleted {} entities and {} vertices.", entityWriteEvent.numUpdates, verticesCount.numUpdates)

        return entityWriteEvent
    }

    override fun deleteEntityProperties(
            entitySetId: UUID,
            entityKeyIds: Set<UUID>,
            authorizedPropertyTypes: Map<UUID, PropertyType>
    ): WriteEvent {
        val propertyCount = eds.deleteEntityProperties(entitySetId, entityKeyIds, authorizedPropertyTypes)

        logger.info(
                "Deleted properties {} of {} entities.",
                authorizedPropertyTypes.values.map(PropertyType::getType), propertyCount.numUpdates
        )
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
        val identifiedEntities = ids.map { it.value to entities.getValue(it.key.entityId) }.toMap()
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
            replacementProperties: Map<UUID, Map<UUID, Set<Map<ByteBuffer, Any>>>>,
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

                    val entities = it.value.map(DataEdge::getData)
                    val (ids, entityWrite) = createEntities(
                            entitySetId, entities, authorizedPropertiesByEntitySetId.getValue(entitySetId)
                    )

                    val edgeKeys = it.value.asSequence().mapIndexed { index, dataEdge ->
                        DataEdgeKey(dataEdge.src, dataEdge.dst, EntityDataKey(entitySetId, ids[index]))
                    }.toSet()
                    val sw = Stopwatch.createStarted()
                    val edgeWrite = graphService.createEdges(edgeKeys)
                    logger.info("graphService.createEdges (for {} edgeKeys) took {}", edgeKeys.size, sw.elapsed(TimeUnit.MILLISECONDS))

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
                .map { (entitySetId, entitySetAssociations) ->
                    val entities = entitySetAssociations.asSequence()
                            .map { association ->
                                entityKeys.add(association.src)
                                entityKeys.add(association.dst)
                                association.key.entityId to association.details
                            }.toMap()
                    val ids = doIntegrateEntities(
                            entitySetId, entities, authorizedPropertiesByEntitySet.getValue(entitySetId)
                    )
                    entityKeyIds.putAll(ids)
                    entitySetId to ids.asSequence().map { it.key.entityId to it.value }.toMap()
                }.toMap()

        // Retrieve the src/dst keys (it adds all entitykeyids to mutable entityKeyIds)
        idService.getEntityKeyIds(entityKeys, entityKeyIds)

        val edges = associations
                .asSequence()
                .map { association ->
                    val srcId = entityKeyIds[association.src]
                    val dstId = entityKeyIds[association.dst]
                    val edgeId = entityKeyIds[association.key]

                    val srcEsId = association.src.entitySetId
                    val dstEsId = association.dst.entitySetId
                    val edgeEsId = association.key.entitySetId

                    val src = EntityDataKey(srcEsId, srcId)
                    val dst = EntityDataKey(dstEsId, dstId)
                    val edge = EntityDataKey(edgeEsId, edgeId)

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
                entity.details.forEach { (propertyTypeId, values) ->
                    entityDetails.getOrPut(propertyTypeId) { mutableSetOf() }.addAll(values)
                }
            }
        }

        entitiesByEntitySet
                .forEach { (entitySetId, entitySet) ->
                    integrateEntities(
                            entitySetId,
                            entitySet,
                            authorizedPropertiesByEntitySetId.getValue(entitySetId)
                    )
                }

        integrateAssociations(associations, authorizedPropertiesByEntitySetId)
        return null
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
//                val associationSets = edm.getEntitySetsOfType(details.associationTypeIds).map { it.id }
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

    override fun getExpiringEntitiesFromEntitySet(entitySetId: UUID, expirationPolicy: DataExpiration,
                                                  dateTime: OffsetDateTime, deleteType: DeleteType,
                                                  expirationPropertyType: Optional<PropertyType>): BasePostgresIterable<UUID> {
        val sqlParams = getSqlParameters(expirationPolicy, dateTime, expirationPropertyType)
        val expirationBaseColumn = sqlParams.first
        val formattedDateMinusTTE = sqlParams.second
        val sqlFormat = sqlParams.third
        return eds.getExpiringEntitiesFromEntitySet(entitySetId, expirationBaseColumn, formattedDateMinusTTE,
                sqlFormat, deleteType)
    }

    private fun getSqlParameters(expirationPolicy: DataExpiration, dateTime: OffsetDateTime, expirationPT: Optional<PropertyType>): Triple<String, Any, Int> {
        val expirationBaseColumn: String
        val formattedDateMinusTTE: Any
        val sqlFormat: Int
        val dateMinusTTEAsInstant = dateTime.toInstant().minusMillis(expirationPolicy.timeToExpiration)
        when (expirationPolicy.expirationBase) {
            ExpirationBase.DATE_PROPERTY -> {
                val expirationPropertyType = expirationPT.get()
                val columnData = Pair(expirationPropertyType.postgresIndexType,
                        expirationPropertyType.datatype)
                expirationBaseColumn = PostgresDataTables.getColumnDefinition(columnData.first, columnData.second).name
                if (columnData.second == EdmPrimitiveTypeKind.Date) {
                    formattedDateMinusTTE = OffsetDateTime.ofInstant(dateMinusTTEAsInstant, ZoneId.systemDefault()).toLocalDate()
                    sqlFormat = Types.DATE
                } else { //only other TypeKind for date property type is OffsetDateTime
                    formattedDateMinusTTE = OffsetDateTime.ofInstant(dateMinusTTEAsInstant, ZoneId.systemDefault())
                    sqlFormat = Types.TIMESTAMP_WITH_TIMEZONE
                }
            }
            ExpirationBase.FIRST_WRITE -> {
                expirationBaseColumn = "${PostgresColumn.VERSIONS.name}[array_upper(${PostgresColumn.VERSIONS.name},1)]" //gets the latest version from the versions column
                formattedDateMinusTTE = dateMinusTTEAsInstant.toEpochMilli()
                sqlFormat = Types.BIGINT
            }
            ExpirationBase.LAST_WRITE -> {
                expirationBaseColumn = DataTables.LAST_WRITE.name
                formattedDateMinusTTE = OffsetDateTime.ofInstant(dateMinusTTEAsInstant, ZoneId.systemDefault())
                sqlFormat = Types.TIMESTAMP_WITH_TIMEZONE
            }
        }
        return Triple(expirationBaseColumn, formattedDateMinusTTE, sqlFormat)
    }
}