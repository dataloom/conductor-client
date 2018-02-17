package com.dataloom.organizations.roles;

import com.dataloom.authorization.*;
import com.openlattice.authorization.Permission;
import com.openlattice.authorization.Principal;
import com.openlattice.authorization.PrincipalType;
import com.openlattice.authorization.securable.SecurableObjectType;
import com.openlattice.directory.pojo.Auth0UserBasic;
import com.dataloom.edm.exceptions.TypeExistsException;
import com.dataloom.hazelcast.HazelcastMap;
import com.openlattice.organization.roles.Role;
import com.dataloom.organizations.processors.NestedPrincipalMerger;
import com.dataloom.organizations.processors.NestedPrincipalRemover;
import com.dataloom.organizations.roles.processors.PrincipalDescriptionUpdater;
import com.dataloom.organizations.roles.processors.PrincipalTitleUpdater;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.SetMultimap;
import com.hazelcast.core.HazelcastInstance;
import com.hazelcast.core.IMap;
import com.hazelcast.map.EntryProcessor;
import com.hazelcast.query.Predicate;
import com.hazelcast.query.Predicates;
import com.kryptnostic.datastore.util.Util;
import com.openlattice.authorization.AclKey;
import com.openlattice.authorization.AclKeySet;
import com.openlattice.authorization.SecurablePrincipal;
import com.openlattice.authorization.projections.PrincipalProjection;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Preconditions.checkState;

public class HazelcastPrincipalService implements SecurePrincipalsManager, AuthorizingComponent {

    private static final Logger logger = LoggerFactory
            .getLogger( HazelcastPrincipalService.class );

    private final AuthorizationManager                  authorizations;
    private final HazelcastAclKeyReservationService     reservations;
    private final IMap<AclKey, SecurablePrincipal>      principals;
    private final IMap<AclKey, AclKeySet>               principalTrees; // RoleName -> Member RoleNames
    private final IMap<String, Auth0UserBasic>          users;
    private final IMap<List<UUID>, SecurableObjectType> securableObjectTypes;

    public HazelcastPrincipalService(
            HazelcastInstance hazelcastInstance,
            HazelcastAclKeyReservationService reservations,
            AuthorizationManager authorizations ) {

        this.authorizations = authorizations;
        this.reservations = reservations;
        this.principals = hazelcastInstance.getMap( HazelcastMap.PRINCIPALS.name() );
        this.principalTrees = hazelcastInstance.getMap( HazelcastMap.PRINCIPAL_TREES.name() );
        this.users = hazelcastInstance.getMap( HazelcastMap.USERS.name() );
        this.securableObjectTypes = hazelcastInstance.getMap( HazelcastMap.SECURABLE_OBJECT_TYPES.name() );
    }

    @Override public void createSecurablePrincipalIfNotExists(
            Principal owner, SecurablePrincipal principal ) {
        try {
            createSecurablePrincipal( owner, principal );
        } catch ( TypeExistsException e ) {
            logger.warn( "Securable Principal {} already exists", principal, e );
        }
    }

    @Override public void createSecurablePrincipal(
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
        securableObjectTypes.putIfAbsent( principal.getAclKey(), principal.getCategory() );
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
    public SecurablePrincipal getSecurablePrincipal( AclKey aclKey ) {
        return principals.get( aclKey );
    }

    @Override public AclKey lookup( Principal p ) {
        return principals.values( findPrincipal( p ) ).stream().map( SecurablePrincipal::getAclKey ).findFirst().get();
    }

    @Override
    public SecurablePrincipal getPrincipal( String principalId ) {
        UUID id = checkNotNull( reservations.getId( principalId ), "AclKey not found for Principal" );
        return Util.getSafely( principals, new AclKey( id ) );
    }

    @Override public Collection<SecurablePrincipal> getSecurablePrincipals( PrincipalType principalType ) {
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
        principalTrees
                .executeOnKey( target, new NestedPrincipalMerger( ImmutableSet.of( source ) ) );
    }

    @Override
    public void removePrincipalFromPrincipal( AclKey source, AclKey target ) {
        ensurePrincipalsExist( source, target );
        principalTrees
                .executeOnKey( target, new NestedPrincipalRemover( ImmutableSet.of( source ) ) );

    }

    @Override
    public void removePrincipalFromPrincipals( AclKey source, Predicate targetFilter ) {
        principalTrees.executeOnEntries( new NestedPrincipalRemover( ImmutableSet.of( source ) ), targetFilter );
    }

    @Override
    public Collection<SecurablePrincipal> getAllPrincipalsWithPrincipal( AclKey aclKey ) {
        //We start from the bottom layer and use predicates to sweep up the tree and enumerate all roles with this role.
        final Set<AclKey> principalsWithPrincipal = new HashSet<>();
        Set<AclKey> parentLayer = principalTrees.keySet( hasSecurablePrincipal( aclKey ) );
        principalsWithPrincipal.addAll( parentLayer );
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
    public Collection<Principal> getAllUsersWithPrincipal( AclKey aclKey ) {
        return getAllPrincipalsWithPrincipal( aclKey )
                .stream()
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
    public Map<AclKey, Object> executeOnPrincipal(
            EntryProcessor<AclKey, SecurablePrincipal> ep,
            Predicate p ) {
        return principals.executeOnEntries( ep, p );
    }

    @Override
    public Collection<SecurablePrincipal> getSecurablePrincipals( Predicate p ) {
        return principals.values( p );
    }

    @Override
    public Collection<Principal> getPrincipals( Predicate<AclKey, SecurablePrincipal> p ) {
        return principals.project( new PrincipalProjection(), p );
    }

    @Override public Collection<SecurablePrincipal> getSecurablePrincipals( Set<Principal> members ) {
        Predicate p = Predicates
                .in( "principal", members.toArray( new Principal[ 0 ] ) );
        return principals.values( p );
    }

    @Override
    public boolean principalExists( Principal p ) {
        return reservations.isReserved( p.getId() );
    }

    @Override public Auth0UserBasic getUser( String userId ) {
        return Util.getSafely( users, userId );
    }

    @Override public Role getRole( UUID organizationId, UUID roleId ) {
        AclKey aclKey = new AclKey( organizationId, roleId );
        return (Role) Util.getSafely( principals, aclKey );
    }

    @Override public Collection<SecurablePrincipal> getAllPrincipals( SecurablePrincipal sp ) {
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

    private void ensurePrincipalsExist( AclKey... aclKeys ) {
        ensurePrincipalsExist( "All principals must exist!", aclKeys );
    }

    private void ensurePrincipalsExist( String msg, AclKey... aclKeys ) {
        checkState( Stream.of( aclKeys )
                .filter( aclKey -> !principals.containsKey( aclKey ) )
                .peek( aclKey -> logger.error( "Principal with acl key {} does not exist!", aclKey ) )
                .count() == 0, msg );
    }

    @Override

    public AuthorizationManager getAuthorizationManager() {
        return authorizations;
    }

    private static Predicate findPrincipal( Principal p ) {
        return Predicates.equal( "principal", p );
    }

    private static Predicate hasSecurablePrincipal( AclKey principalAclKey ) {
        return Predicates.and( Predicates.equal( "this.index[any]", principalAclKey.getIndex() ) );
    }

}
