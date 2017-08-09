/*
 * Copyright (C) 2017. OpenLattice, Inc
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

package com.dataloom.data.hazelcast;

import static com.dataloom.data.mapstores.DataMapstore.KEY_ENTITY_SET_ID;
import static com.dataloom.data.mapstores.DataMapstore.KEY_SYNC_ID;

import com.datastax.driver.core.Row;
import com.hazelcast.query.Predicate;
import com.hazelcast.query.Predicates;
import com.kryptnostic.datastore.cassandra.RowAdapters;
import java.util.Collection;
import java.util.Set;
import java.util.UUID;

/**
 * @author Matthew Tamayo-Rios &lt;matthew@openlattice.com&gt;
 */
public final class EntitySets {


    private EntitySets() {
    }

    public static Predicate filterByEntitySetIdAndSyncId( Row row ) {
        UUID entitySetId = RowAdapters.entitySetId( row );
        UUID syncId = RowAdapters.syncId( row );
        return filterByEntitySetIdAndSyncId( entitySetId, syncId );
    }

    public static Predicate getEntity( UUID entitySetId, UUID syncId, String entityId ) {
        return Predicates.and(
                Predicates.equal( KEY_ENTITY_SET_ID, entitySetId ),
                Predicates.equal( KEY_SYNC_ID, syncId ),
                Predicates.equal( "__key#entityId", entityId ) );

    }

    public static Predicate getEntities( Collection<UUID> ids ) {
        return getEntities( ids.toArray( new UUID[] {} ) );
    }

    public static Predicate getEntities( UUID[] ids ) {
        return Predicates.in( "__key#id", ids );
    }

    public static Predicate getEntity( UUID id ) {
        return Predicates.equal( "__key#id", id );
    }

    public static Predicate getEntity( UUID entitySetId, UUID syncId, String entityId, Set<UUID> propertyTypeIds ) {
        return Predicates.and(
                Predicates.equal( "__key#entitySetId", entitySetId ),
                Predicates.equal( "__key#syncId", syncId ),
                Predicates.equal( "__key#entityId", entityId ),
                Predicates.in( "__key#propertyTypeId", propertyTypeIds.toArray( new UUID[] {} ) ) );
    }

    public static Predicate filterByEntitySetIdAndSyncId( UUID entitySetId, UUID syncId ) {
        return Predicates.and(
                Predicates.equal( "__key#entitySetId", entitySetId ),
                Predicates.equal( "__key#syncId", syncId ) );
    }
}
