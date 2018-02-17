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

package com.dataloom.edm.events;

import java.util.List;
import java.util.UUID;

import com.openlattice.edm.type.PropertyType;

public class PropertyTypesInEntitySetUpdatedEvent {
    
    private UUID entitySetId;
    private List<PropertyType> newPropertyTypes;
    
    public PropertyTypesInEntitySetUpdatedEvent( UUID entitySetId, List<PropertyType> newPropertyTypes ) {
        this.entitySetId = entitySetId;
        this.newPropertyTypes = newPropertyTypes;
    }
    
    public UUID getEntitySetId() {
        return entitySetId;
    }
    
    public List<PropertyType> getNewPropertyTypes() {
        return newPropertyTypes;
    }

}
