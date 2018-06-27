

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

import com.google.common.collect.ListMultimap;
import com.google.common.collect.SetMultimap;
import com.openlattice.edm.type.PropertyType;
import java.nio.ByteBuffer;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;
import org.apache.olingo.commons.api.edm.FullQualifiedName;

/**
 * @author Matthew Tamayo-Rios &lt;matthew@openlattice.com&gt;
 */
public interface EntityDatastore {

    EntitySetData<FullQualifiedName> getEntitySetData(
            UUID entitySetId,
            LinkedHashSet<String> orderedPropertyNames,
            Map<UUID, PropertyType> authorizedPropertyTypes );

    Stream<SetMultimap<FullQualifiedName, Object>> getEntities(
            UUID entitySetId,
            Set<UUID> ids,
            Map<UUID, PropertyType> authorizedPropertyTypes );

    ListMultimap<UUID, SetMultimap<FullQualifiedName, Object>> getEntitiesAcrossEntitySets(
            SetMultimap<UUID, UUID> entitySetIdsToEntityKeyIds,
            Map<UUID, Map<UUID, PropertyType>> authorizedPropertyTypesByEntitySet );

    @Deprecated
    SetMultimap<FullQualifiedName, Object> getEntity(
            UUID entitySetId,
            String entityId,
            Map<UUID, PropertyType> authorizedPropertyTypes );

    int createEntities(
            UUID entitySetId,
            Map<UUID, SetMultimap<UUID, Object>> entities,
            Map<UUID, PropertyType> authorizedPropertyTypes );

    /**
     * Replaces the contents of an entity in its entirety. Equivalent to a delete of the existing entity and write
     * of new values
     */
    int replaceEntities(
            UUID entitySetId,
            Map<UUID, SetMultimap<UUID, Object>> entities,
            Map<UUID, PropertyType> authorizedPropertyTypes );

    /**
     * Replaces a subset of the properties of an entity specified in the provided {@code entity} argument.
     */
    int partialReplaceEntities(
            UUID entitySetId,
            Map<UUID, SetMultimap<UUID, Object>> entity,
            Map<UUID, PropertyType> authorizedPropertyTypes );

    /**
     * Replace specific values in an entity
     */
    int replacePropertiesInEntities(
            UUID entitySetId,
            Map<UUID, SetMultimap<UUID, Map<ByteBuffer, Object>>> replacementProperties,
            Map<UUID, PropertyType> authorizedPropertyTypes );

    /**
     * Merges in new entity data without affecting existing entity data.
     */
    int mergeIntoEntities(
            UUID entitySetId,
            Map<UUID,SetMultimap<UUID, Object>> entities,
            Map<UUID, PropertyType> authorizedPropertyTypes );

    /**
     * Clears (soft-deletes) the contents of an entity set by setting version to {@code -now()}
     *
     * @param entitySetId The id of the entity set to clear.
     * @return The number of rows cleared from the entity set.
     */
    int clearEntitySet( UUID entitySetId, Map<UUID, PropertyType> authorizedPropertyTypes );

    /**
     * Clears (soft-deletes) the contents of an entity by setting versions of all properties to {@code -now()}
     *
     * @param entitySetId The id of the entity set to clear.
     * @param entityKeyId The entity key id for the entity set to clear.
     * @return The number of properties cleared.
     */
    int clearEntities( UUID entitySetId, Set<UUID> entityKeyId, Map<UUID, PropertyType> authorizedPropertyTypes );

    /**
     * Deletes an entity set and removes the historical contents. This causes loss of historical data
     * and should only be used for scrubbing customer data.
     *
     * @param entitySetId The entity set id to be hard deleted.
     */
    int deleteEntitySetData( UUID entitySetId, Map<UUID, PropertyType> authorizedPropertyTypes );

    /**
     * Deletes an entity and removes the historical contents.
     *
     * @param entityKeyId The entity key id to be hard deleted.
     */
    int deleteEntities( UUID entitySetId, Set<UUID> entityKeyId, Map<UUID, PropertyType> authorizedPropertyTypes );

}
