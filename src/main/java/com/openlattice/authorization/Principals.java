

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

package com.openlattice.authorization;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkState;
import static com.openlattice.authorization.mapstores.PrincipalMapstore.PRINCIPAL_INDEX;

import com.google.common.base.MoreObjects;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Iterables;
import com.hazelcast.core.HazelcastInstance;
import com.hazelcast.core.IMap;
import com.hazelcast.query.Predicates;
import com.openlattice.hazelcast.HazelcastMap;
import com.openlattice.organizations.SortedPrincipalSet;
import com.openlattice.organizations.roles.SecurePrincipalsManager;
import java.util.NavigableSet;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import javax.annotation.Nonnull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

public final class Principals {
    private static final Logger                           logger      = LoggerFactory
            .getLogger( Principals.class );
    private static final Lock                             startupLock = new ReentrantLock();
    private static       IMap<AclKey, SecurablePrincipal> principals;
    private static       IMap<String, SortedPrincipalSet> resolvedPrincipalTrees;

    private Principals() {
    }

    public static void init( SecurePrincipalsManager spm, HazelcastInstance hazelcastInstance ) {
        if ( startupLock.tryLock() ) {
            principals = hazelcastInstance.getMap( HazelcastMap.PRINCIPALS.name() );
            resolvedPrincipalTrees = hazelcastInstance.getMap( HazelcastMap.RESOLVED_PRINCIPAL_TREES.name() );
        } else {
            logger.error( "Principals security processing can only be initialized once." );
            throw new IllegalStateException( "Principals context already initialized." );
        }
    }

    public static void ensureOrganization( Principal principal ) {
        checkArgument( principal.getType().equals( PrincipalType.ORGANIZATION ) );
    }

    public static void ensureUser( Principal principal ) {
        checkState( principal.getType().equals( PrincipalType.USER ), "Only user principal type allowed." );
    }

    /**
     * This will retrieve the current user. If auth information isn't present an NPE is thrown (by design). If the wrong
     * type of auth is present a ClassCast exception will be thrown (by design).
     *
     * @return The principal for the current request.
     */
    public static @Nonnull Principal getCurrentUser() {
        return getUserPrincipal( getCurrentPrincipalId() );
    }

    public static SecurablePrincipal getCurrentSecurablePrincipal() {
        final var maybePrincipal = principals.values( Predicates.equal( PRINCIPAL_INDEX, getCurrentPrincipalId() ) );

    }

    public static Principal getUserPrincipal( String principalId ) {
        return new Principal( PrincipalType.USER, principalId );
    }

    public static NavigableSet<Principal> getUserPrincipals( String principalId ) {
        return resolvedPrincipalTrees.get( principalId );
    }

    public static NavigableSet<Principal> getCurrentPrincipals() {
        return MoreObjects
                .firstNonNull( resolvedPrincipalTrees.get( getCurrentPrincipalId() ), ImmutableSortedSet.of() );
    }

    public static Principal getAdminRole() {
        return SystemRole.ADMIN.getPrincipal();
    }

    public static SimpleGrantedAuthority fromPrincipal( Principal p ) {
        return new SimpleGrantedAuthority( p.getType().name() + "|" + p.getId() );
    }

    private static String getPrincipalId( Authentication authentication ) {
        return authentication.getPrincipal().toString();
    }

    private static String getCurrentPrincipalId() {
        return getPrincipalId( SecurityContextHolder.getContext().getAuthentication() );
    }

    public static void invalidatePrincipalCache( String principalId ) {
        principals.evict( principalId );
        resolvedPrincipalTrees.evict( principalId );
    }
}

