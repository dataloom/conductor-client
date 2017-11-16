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

package com.openlattice.bootstrap;

import static com.openlattice.bootstrap.BootstrapConstants.GLOBAL_ORGANIZATION_ID;
import static com.openlattice.bootstrap.BootstrapConstants.OPENLATTICE_ORGANIZATION_ID;
import static com.openlattice.bootstrap.BootstrapConstants.ROOT_PRINCIPAL_ID;

import com.dataloom.authorization.Principal;
import com.dataloom.authorization.PrincipalType;
import com.dataloom.authorization.SystemRole;
import com.dataloom.directory.pojo.Auth0UserBasic;
import com.dataloom.hazelcast.HazelcastMap;
import com.dataloom.organization.roles.Role;
import com.dataloom.organizations.roles.SecurePrincipalsManager;
import com.google.common.base.Optional;
import com.hazelcast.core.HazelcastInstance;
import com.hazelcast.core.IMap;

/**
 * @author Matthew Tamayo-Rios &lt;matthew@openlattice.com&gt;
 */
public class AuthorizationBootstrap {
    public static final Principal OPENLATTICE_PRINCIPAL = new Principal( PrincipalType.ROLE, "openlatticeRole" );
    public static final Role      OPENLATTICE_ROLE      = createOpenlatticeRole();
    public static final Role      GLOBAL_USER_ROLE      = createUserRole();
    public static final Role      GLOBAL_ADMIN_ROLE     = createAdminRole();
    private final IMap<String, Auth0UserBasic> users;
    private final SecurePrincipalsManager      spm;
    private       boolean                      initialized;

    public AuthorizationBootstrap( HazelcastInstance hazelcastInstance, SecurePrincipalsManager spm ) {
        this.users = hazelcastInstance.getMap( HazelcastMap.USERS.name() );
        this.spm = spm;

        spm.createSecurablePrincipalIfNotExists( OPENLATTICE_PRINCIPAL, OPENLATTICE_ROLE );
        spm.createSecurablePrincipalIfNotExists( SystemRole.AUTHENTICATED_USER.getPrincipal(), GLOBAL_USER_ROLE );
        spm.createSecurablePrincipalIfNotExists( SystemRole.ADMIN.getPrincipal(), GLOBAL_ADMIN_ROLE );
        initialized = true;
    }

    public boolean isInitialized() {
        return initialized;
    }

    public static Role createUserRole() {
        return new Role( Optional.absent(),
                GLOBAL_ORGANIZATION_ID,
                SystemRole.AUTHENTICATED_USER.getPrincipal(),
                "OpenLattice User Role",
                Optional.of( "Initial account granting access to everything." ) );
    }

    public static Role createAdminRole() {
        return new Role( Optional.absent(),
                GLOBAL_ORGANIZATION_ID,
                SystemRole.ADMIN.getPrincipal(),
                "Global Admin Role",
                Optional.of( "Global admin role." ) );
    }

    public static Role createOpenlatticeRole() {
        //TODO: Merge this with global roles.
        return new Role( Optional.of( ROOT_PRINCIPAL_ID ),
                OPENLATTICE_ORGANIZATION_ID,
                OPENLATTICE_PRINCIPAL,
                "OpenLattice Root Group",
                Optional.of( "Initial account granting access to everything." ) );
    }
}
