/*
 * Copyright (C) 2017. Kryptnostic, Inc (dba Loom)
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
 * You can contact the owner of the copyright at support@thedataloom.com
 */

package com.dataloom.linking;

import com.dataloom.edm.type.EntityType;
import com.dataloom.hazelcast.HazelcastMap;
import com.hazelcast.core.HazelcastInstance;
import com.hazelcast.core.IMap;
import com.kryptnostic.rhizome.hazelcast.objects.DelegatedUUIDSet;

import java.util.Set;
import java.util.UUID;

/**
 * @author Matthew Tamayo-Rios &lt;matthew@kryptnostic.com&gt;
 */
public class HazelcastListingService {
    private final IMap<UUID, DelegatedUUIDSet> linkedEntityTypes;

    public HazelcastListingService( HazelcastInstance hazelcastInstance ) {
        this.linkedEntityTypes = hazelcastInstance.getMap( HazelcastMap.LINKED_ENTITY_TYPES.name() );
    }

    public void setLinkedEntityTypes( UUID entitySetId, Set<UUID> entityTypes ) {
        this.linkedEntityTypes.set( entitySetId, DelegatedUUIDSet.wrap( entityTypes ) );
    }

    public boolean isLinkedEntitySet( UUID entitySetId ) {
        return linkedEntityTypes.containsKey( entitySetId );
    }
}
