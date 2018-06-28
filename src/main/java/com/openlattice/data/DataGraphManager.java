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

import com.google.common.collect.SetMultimap;
import com.openlattice.analysis.requests.TopUtilizerDetails;
import com.openlattice.data.requests.Association;
import com.openlattice.data.requests.AssociationRequest;
import com.openlattice.data.requests.Entity;
import com.openlattice.data.requests.EntityRequest;
import com.openlattice.edm.type.PropertyType;
import com.openlattice.graph.core.objects.NeighborTripletSet;
import com.openlattice.graph.edge.EdgeKey;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import org.apache.olingo.commons.api.edm.EdmPrimitiveTypeKind;
import org.apache.olingo.commons.api.edm.FullQualifiedName;

public interface DataGraphManager {
    /*
     * Entity set methods
     */
    EntitySetData<FullQualifiedName> getEntitySetData(
            UUID entitySetId,
            UUID syncId,
            LinkedHashSet<String> orderedPropertyNames,
            Map<UUID, PropertyType> authorizedPropertyTypes );

    // TODO remove vertices too
    void deleteEntitySetData( UUID entitySetId );

    /*
     * CRUD methods for entity
     */
    SetMultimap<FullQualifiedName, Object> getEntity(
            UUID entityKeyId,
            Map<UUID, PropertyType> authorizedPropertyTypes );

    void updateEntity(
            UUID elementId,
            SetMultimap<UUID, Object> entityDetails,
            Map<UUID, EdmPrimitiveTypeKind> authorizedPropertiesWithDataType );

    void updateEntity(
            EntityKey elementReference,
            SetMultimap<UUID, Object> entityDetails,
            Map<UUID, EdmPrimitiveTypeKind> authorizedPropertiesWithDataType );

    void deleteEntity( EntityDataKey edk );

    void deleteAssociation( EdgeKey key );

    /*
     * Bulk endpoints for entities/associations
     */

    UUID createEntity(
            UUID entitySetId,
            UUID syncId,
            String entityId,
            SetMultimap<UUID, Object> entityDetails,
            Map<UUID, EdmPrimitiveTypeKind> authorizedPropertiesWithDataType )
            throws ExecutionException, InterruptedException;

    void createEntities(
            UUID entitySetId,
            UUID syncId,
            Map<String, SetMultimap<UUID, Object>> entities,
            Map<UUID, EdmPrimitiveTypeKind> authorizedPropertiesWithDataType )
            throws ExecutionException, InterruptedException;

    void replaceEntity(
            EntityDataKey edk,
            SetMultimap<UUID, Object> entity,
            Map<UUID, EdmPrimitiveTypeKind> propertyTypes );

    void createAssociations(
            UUID entitySetId,
            UUID syncId,
            Set<Association> associations,
            Map<UUID, EdmPrimitiveTypeKind> authorizedPropertiesWithDataType )
            throws ExecutionException, InterruptedException;

    void createEntitiesAndAssociations(
            Set<Entity> entities,
            Set<Association> associations,
            Map<UUID, Map<UUID, EdmPrimitiveTypeKind>> authorizedPropertiesByEntitySetId )
            throws ExecutionException, InterruptedException;

    void bulkCreateEntityData(
            Set<EntityRequest> entities,
            Set<AssociationRequest> associations,
            Map<UUID, Map<UUID, EdmPrimitiveTypeKind>> authorizedPropertiesByEntitySetId )
            throws ExecutionException, InterruptedException;

    public Iterable<SetMultimap<Object, Object>> getTopUtilizers(
            UUID entitySetId,
            UUID syncId,
            List<TopUtilizerDetails> topUtilizerDetails,
            int numResults,
            Map<UUID, PropertyType> authorizedPropertyTypes )
            throws InterruptedException, ExecutionException;

    NeighborTripletSet getNeighborEntitySets( UUID entitySetId, UUID syncId );

}
