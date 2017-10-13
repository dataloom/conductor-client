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

package com.dataloom.hazelcast.pods;

import com.dataloom.authorization.AceKey;
import com.dataloom.authorization.DelegatedPermissionEnumSet;
import com.dataloom.authorization.mapstores.PermissionMapstore;
import com.dataloom.authorization.mapstores.SecurableObjectTypeMapstore;
import com.dataloom.authorization.securable.SecurableObjectType;
import com.dataloom.blocking.GraphEntityPair;
import com.dataloom.blocking.LinkingEntity;
import com.dataloom.data.EntityKey;
import com.dataloom.data.hazelcast.DataKey;
import com.dataloom.data.mapstores.*;
import com.dataloom.edm.EntitySet;
import com.dataloom.edm.mapstores.AssociationTypeMapstore;
import com.dataloom.edm.mapstores.*;
import com.dataloom.edm.mapstores.EntitySetMapstore;
import com.dataloom.edm.mapstores.EntitySetPropertyMetadataMapstore;
import com.dataloom.edm.mapstores.EntityTypeMapstore;
import com.dataloom.edm.mapstores.NamesMapstore;
import com.dataloom.edm.set.EntitySetPropertyKey;
import com.dataloom.edm.set.EntitySetPropertyMetadata;
import com.dataloom.edm.type.*;
import com.dataloom.graph.edge.EdgeKey;
import com.dataloom.graph.edge.LoomEdge;
import com.dataloom.graph.mapstores.PostgresEdgeMapstore;
import com.dataloom.hazelcast.HazelcastMap;
import com.dataloom.linking.LinkingEntityKey;
import com.dataloom.linking.LinkingVertex;
import com.dataloom.linking.LinkingVertexKey;
import com.dataloom.linking.WeightedLinkingVertexKeySet;
import com.dataloom.linking.mapstores.*;
import com.dataloom.linking.mapstores.LinkedEntitySetsMapstore;
import com.dataloom.linking.mapstores.LinkingVerticesMapstore;
import com.dataloom.organization.roles.Role;
import com.dataloom.organization.roles.RoleKey;
import com.dataloom.organizations.PrincipalSet;
import com.dataloom.organizations.mapstores.StringMapstore;
import com.dataloom.organizations.mapstores.StringSetMapstore;
import com.dataloom.organizations.mapstores.UserSetMapstore;
import com.dataloom.organizations.roles.mapstores.RolesMapstore;
import com.dataloom.organizations.roles.mapstores.UsersWithRoleMapstore;
import com.dataloom.requests.Status;
import com.dataloom.requests.mapstores.RequestMapstore;
import com.datastax.driver.core.Session;
import com.kryptnostic.conductor.rpc.odata.Table;
import com.kryptnostic.datastore.cassandra.CommonColumns;
import com.kryptnostic.rhizome.configuration.cassandra.CassandraConfiguration;
import com.kryptnostic.rhizome.hazelcast.objects.DelegatedStringSet;
import com.kryptnostic.rhizome.hazelcast.objects.DelegatedUUIDSet;
import com.kryptnostic.rhizome.mapstores.SelfRegisteringMapStore;
import com.kryptnostic.rhizome.pods.CassandraPod;
import com.kryptnostic.rhizome.pods.hazelcast.QueueConfigurer;
import com.openlattice.postgres.PostgresPod;
import com.openlattice.postgres.PostgresTableManager;
import com.openlattice.postgres.mapstores.AclKeysMapstore;
import com.openlattice.postgres.mapstores.*;
import com.openlattice.postgres.mapstores.EnumTypesMapstore;
import com.openlattice.postgres.mapstores.VertexIdsAfterLinkingMapstore;
import com.zaxxer.hikari.HikariDataSource;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

import javax.inject.Inject;
import java.nio.ByteBuffer;
import java.sql.SQLException;
import java.util.List;
import java.util.UUID;

import static com.openlattice.postgres.PostgresTable.*;

@Configuration
@Import( { CassandraPod.class, PostgresPod.class } )
public class MapstoresPod {

    @Inject
    private Session                session;
    @Inject
    private CassandraConfiguration cc;
    @Inject
    private HikariDataSource       hikariDataSource;

    @Inject
    private PostgresTableManager ptMgr;
    //    @Bean
    //    public SelfRegisteringMapStore<UUID, Neighborhood> edgesMapstore() {
    //        return new EdgesMapstore( session );
    //    }

    //    @Bean
    //    public SelfRegisteringMapStore<UUID, Neighborhood> backedgesMapstore() {
    //        return new BackedgesMapstore( session );
    //    }

    @Bean
    public SelfRegisteringMapStore<EdgeKey, LoomEdge> edgesMapstore() throws SQLException {
        return new PostgresEdgeMapstore( HazelcastMap.EDGES.name(), hikariDataSource );
    }

    @Bean
    public SelfRegisteringMapStore<AceKey, DelegatedPermissionEnumSet> permissionMapstore() {
        return new PermissionMapstore( session );
    }

    @Bean
    public SelfRegisteringMapStore<List<UUID>, SecurableObjectType> securableObjectTypeMapstore() {
        return new SecurableObjectTypeMapstore( session );
    }

    @Bean
    public SelfRegisteringMapStore<UUID, PropertyType> propertyTypeMapstore() {
        //        PropertyTypeMapstore cptm = new PropertyTypeMapstore( session );
        com.openlattice.postgres.mapstores.PropertyTypeMapstore ptm = new com.openlattice.postgres.mapstores.PropertyTypeMapstore(
                HazelcastMap.PROPERTY_TYPES.name(),
                PROPERTY_TYPES,
                hikariDataSource );
        //        for ( UUID id : cptm.loadAllKeys() ) {
        //            ptm.store( id, cptm.load( id ) );
        //        }
        return ptm;
    }

    @Bean
    public SelfRegisteringMapStore<UUID, EntityType> entityTypeMapstore() {
        EntityTypeMapstore etm = new EntityTypeMapstore( session );
        com.openlattice.postgres.mapstores.EntityTypeMapstore petm = new com.openlattice.postgres.mapstores.EntityTypeMapstore(
                HazelcastMap.ENTITY_TYPES.name(),
                ENTITY_TYPES,
                hikariDataSource );
        for ( UUID id : etm.loadAllKeys() ) {
            petm.store( id, etm.load( id ) );
        }
        return petm;
    }

    @Bean
    public SelfRegisteringMapStore<UUID, ComplexType> complexTypeMapstore() {
        return new ComplexTypeMapstore( session );
    }

    @Bean
    public SelfRegisteringMapStore<UUID, EnumType> enumTypeMapstore() {
        return new EnumTypesMapstore( HazelcastMap.ENUM_TYPES.name(), ENUM_TYPES, hikariDataSource );
    }

    @Bean
    public SelfRegisteringMapStore<UUID, EntitySet> entitySetMapstore() {
        EntitySetMapstore esm = new EntitySetMapstore( session );
        com.openlattice.postgres.mapstores.EntitySetMapstore pesm = new com.openlattice.postgres.mapstores.EntitySetMapstore(
                HazelcastMap.ENTITY_SETS.name(),
                ENTITY_SETS,
                hikariDataSource );
        for ( UUID id : esm.loadAllKeys() ) {
            pesm.store( id, esm.load( id ) );
        }
        return pesm;
    }

    @Bean
    public SelfRegisteringMapStore<String, DelegatedStringSet> schemaMapstore() {
        return new SchemasMapstore( HazelcastMap.SCHEMAS.name(), SCHEMA, hikariDataSource );
    }

    @Bean
    public SelfRegisteringMapStore<String, UUID> aclKeysMapstore() {
        com.dataloom.edm.mapstores.AclKeysMapstore akm = new com.dataloom.edm.mapstores.AclKeysMapstore( session );
        AclKeysMapstore pakm = new AclKeysMapstore( HazelcastMap.ACL_KEYS.name(), ACL_KEYS, hikariDataSource );
        for ( String name : akm.loadAllKeys() ) {
            pakm.store( name, akm.load( name ) );
        }
        return pakm;
    }

    @Bean
    public SelfRegisteringMapStore<String, UUID> edmVersionMapstore() {
        return new EdmVersionMapstore( session );
    }

    @Bean
    public SelfRegisteringMapStore<UUID, String> namesMapstore() {
        com.openlattice.postgres.mapstores.NamesMapstore pnm = new com.openlattice.postgres.mapstores.NamesMapstore(
                HazelcastMap.NAMES.name(), NAMES, hikariDataSource );

        NamesMapstore nm = new NamesMapstore( session );
        for ( UUID key : nm.loadAllKeys() ) {
            pnm.store( key, nm.load( key ) );
        }
        return pnm;
    }

    @Bean
    public SelfRegisteringMapStore<AceKey, Status> requestMapstore() {
        return new RequestMapstore( session );
    }

    @Bean
    public SelfRegisteringMapStore<UUID, String> orgTitlesMapstore() {
        StringMapstore otm = new StringMapstore(
                HazelcastMap.ORGANIZATIONS_TITLES,
                session,
                Table.ORGANIZATIONS,
                CommonColumns.ID,
                CommonColumns.TITLE );
        OrganizationTitlesMapstore potm = new OrganizationTitlesMapstore(
                HazelcastMap.ORGANIZATIONS_TITLES.name(),
                ORGANIZATIONS,
                hikariDataSource );

        for ( UUID id : otm.loadAllKeys() ) {
            potm.store( id, otm.load( id ) );
        }
        return potm;
    }

    @Bean
    public SelfRegisteringMapStore<UUID, String> orgDescsMapstore() {
        StringMapstore odm = new StringMapstore(
                HazelcastMap.ORGANIZATIONS_DESCRIPTIONS,
                session,
                Table.ORGANIZATIONS,
                CommonColumns.ID,
                CommonColumns.DESCRIPTION );
        OrganizationDescriptionsMapstore podm = new OrganizationDescriptionsMapstore(
                HazelcastMap.ORGANIZATIONS_DESCRIPTIONS.name(),
                ORGANIZATIONS,
                hikariDataSource );
        for ( UUID id : odm.loadAllKeys() ) {
            podm.store( id, odm.load( id ) );
        }
        return podm;
    }

    @Bean
    public SelfRegisteringMapStore<UUID, DelegatedStringSet> aaEmailDomainsMapstore() {
        StringSetMapstore edm = new StringSetMapstore(
                HazelcastMap.ALLOWED_EMAIL_DOMAINS,
                session,
                Table.ORGANIZATIONS,
                CommonColumns.ID,
                CommonColumns.ALLOWED_EMAIL_DOMAINS );
        OrganizationEmailDomainsMapstore pedm = new OrganizationEmailDomainsMapstore(
                HazelcastMap.ALLOWED_EMAIL_DOMAINS.name(),
                ORGANIZATIONS,
                hikariDataSource );
        for ( UUID id : edm.loadAllKeys() ) {
            pedm.store( id, edm.load( id ) );
        }
        return pedm;
    }

    @Bean
    public SelfRegisteringMapStore<UUID, PrincipalSet> membersMapstore() {
        UserSetMapstore mm = new UserSetMapstore(
                HazelcastMap.ORGANIZATIONS_MEMBERS,
                session,
                Table.ORGANIZATIONS,
                CommonColumns.ID,
                CommonColumns.MEMBERS );
        OrganizationMembersMapstore pmm = new OrganizationMembersMapstore(
                HazelcastMap.ORGANIZATIONS_MEMBERS.name(),
                ORGANIZATIONS,
                hikariDataSource );
        for ( UUID id : mm.loadAllKeys() ) {
            pmm.store( id, mm.load( id ) );
        }
        return pmm;
    }

    @Bean
    public SelfRegisteringMapStore<UUID, DelegatedUUIDSet> linkedEntityTypesMapstore() {
        return new LinkedEntityTypesMapstore( session );
    }

    @Bean
    public SelfRegisteringMapStore<UUID, DelegatedUUIDSet> linkedEntitySetsMapstore() {
        LinkedEntitySetsMapstore lesm = new LinkedEntitySetsMapstore( session );
        com.openlattice.postgres.mapstores.LinkedEntitySetsMapstore plesm = new com.openlattice.postgres.mapstores.LinkedEntitySetsMapstore(
                hikariDataSource );
        for ( UUID id : lesm.loadAllKeys() ) {
            plesm.store( id, lesm.load( id ) );
        }
        return plesm;
    }

    @Bean
    public SelfRegisteringMapStore<LinkingVertexKey, WeightedLinkingVertexKeySet> linkingEdgesMapstore() {
        return new LinkingEdgesMapstore( session );
    }

    @Bean
    public SelfRegisteringMapStore<LinkingVertexKey, LinkingVertex> linkingVerticesMapstore() {
        LinkingVerticesMapstore lvm = new LinkingVerticesMapstore( session );
        com.openlattice.postgres.mapstores.LinkingVerticesMapstore plvm = new com.openlattice.postgres.mapstores.LinkingVerticesMapstore(
                HazelcastMap.LINKING_VERTICES.name(),
                LINKING_VERTICES,
                hikariDataSource );
        for ( LinkingVertexKey key : lvm.loadAllKeys() ) {
            plvm.store( key, lvm.load( key ) );
        }
        return plvm;
    }

    @Bean
    public SelfRegisteringMapStore<LinkingEntityKey, UUID> linkingEntityVerticesMapstore() {
        return new LinkingEntityVerticesMapstore( session );
    }

    @Bean
    public SelfRegisteringMapStore<UUID, AssociationType> edgeTypeMapstore() {
        AssociationTypeMapstore atm = new AssociationTypeMapstore( session );
        com.openlattice.postgres.mapstores.AssociationTypeMapstore patm = new com.openlattice.postgres.mapstores.AssociationTypeMapstore(
                HazelcastMap.ASSOCIATION_TYPES.name(),
                ASSOCIATION_TYPES,
                hikariDataSource );
        for ( UUID id : atm.loadAllKeys() ) {
            patm.store( id, atm.load( id ) );
        }
        return patm;
    }

    @Bean
    public SelfRegisteringMapStore<RoleKey, Role> roleidsMapstore() {
        RolesMapstore rm = new RolesMapstore( session );
        com.openlattice.postgres.mapstores.RolesMapstore prm = new com.openlattice.postgres.mapstores.RolesMapstore(
                HazelcastMap.ROLES.name(),
                ROLES,
                hikariDataSource );
        for ( RoleKey key : rm.loadAllKeys() ) {
            prm.store( key, rm.load( key ) );
        }
        return prm;
    }

    @Bean
    public SelfRegisteringMapStore<RoleKey, PrincipalSet> usersWithRolesMapstore() {
        UsersWithRoleMapstore uwrm = new UsersWithRoleMapstore( session );
        com.openlattice.postgres.mapstores.UsersWithRoleMapstore puwrm = new com.openlattice.postgres.mapstores.UsersWithRoleMapstore(
                HazelcastMap.USERS_WITH_ROLE.name(),
                ROLES,
                hikariDataSource );
        for ( RoleKey key : uwrm.loadAllKeys() ) {
            puwrm.store( key, uwrm.load( key ) );
        }
        return puwrm;
    }

    @Bean
    public SelfRegisteringMapStore<UUID, UUID> syncIdsMapstore() {
        return new SyncIdsMapstore( session );
    }

    //Still using Cassandra for mapstores below to avoid contention on data integrations
    @Bean
    public SelfRegisteringMapStore<EntityKey, UUID> idsMapstore() throws SQLException {
        //return new PostgresEntityKeyIdsMapstore( HazelcastMap.IDS.name(), hikariDataSource, keysMapstore() );
        return new EntityKeyIdsMapstore( keysMapstore(), HazelcastMap.IDS.name(), session, Table.IDS.getBuilder() );
    }

    @Bean
    public SelfRegisteringMapStore<UUID, EntityKey> keysMapstore() throws SQLException {
        //        return new PostgresEntityKeysMapstore( HazelcastMap.KEYS.name(), hikariDataSource );
        return new EntityKeysMapstore( HazelcastMap.KEYS.name(), session, Table.KEYS.getBuilder() );
    }

    @Bean
    public SelfRegisteringMapStore<LinkingVertexKey, UUID> vertexIdsAfterLinkingMapstore() {
        return new VertexIdsAfterLinkingMapstore(
                HazelcastMap.VERTEX_IDS_AFTER_LINKING.name(),
                VERTEX_IDS_AFTER_LINKING,
                hikariDataSource );
    }

    @Bean
    public SelfRegisteringMapStore<DataKey, ByteBuffer> dataMapstore() throws SQLException {
        return new PostgresDataMapstore( HazelcastMap.DATA.name(), session, hikariDataSource );
        //        ObjectMapper mapper = ObjectMappers.getJsonMapper();
        //        FullQualifedNameJacksonSerializer.registerWithMapper( mapper );
        //        FullQualifedNameJacksonDeserializer.registerWithMapper( mapper );
        //        return new DataMapstore( HazelcastMap.DATA.name(),
        //                Table.DATA.getBuilder(),
        //                session,
        //                propertyTypeMapstore(),
        //                mapper );
    }

    @Bean
    public SelfRegisteringMapStore<EntitySetPropertyKey, EntitySetPropertyMetadata> entitySetPropertyMetadataMapstore() {
        EntitySetPropertyMetadataMapstore espm = new EntitySetPropertyMetadataMapstore( session );
        com.openlattice.postgres.mapstores.EntitySetPropertyMetadataMapstore pespm = new com.openlattice.postgres.mapstores.EntitySetPropertyMetadataMapstore(
                HazelcastMap.ENTITY_SET_PROPERTY_METADATA.name(),
                ENTITY_SET_PROPERTY_METADATA,
                hikariDataSource );

        for ( EntitySetPropertyKey key : espm.loadAllKeys() ) {
            pespm.store( key, espm.load( key ) );
        }
        return pespm;
    }

    @Bean
    public SelfRegisteringMapStore<GraphEntityPair, LinkingEntity> linkingEntityMapstore() {
        return new LinkingEntityMapstore( session );
    }

    @Bean
    public QueueConfigurer defaultQueueConfigurer() {
        return config -> config.setMaxSize( 10000 ).setEmptyQueueTtl( 60 );
    }
}
