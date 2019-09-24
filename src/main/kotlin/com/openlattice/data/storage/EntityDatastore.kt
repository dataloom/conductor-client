package com.openlattice.data.storage

import com.google.common.collect.ListMultimap
import com.google.common.collect.SetMultimap
import com.openlattice.data.DeleteType
import com.openlattice.data.EntitySetData
import com.openlattice.data.WriteEvent
import com.openlattice.edm.set.ExpirationBase
import com.openlattice.edm.type.PropertyType
import com.openlattice.postgres.streams.BasePostgresIterable
import com.openlattice.postgres.streams.PostgresIterable
import org.apache.olingo.commons.api.edm.FullQualifiedName
import java.nio.ByteBuffer
import java.time.OffsetDateTime
import java.util.*
import java.util.stream.Stream

/**
 *
 * @author Matthew Tamayo-Rios &lt;matthew@openlattice.com&gt;
 */
interface EntityDatastore {

    fun getEntitySetData(
            entitySetId: UUID,
            authorizedPropertyTypes: Map<UUID, PropertyType>
    ): Map<UUID, Map<UUID, Set<Any>>>

    fun getEntityKeyIdsInEntitySet(entitySetId: UUID): BasePostgresIterable<UUID>

    fun getEntities(
            entitySetId: UUID,
            ids: Set<UUID>,
            authorizedPropertyTypes: Map<UUID, Map<UUID, PropertyType>>
    ): Stream<MutableMap<FullQualifiedName, MutableSet<Any>>>

    fun getEntitiesWithMetadata(
            entitySetId: UUID,
            ids: Set<UUID>,
            authorizedPropertyTypes: Map<UUID, Map<UUID, PropertyType>>,
            metadataOptions: EnumSet<MetadataOption>
    ): Stream<MutableMap<FullQualifiedName, MutableSet<Any>>>

    fun getEntitiesById(
            entitySetId: UUID,
            ids: Set<UUID>,
            authorizedPropertyTypes: Map<UUID, Map<UUID, PropertyType>>
    ): Map<UUID, Map<FullQualifiedName, Set<Any>>>

    fun getLinkingEntities(
            entityKeyIds: Map<UUID, Optional<Set<UUID>>>,
            authorizedPropertyTypes: Map<UUID, Map<UUID, PropertyType>>
    ): Stream<MutableMap<FullQualifiedName, MutableSet<Any>>>

    fun getLinkingEntitiesWithMetadata(
            entityKeyIds: Map<UUID, Optional<Set<UUID>>>,
            authorizedPropertyTypes: Map<UUID, Map<UUID, PropertyType>>,
            metadataOptions: EnumSet<MetadataOption>
    ): Stream<MutableMap<FullQualifiedName, MutableSet<Any>>>

    fun getLinkedEntityDataByLinkingId(
            linkingIdsByEntitySetId: Map<UUID, Optional<Set<UUID>>>,
            authorizedPropertyTypesByEntitySetId: Map<UUID, Map<UUID, PropertyType>>
    ): Map<UUID, Map<UUID, Map<UUID, Set<Any>>>>

    fun getLinkedEntityDataByLinkingIdWithMetadata(
            linkingIdsByEntitySetId: Map<UUID, Optional<Set<UUID>>>,
            authorizedPropertyTypesByEntitySetId: Map<UUID, Map<UUID, PropertyType>>,
            metadataOptions: EnumSet<MetadataOption>
    ): Map<UUID, Map<UUID, Map<UUID, Set<Any>>>>

    fun getEntities(
            entityKeyIds: Map<UUID, Optional<Set<UUID>>>,
            orderedPropertyTypes: LinkedHashSet<String>,
            authorizedPropertyTypes: Map<UUID, Map<UUID, PropertyType>>,
            linking: Boolean
    ): EntitySetData<FullQualifiedName>

    fun getEntitiesAcrossEntitySets(
            entitySetIdsToEntityKeyIds: SetMultimap<UUID, UUID>,
            authorizedPropertyTypesByEntitySet: Map<UUID, Map<UUID, PropertyType>>
    ): ListMultimap<UUID, MutableMap<FullQualifiedName, MutableSet<Any>>>

    fun getEntityKeyIdsOfLinkingIds(linkingIds: Set<UUID>): PostgresIterable<Pair<UUID, Set<UUID>>>

    /**
     * Creates entities if they do not exist and then adds the provided properties to specified entities.
     */
    fun createOrUpdateEntities(
            entitySetId: UUID,
            entities: Map<UUID, Map<UUID, Set<Any>>>,
            authorizedPropertyTypes: Map<UUID, PropertyType>
    ): WriteEvent

    @Deprecated("")
    fun integrateEntities(
            entitySetId: UUID,
            entities: Map<UUID, Map<UUID, Set<Any>>>,
            authorizedPropertyTypes: Map<UUID, PropertyType>
    ): WriteEvent

    /**
     * Replaces the contents of an entity in its entirety. Equivalent to a delete of the existing entity and write
     * of new values
     */
    fun replaceEntities(
            entitySetId: UUID,
            entities: Map<UUID, Map<UUID, Set<Any>>>,
            authorizedPropertyTypes: Map<UUID, PropertyType>
    ): WriteEvent

    /**
     * Replaces a subset of the properties of an entity specified in the provided `entity` argument.
     */
    fun partialReplaceEntities(
            entitySetId: UUID,
            entities: Map<UUID, Map<UUID, Set<Any>>>,
            authorizedPropertyTypes: Map<UUID, PropertyType>
    ): WriteEvent

    /**
     * Replace specific values in an entity
     */
    fun replacePropertiesInEntities(
            entitySetId: UUID,
            replacementProperties: Map<UUID, Map<UUID, Set<Map<ByteBuffer, Any>>>>,
            authorizedPropertyTypes: Map<UUID, PropertyType>
    ): WriteEvent

    /**
     * Clears (soft-deletes) the contents of an entity set by setting version to `-now()`
     *
     * @param entitySetId The id of the entity set to clear.
     * @return The number of rows cleared from the entity set.
     */
    fun clearEntitySet(entitySetId: UUID, authorizedPropertyTypes: Map<UUID, PropertyType>): WriteEvent

    /**
     * Clears (soft-deletes) the contents of an entity by setting versions of all properties to `-now()`
     *
     * @param entitySetId             The id of the entity set to clear.
     * @param entityKeyIds            The entity key ids for the entity set to clear.
     * @param authorizedPropertyTypes The property types the user is allowed to clear.
     * @return The number of entities cleared.
     */
    fun clearEntities(
            entitySetId: UUID,
            entityKeyIds: Set<UUID>,
            authorizedPropertyTypes: Map<UUID, PropertyType>
    ): WriteEvent

    /**
     * Clears (soft-deletes) the contents of an entity by setting versions of all properties to `-now()`
     *
     * @param entitySetId             The id of the entity set to clear.
     * @param entityKeyIds            The entity key ids for the entity set to clear.
     * @param authorizedPropertyTypes The property types the user is requested and is allowed to clear.
     * @return The number of properties cleared.
     */
    fun clearEntityData(
            entitySetId: UUID,
            entityKeyIds: Set<UUID>,
            authorizedPropertyTypes: Map<UUID, PropertyType>
    ): WriteEvent

    /**
     * Hard deletes an entity set and removes the historical contents. This causes loss of historical data
     * and should only be used for scrubbing customer data.
     *
     * @param entitySetId             The id of the entity set to delete.
     * @param authorizedPropertyTypes The authorized property types on this entity set. In this case all the property
     * types for its entity type
     */
    fun deleteEntitySetData(entitySetId: UUID, authorizedPropertyTypes: Map<UUID, PropertyType>): WriteEvent

    /**
     * Hard deletes entities and removes the historical contents.
     *
     * @param entitySetId             The id of the entity set from which to delete.
     * @param entityKeyIds            The ids of entities to hard delete.
     * @param authorizedPropertyTypes The authorized property types on this entity set. In this case all the property
     * types for its entity type
     * @return count of deletes
     */
    fun deleteEntities(
            entitySetId: UUID,
            entityKeyIds: Set<UUID>,
            authorizedPropertyTypes: Map<UUID, PropertyType>
    ): WriteEvent

    /**
     * Hard deletes properties of entity and removes the historical contents.
     *
     * @param entitySetId             The id of the entity set from which to delete.
     * @param entityKeyIds            The ids of entities to delete the data from.
     * @param authorizedPropertyTypes The authorized property types to delete the data from.
     */
    fun deleteEntityProperties(
            entitySetId: UUID, entityKeyIds: Set<UUID>, authorizedPropertyTypes: Map<UUID, PropertyType>
    ): WriteEvent

    fun getExpiringEntitiesFromEntitySet(entitySetId: UUID, expirationBaseColumn: String, formattedDateMinusTTE: Any,
                                         sqlFormat: Int, deleteType: DeleteType) : BasePostgresIterable<UUID>

}