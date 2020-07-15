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

import com.google.common.collect.ListMultimap
import com.google.common.collect.SetMultimap
import com.openlattice.analysis.AuthorizedFilteredNeighborsRanking
import com.openlattice.analysis.requests.FilteredNeighborsRankingAggregation
import com.openlattice.data.storage.MetadataOption
import com.openlattice.edm.type.PropertyType
import com.openlattice.analysis.requests.AggregationResult
import com.openlattice.graph.core.NeighborSets
import com.openlattice.graph.edge.Edge
import com.openlattice.postgres.streams.BasePostgresIterable
import com.openlattice.postgres.streams.PostgresIterable
import org.apache.commons.lang3.tuple.Pair
import org.apache.olingo.commons.api.edm.FullQualifiedName
import java.nio.ByteBuffer
import java.time.OffsetDateTime
import java.util.*
import java.util.stream.Stream

/**
 *
 * @author Matthew Tamayo-Rios &lt;matthew@openlattice.com&gt;
 */
interface DataGraphManager {

    /*
     * Entity set methods
     */
    fun getEntitySetData(
            entityKeyIds: Map<UUID, Optional<Set<UUID>>>,
            orderedPropertyNames: LinkedHashSet<String>,
            authorizedPropertyTypes: Map<UUID, Map<UUID, PropertyType>>,
            linking: Boolean
    ): EntitySetData<FullQualifiedName>

    /*
     * CRUD methods for entity
     */
    @Deprecated("v1 style data read.")
    fun getEntity(
            entitySetId: UUID,
            entityKeyId: UUID,
            authorizedPropertyTypes: Map<UUID, PropertyType>
    ): Map<FullQualifiedName, Set<Any>>

    fun getEntityWithPropertyTypeFqns(
            entitySetId: UUID,
            entityKeyId: UUID,
            authorizedPropertyTypes: Map<UUID, PropertyType>,
            metadataOptions: EnumSet<MetadataOption> = EnumSet.noneOf(MetadataOption::class.java)
    ): MutableMap<FullQualifiedName, MutableSet<Property>>

    fun getEntityWithPropertyTypeIds(
            entitySetId: UUID,
            entityKeyId: UUID,
            authorizedPropertyTypes: Map<UUID, PropertyType>,
            metadataOptions: EnumSet<MetadataOption> = EnumSet.noneOf(MetadataOption::class.java)
    ): MutableMap<FullQualifiedName, MutableSet<Property>>

    fun getLinkingEntity(
            entitySetIds: Set<UUID>,
            entityKeyId: UUID,
            authorizedPropertyTypes: Map<UUID, Map<UUID, PropertyType>>
    ): Map<FullQualifiedName, Set<Any>>

    fun getLinkedEntitySetBreakDown(
            linkingIdsByEntitySetId: Map<UUID, Optional<Set<UUID>>>,
            authorizedPropertyTypesByEntitySetId: Map<UUID, Map<UUID, PropertyType>>
    ): Map<UUID, Map<UUID, Map<UUID, Map<FullQualifiedName, Set<Any>>>>>


    fun getEntitiesWithPropertyTypeFqns(
            entityKeyIds: Map<UUID, Optional<Set<UUID>>>,
            authorizedPropertyTypes: Map<UUID, Map<UUID, PropertyType>>,
            metadataOptions: EnumSet<MetadataOption>
    ): Iterable<MutableMap<FullQualifiedName, MutableSet<Property>>>

    fun getEntitiesWithPropertyTypeIds(
            entityKeyIds: Map<UUID, Optional<Set<UUID>>>,
            authorizedPropertyTypes: Map<UUID, Map<UUID, PropertyType>>,
            metadataOptions: EnumSet<MetadataOption>
    ): Iterable<MutableMap<UUID, MutableSet<Property>>>

    @Deprecated("v1 api")
    fun getEntitiesWithMetadata(
            entityKeyIds: Map<UUID, Optional<Set<UUID>>>,
            authorizedPropertyTypes: Map<UUID, Map<UUID, PropertyType>>,
            metadataOptions: EnumSet<MetadataOption>
    ): Iterable<MutableMap<FullQualifiedName, MutableSet<Any>>>

    fun getEntitiesWithMetadata(
            entitySetId: UUID,
            ids: Set<UUID>,
            authorizedPropertyTypes: Map<UUID, Map<UUID, PropertyType>>,
            metadataOptions: EnumSet<MetadataOption>
    ): Stream<Map<FullQualifiedName, MutableSet<Any>>>

    fun getEntitiesAcrossEntitySets(
            entitySetIdsToEntityKeyIds: Map<UUID, Set<UUID>>,
            authorizedPropertyTypesByEntitySet: Map<UUID, Map<UUID, PropertyType>>
    ): Map<UUID, Map<UUID, MutableMap<FullQualifiedName, MutableSet<Property>>>>


    /**
     * Clears property data, id, edges of association entities of the provided DataEdgeKeys in batches.
     * Note: it only clears edge, not src or dst entities.
     */
    fun clearAssociationsBatch(
            entitySetId: UUID,
            associationsEdgeKeys: Iterable<DataEdgeKey>,
            authorizedPropertyTypes: Map<UUID, Map<UUID, PropertyType>>
    ): List<WriteEvent>

    /**
     * Deletes property data, id, edges of association entities of the provided DataEdgeKeys in batches.
     * Note: it only deletes edge, not src or dst entities.
     */
    fun deleteAssociationsBatch(
            entitySetId: UUID,
            associationsEdgeKeys: Iterable<DataEdgeKey>,
            authorizedPropertyTypes: Map<UUID, Map<UUID, PropertyType>>
    ): List<WriteEvent>

    /*
     * Bulk endpoints for entities/associations
     */

    fun getEntityKeyIds(entityKeys: Set<EntityKey>): Set<UUID>

    fun createEntities(
            entitySetId: UUID,
            entities: List<MutableMap<UUID, MutableSet<Any>>>,
            authorizedPropertyTypes: Map<UUID, PropertyType>
    ): Pair<List<UUID>, WriteEvent>

    fun replaceEntities(
            entitySetId: UUID,
            entities: Map<UUID, MutableMap<UUID, MutableSet<Any>>>,
            authorizedPropertyTypes: Map<UUID, PropertyType>
    ): WriteEvent

    fun partialReplaceEntities(
            entitySetId: UUID,
            entities: Map<UUID, MutableMap<UUID, MutableSet<Any>>>,
            authorizedPropertyTypes: Map<UUID, PropertyType>
    ): WriteEvent

    fun replacePropertiesInEntities(
            entitySetId: UUID,
            replacementProperties: Map<UUID, Map<UUID, Set<Map<ByteBuffer, Any>>>>,
            authorizedPropertyTypes: Map<UUID, PropertyType>
    ): WriteEvent

    fun createAssociations(associations: Set<DataEdgeKey>): WriteEvent

    fun createAssociations(
            associations: ListMultimap<UUID, DataEdge>,
            authorizedPropertiesByEntitySetId: Map<UUID, Map<UUID, PropertyType>>
    ): Map<UUID, CreateAssociationEvent>

    fun getTopUtilizers(
            entitySetId: UUID,
            filteredNeighborsRankingList: List<FilteredNeighborsRankingAggregation>,
            numResults: Int,
            authorizedPropertyTypes: Map<UUID, PropertyType>
    ): Stream<SetMultimap<FullQualifiedName, Any>>

    fun getFilteredRankings(
            entitySetIds: Set<UUID>,
            numResults: Int,
            filteredRankings: List<AuthorizedFilteredNeighborsRanking>,
            authorizedPropertyTypes: Map<UUID, Map<UUID, PropertyType>>,
            linked: Boolean,
            linkingEntitySetId: Optional<UUID>
    ): AggregationResult

    fun getNeighborEntitySets(entitySetIds: Set<UUID>): List<NeighborSets>

    /**
     * The values here are mutable because some entity stores need to decorate entities with additional metadata.
     */
    fun mergeEntities(
            entitySetId: UUID,
            entities: Map<UUID, MutableMap<UUID, MutableSet<Any>>>,
            authorizedPropertyTypes: Map<UUID, PropertyType>
    ): WriteEvent

    fun getNeighborEntitySetIds(entitySetIds: Set<UUID>): Set<UUID>

    fun getEdgesAndNeighborsForVertex(entitySetId: UUID, entityKeyId: UUID): Stream<Edge>

    /**
     * Returns all [DataEdgeKey]s where either src, dst and/or edge entity set ids are equal the requested entitySetId.
     * If includeClearedEdges is set to true, it will also return cleared (version < 0) entities.
     */
    fun getEdgeKeysOfEntitySet(entitySetId: UUID, includeClearedEdges: Boolean): PostgresIterable<DataEdgeKey>

    /**
     * Returns all [DataEdgeKey]s that include requested entityKeyIds either as src, dst and/or edge with the requested
     * entity set id.
     * If includeClearedEdges is set to true, it will also return cleared (version < 0) entities.
     */
    fun getEdgesConnectedToEntities(
            entitySetId: UUID, entityKeyIds: Set<UUID>, includeClearedEdges: Boolean
    ): PostgresIterable<DataEdgeKey>

    fun getExpiringEntitiesFromEntitySet(
            entitySetId: UUID,
            expirationPolicy: DataExpiration,
            dateTime: OffsetDateTime,
            deleteType: DeleteType,
            expirationPropertyType: Optional<PropertyType>
    ): BasePostgresIterable<UUID>

    fun getEdgeEntitySetsConnectedToEntities(entitySetId: UUID, entityKeyIds: Set<UUID>): Set<UUID>
    fun getEdgeEntitySetsConnectedToEntitySet(entitySetId: UUID): Set<UUID>

    fun getLinkingEntitiesWithMetadata(
            linkingIdsByEntitySetIds: Map<UUID, Optional<Set<UUID>>>,
            authorizedPropertiesOfNormalEntitySets: Map<UUID, Map<UUID, PropertyType>>,
            metadataOptions: Set<MetadataOption>
    ): Stream<MutableMap<FullQualifiedName, MutableSet<Any>>>

    fun getEntityKeyIdsOfLinkingIds(
            linkingIds: Set<UUID>, normalEntitySetIds: Set<UUID>
    ): BasePostgresIterable<kotlin.Pair<UUID, Set<UUID>>>
}