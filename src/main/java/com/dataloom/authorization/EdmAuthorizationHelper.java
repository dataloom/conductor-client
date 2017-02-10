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

import java.util.Arrays;
import java.util.EnumSet;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import com.dataloom.edm.internal.EntitySet;
import com.dataloom.edm.internal.EntityType;
import com.hazelcast.util.Preconditions;
import com.kryptnostic.datastore.services.EdmManager;

public class EdmAuthorizationHelper {

    private final EdmManager           edm;
    private final AuthorizationManager authz;

    public EdmAuthorizationHelper( EdmManager edm, AuthorizationManager authz ) {
        this.edm = Preconditions.checkNotNull( edm );
        this.authz = Preconditions.checkNotNull( authz );
    }

    public Set<UUID> getAuthorizedPropertiesOnEntitySet(
            UUID entitySetId,
            EnumSet<Permission> requiredPermissions ) {
        return getAuthorizedPropertiesOnEntitySet(
                entitySetId,
                getAllPropertiesOnEntitySet( entitySetId ),
                requiredPermissions );
    }

    public Set<UUID> getAuthorizedPropertiesOnEntitySet(
            UUID entitySetId,
            Set<UUID> selectedProperties,
            EnumSet<Permission> requiredPermissions ) {
        return selectedProperties.stream()
                .filter( ptId -> authz.checkIfHasPermissions( Arrays.asList( entitySetId,
                        ptId ),
                        Principals.getCurrentPrincipals(),
                        requiredPermissions ) )
                .collect( Collectors.toSet() );
    }

    public Set<UUID> getAllPropertiesOnEntitySet( UUID entitySetId ) {
        EntitySet es = edm.getEntitySet( entitySetId );
        EntityType et = edm.getEntityType( es.getEntityTypeId() );
        return et.getProperties();
    }

}
