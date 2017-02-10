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

package com.dataloom.authorization;

import java.util.List;
import java.util.UUID;

import com.dataloom.hazelcast.HazelcastMap;
import com.hazelcast.core.HazelcastInstance;
import com.hazelcast.core.IMap;
 
public class HazelcastAbstractSecurableObjectResolveTypeService implements AbstractSecurableObjectResolveTypeService {
    
    private final IMap<List<UUID>, SecurableObjectType> securableObjectTypes;
    
    public HazelcastAbstractSecurableObjectResolveTypeService( HazelcastInstance hazelcastInstance ) {
        securableObjectTypes = hazelcastInstance.getMap( HazelcastMap.SECURABLE_OBJECT_TYPES.name() );
    }
    
    @Override
    public void createSecurableObjectType( List<UUID> aclKey, SecurableObjectType type ) {
        securableObjectTypes.set( aclKey, type );
        
    }

    @Override
    public void deleteSecurableObjectType( List<UUID> aclKey ) {
        securableObjectTypes.remove( aclKey );
    }
    
    @Override
    public SecurableObjectType getSecurableObjectType( List<UUID> aclKey ) {
        return securableObjectTypes.get( aclKey );
    }

}
