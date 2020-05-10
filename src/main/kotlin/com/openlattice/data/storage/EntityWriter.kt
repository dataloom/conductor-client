package com.openlattice.data.storage

import com.openlattice.data.Property
import com.openlattice.data.WriteEvent
import com.openlattice.edm.type.PropertyType
import java.nio.ByteBuffer
import java.util.*

/**
 *
 * @author Matthew Tamayo-Rios &lt;matthew@openlattice.com&gt;
 */
interface EntityWriter {
    /**
     * Creates entities if they do not exist and then adds the provided properties to specified entities.
     *
     * @param entitySetId The entity set in which to upsert the entities.
     * @param entities The entities to upsert.
     * @param authorizedPropertyTypes The property types to write.
     */
    fun createOrUpdateEntities(
            entitySetId: UUID,
            entities: Map<UUID, MutableMap<UUID, MutableSet<Any>>>,
            authorizedPropertyTypes: Map<UUID, PropertyType>,
            versioned: Boolean = true
    ): WriteEvent

    /**
     * Replaces the contents of an entity in its entirety. Equivalent to a delete of the existing entity and write
     * of new values
     */
    fun replaceEntities(
            entitySetId: UUID,
            entities: Map<UUID, MutableMap<UUID, MutableSet<Any>>>,
            authorizedPropertyTypes: Map<UUID, PropertyType>
    ): WriteEvent

    /**
     * Replaces a subset of the properties of an entity specified in the provided `entity` argument.
     */
    fun partialReplaceEntities(
            entitySetId: UUID,
            entities: Map<UUID, MutableMap<UUID, MutableSet<Any>>>,
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
    fun clearEntityProperties(
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

    /**
     * Writes entities along with their history. Useful for migrating data from one entity store to another.
     *
     */
    fun writeEntitiesWithHistory(entities: Map<UUID, MutableMap<UUID, MutableSet<Property>>>)
}