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

package com.openlattice.organizations.roles;

import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Preconditions.checkState;

import com.google.common.base.MoreObjects;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.SetMultimap;
import com.google.common.eventbus.EventBus;
import com.hazelcast.core.HazelcastInstance;
import com.hazelcast.core.IMap;
import com.hazelcast.query.Predicate;
import com.hazelcast.query.Predicates;
import com.openlattice.authorization.AclKey;
import com.openlattice.authorization.AclKeySet;
import com.openlattice.authorization.AuthorizationManager;
import com.openlattice.authorization.AuthorizingComponent;
import com.openlattice.authorization.HazelcastAclKeyReservationService;
import com.openlattice.authorization.Permission;
import com.openlattice.authorization.Principal;
import com.openlattice.authorization.PrincipalType;
import com.openlattice.authorization.SecurablePrincipal;
import com.openlattice.authorization.mapstores.PrincipalMapstore;
import com.openlattice.authorization.projections.PrincipalProjection;
import com.openlattice.authorization.securable.SecurableObjectType;
import com.openlattice.controllers.exceptions.TypeExistsException;
import com.openlattice.datastore.util.Util;
import com.openlattice.directory.pojo.Auth0UserBasic;
import com.openlattice.hazelcast.HazelcastMap;
import com.openlattice.organization.roles.Role;
import com.openlattice.organizations.processors.NestedPrincipalMerger;
import com.openlattice.organizations.processors.NestedPrincipalRemover;
import com.openlattice.organizations.roles.processors.PrincipalDescriptionUpdater;
import com.openlattice.organizations.roles.processors.PrincipalTitleUpdater;
import com.openlattice.principals.RoleCreatedEvent;
import com.openlattice.principals.UserCreatedEvent;

import java.util.Collection;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import javax.annotation.Nullable;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class HazelcastPrincipalService implements SecurePrincipalsManager, AuthorizingComponent {

    private static final Logger logger = LoggerFactory
            .getLogger( HazelcastPrincipalService.class );

    private final AuthorizationManager                  authorizations;
    private final HazelcastAclKeyReservationService     reservations;
    private final IMap<AclKey, SecurablePrincipal>      principals;
    private final IMap<AclKey, AclKeySet>               principalTrees; // RoleName -> Member RoleNames
    private final IMap<String, Auth0UserBasic>          users;
    private final IMap<List<UUID>, SecurableObjectType> securableObjectTypes;
    private final EventBus                              eventBus;

    public HazelcastPrincipalService(
            HazelcastInstance hazelcastInstance,
            HazelcastAclKeyReservationService reservations,
            AuthorizationManager authorizations,
            EventBus eventBus ) {

        this.authorizations = authorizations;
        this.reservations = reservations;
        this.principals = hazelcastInstance.getMap( HazelcastMap.PRINCIPALS.name() );
        this.principalTrees = hazelcastInstance.getMap( HazelcastMap.PRINCIPAL_TREES.name() );
        this.users = hazelcastInstance.getMap( HazelcastMap.USERS.name() );
        this.securableObjectTypes = hazelcastInstance.getMap( HazelcastMap.SECURABLE_OBJECT_TYPES.name() );
        this.eventBus = checkNotNull( eventBus );
    }

    @Override
    public boolean createSecurablePrincipalIfNotExists(
            Principal owner, SecurablePrincipal principal ) {
        try {
            createSecurablePrincipal( owner, principal );
            return true;
        } catch ( TypeExistsException e ) {
            logger.warn( "Securable Principal {} already exists", principal );
            logger.debug( "Stack trace for securable principal already exists.", e );
            return false;
        }
    }

    @Override
    public void createSecurablePrincipal(
            Principal owner, SecurablePrincipal principal ) {
        createSecurablePrincipal( principal );
        final AclKey aclKey = principal.getAclKey();

        try {
            authorizations.setSecurableObjectType( aclKey, principal.getCategory() );
            authorizations.addPermission( aclKey, owner, EnumSet.allOf( Permission.class ) );
        } catch ( Exception e ) {
            logger.error( "Unable to create principal {}", principal, e );
            Util.deleteSafely( principals, aclKey );
            reservations.release( principal.getId() );
            authorizations.deletePermissions( aclKey );
            throw new IllegalStateException( "Unable to create principal: " + principal.toString() );
        }
    }

    private void createSecurablePrincipal( SecurablePrincipal principal ) {
        reservations.reserveIdAndValidateType( principal, principal::getName );
        principals.set( principal.getAclKey(), principal );
        if ( !principalTrees.containsKey( principal.getAclKey() ) ) {
            principalTrees.set( principal.getAclKey(), new AclKeySet() );
        }

        securableObjectTypes.putIfAbsent( principal.getAclKey(), principal.getCategory() );
        switch ( principal.getPrincipalType() ) {
            case USER:
                eventBus.post( new UserCreatedEvent( principal ) );
                break;
            case ROLE:
                eventBus.post( new RoleCreatedEvent( (Role) principal ) );
                break;
        }
    }

    @Override
    public void updateTitle( AclKey aclKey, String title ) {
        principals.executeOnKey( aclKey, new PrincipalTitleUpdater( title ) );
    }

    @Override
    public void updateDescription( AclKey aclKey, String description ) {
        principals.executeOnKey( aclKey, new PrincipalDescriptionUpdater( description ) );
    }

    @Override
    public @Nullable SecurablePrincipal getSecurablePrincipal( AclKey aclKey ) {
        return principals.get( aclKey );
    }

    @Override
    public AclKey lookup( Principal p ) {
        return principals.values( findPrincipal( p ) ).stream().map( SecurablePrincipal::getAclKey ).findFirst().get();
    }

    @Override
    public Map<Principal, AclKey> lookup( Set<Principal> p ) {
        return principals.values( findPrincipals( p ) ).stream()
                .collect( Collectors.toMap( SecurablePrincipal::getPrincipal, SecurablePrincipal::getAclKey ) );
    }

    @Override
    public Role lookupRole( Principal principal ) {
        if ( principal.getType() != PrincipalType.ROLE ) {
            throw new IllegalArgumentException( "The provided principal is not a role" );
        }
        return (Role) principals.values( findPrincipal( principal ) ).stream().findFirst().get();
    }

    @Override
    public SecurablePrincipal getPrincipal( String principalId ) {
        final UUID id = checkNotNull( reservations.getId( principalId ),
                "AclKey not found for Principal %s",
                principalId );
        return Util.getSafely( principals, new AclKey( id ) );
    }

    @Override
    public Optional<SecurablePrincipal> maybeGetSecurablePrincipal( Principal p ) {
        final UUID id = reservations.getId( p.getId() );
        if ( id == null ) {
            return Optional.empty();
        }
        return Optional.ofNullable( Util.getSafely( principals, new AclKey( id ) ) );
    }

    @Override
    public Collection<SecurablePrincipal> getSecurablePrincipals( PrincipalType principalType ) {
        return principals.values( Predicates.equal( "principalType", principalType ) );
    }

    @Override
    public SetMultimap<SecurablePrincipal, SecurablePrincipal> getRolesForUsersInOrganization( UUID organizationId ) {
        //  new PagingPredicate<>();
        return null;
    }

    @Override
    public Collection<SecurablePrincipal> getAllRolesInOrganization( UUID organizationId ) {
        Predicate rolesInOrganization = Predicates.and( Predicates.equal( "principalType", PrincipalType.ROLE ),
                Predicates.equal( "aclKey[0]", organizationId ) );
        return principals.values( rolesInOrganization );
    }

    @Override
    public void deletePrincipal( AclKey aclKey ) {
        ensurePrincipalsExist( aclKey );
        authorizations.deletePrincipalPermissions( principals.get( aclKey ).getPrincipal() );
        removePrincipalFromPrincipals( aclKey, hasSecurablePrincipal( aclKey ) );
        Util.deleteSafely( principalTrees, aclKey );
        Util.deleteSafely( principals, aclKey );
    }

    @Override
    public void deleteAllRolesInOrganization( UUID organizationId ) {
        Collection<SecurablePrincipal> allRolesInOrg = getAllRolesInOrganization( organizationId );
        allRolesInOrg.stream().map( SecurablePrincipal::getAclKey ).forEach( this::deletePrincipal );
    }

    @Override
    public void addPrincipalToPrincipal( AclKey source, AclKey target ) {
        ensurePrincipalsExist( source, target );
        principalTrees.executeOnKey( target, new NestedPrincipalMerger( ImmutableSet.of( source ) ) );
    }

    @Override
    public void removePrincipalFromPrincipal( AclKey source, AclKey target ) {
        ensurePrincipalsExist( source, target );
        principalTrees.executeOnKey( target, new NestedPrincipalRemover( ImmutableSet.of( source ) ) );
    }

    @Override
    public void removePrincipalsFromPrincipals( Collection<AclKey> source, Set<AclKey> target ) {
        ensurePrincipalsExist( target );
        ensurePrincipalsExist( source );
        principalTrees.executeOnKeys( target, new NestedPrincipalRemover( source ) );
    }

    @Override
    public void removePrincipalFromPrincipals( AclKey source, Predicate targetFilter ) {
        principalTrees.executeOnEntries( new NestedPrincipalRemover( ImmutableSet.of( source ) ), targetFilter );
    }

    @Override
    public Collection<SecurablePrincipal> getAllPrincipalsWithPrincipal( AclKey aclKey ) {
        //We start from the bottom layer and use predicates to sweep up the tree and enumerate all roles with this role.
        Set<AclKey> parentLayer = principalTrees.keySet( hasSecurablePrincipal( aclKey ) );
        final Set<AclKey> principalsWithPrincipal = new HashSet<>(parentLayer);

        while ( !parentLayer.isEmpty() ) {
            parentLayer = parentLayer
                    .parallelStream()
                    .flatMap( ak -> principalTrees.keySet( hasSecurablePrincipal( ak ) ).stream() )
                    .collect( Collectors.toSet() );
            principalsWithPrincipal.addAll( parentLayer );
        }
        return principals.getAll( principalsWithPrincipal ).values();
    }

    @Override
    public Collection<SecurablePrincipal> getParentPrincipalsOfPrincipal( AclKey aclKey ) {
        Set<AclKey> parentLayer = principalTrees.keySet( hasSecurablePrincipal( aclKey ) );
        return principals.getAll( parentLayer ).values();
    }

    @Override
    public boolean principalHasChildPrincipal( AclKey parent, AclKey child ) {
        return MoreObjects.firstNonNull( principalTrees.get( parent ), ImmutableSet.of() ).contains( child );
    }

    @Override
    public Collection<Principal> getAllUsersWithPrincipal( AclKey aclKey ) {
        return getAllPrincipalsWithPrincipal( aclKey )
                .stream()
                .filter( sp -> sp.getPrincipalType().equals( PrincipalType.USER ) )
                .map( SecurablePrincipal::getPrincipal )
                .collect( Collectors.toList() );
    }

    @Override
    public Collection<Auth0UserBasic> getAllUserProfilesWithPrincipal( AclKey principal ) {
        return users.getAll( getAllUsersWithPrincipal( principal )
                .stream()
                .map( Principal::getId )
                .collect( Collectors.toSet() ) ).values();
    }

    @Override
    public Collection<SecurablePrincipal> getSecurablePrincipals( Predicate p ) {
        return principals.values( p );
    }

    @Override
    public Collection<Principal> getPrincipals( Predicate<AclKey, SecurablePrincipal> p ) {
        return principals.project( new PrincipalProjection(), p );
    }

    @Override
    public Collection<SecurablePrincipal> getSecurablePrincipals( Collection<Principal> members ) {
        final var p = findPrincipals( members );
        return principals.values( p );
    }

    @Override
    public boolean principalExists( Principal p ) {
        return !principals.keySet( Predicates.equal( PrincipalMapstore.PRINCIPAL_INDEX, p ) ).isEmpty();
    }

    @Override
    public boolean isPrincipalIdAvailable( String principalId ) {
        return reservations.isReserved( principalId );
    }

    @Override
    public Auth0UserBasic getUser( String userId ) {
        return Util.getSafely( users, userId );
    }

    @Override
    public Role getRole( UUID organizationId, UUID roleId ) {
        AclKey aclKey = new AclKey( organizationId, roleId );
        return (Role) Util.getSafely( principals, aclKey );
    }

    @Override
    public Collection<SecurablePrincipal> getAllPrincipals( SecurablePrincipal sp ) {
        final AclKeySet roles = Util.getSafely( principalTrees, sp.getAclKey() );
        if ( roles == null ) {
            return ImmutableList.of();
        }
        Set<AclKey> nextLayer = roles;

        while ( !nextLayer.isEmpty() ) {
            Map<AclKey, AclKeySet> nextRoles = principalTrees.getAll( nextLayer );
            nextLayer = nextRoles.values().stream().flatMap( AclKeySet::stream )
                    .filter( aclKey -> !roles.contains( aclKey ) ).collect( Collectors.toSet() );
            roles.addAll( nextLayer );
        }

        return principals.getAll( roles ).values();
    }

    @Override
    public Set<Principal> getAuthorizedPrincipalsOnSecurableObject( AclKey key, EnumSet<Permission> permissions ) {
        return authorizations.getAuthorizedPrincipalsOnSecurableObject( key, permissions );
    }

    private void ensurePrincipalsExist( AclKey... aclKeys ) {
        ensurePrincipalsExist( Stream.of( aclKeys ) );
    }

    private void ensurePrincipalsExist( Collection<AclKey> aclKeys ) {
        ensurePrincipalsExist( aclKeys.stream() );
    }

    private void ensurePrincipalsExist( Stream<AclKey> aclKeys ) {
        checkState( aclKeys
                .filter( aclKey -> !principals.containsKey( aclKey ) )
                .peek( aclKey -> logger.error( "Principal with acl key {} does not exists!", aclKey ) )
                .count() == 0, "All principals must exists!" );
    }

    @Override
    public AuthorizationManager getAuthorizationManager() {
        return authorizations;
    }

    private static Predicate findPrincipal( Principal p ) {
        return Predicates.equal( PrincipalMapstore.PRINCIPAL_INDEX, p );
    }

    private static Predicate findPrincipals( Collection<Principal> principals ) {
        return Predicates.in( PrincipalMapstore.PRINCIPAL_INDEX, principals.toArray( new Principal[] {} ) );
    }

    private static Predicate findPrincipals( Set<Principal> p ) {
        return Predicates.in( "principal", p.toArray( new Principal[] {} ) );
    }

    private static Predicate hasSecurablePrincipal( AclKey principalAclKey ) {
        return Predicates.and( Predicates.equal( "this.index[any]", principalAclKey.getIndex() ) );
    }

}
