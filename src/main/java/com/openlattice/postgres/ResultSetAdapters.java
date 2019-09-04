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

package com.openlattice.postgres;

import com.dataloom.mappers.ObjectMappers;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.base.MoreObjects;
import com.google.common.collect.*;
import com.openlattice.IdConstants;
import com.openlattice.apps.App;
import com.openlattice.apps.AppConfigKey;
import com.openlattice.apps.AppType;
import com.openlattice.apps.AppTypeSetting;
import com.openlattice.assembler.EntitySetAssemblyKey;
import com.openlattice.assembler.MaterializedEntitySet;
import com.openlattice.auditing.AuditRecordEntitySetConfiguration;
import com.openlattice.authorization.*;
import com.openlattice.authorization.securable.SecurableObjectType;
import com.openlattice.data.*;
import com.openlattice.data.storage.ByteBlobDataManager;
import com.openlattice.data.storage.MetadataOption;
import com.openlattice.edm.EntitySet;
import com.openlattice.edm.set.EntitySetFlag;
import com.openlattice.edm.set.EntitySetPropertyKey;
import com.openlattice.edm.set.EntitySetPropertyMetadata;
import com.openlattice.edm.type.Analyzer;
import com.openlattice.edm.type.AssociationType;
import com.openlattice.edm.type.EntityType;
import com.openlattice.edm.type.PropertyType;
import com.openlattice.graph.NeighborhoodQuery;
import com.openlattice.graph.NeighborhoodSelection;
import com.openlattice.graph.edge.Edge;
import com.openlattice.graph.query.GraphQueryState;
import com.openlattice.graph.query.GraphQueryState.State;
import com.openlattice.ids.Range;
import com.openlattice.linking.EntityKeyPair;
import com.openlattice.linking.EntityLinkingFeedback;
import com.openlattice.notifications.sms.SmsEntitySetInformation;
import com.openlattice.notifications.sms.SmsInformationKey;
import com.openlattice.organization.OrganizationEntitySetFlag;
import com.openlattice.organization.roles.Role;
import com.openlattice.organizations.PrincipalSet;
import com.openlattice.requests.Request;
import com.openlattice.requests.RequestStatus;
import com.openlattice.requests.Status;
import com.openlattice.search.PersistentSearchNotificationType;
import com.openlattice.search.requests.PersistentSearch;
import com.openlattice.search.requests.SearchConstraints;
import com.openlattice.subscriptions.Subscription;
import com.openlattice.subscriptions.SubscriptionContactType;
import org.apache.olingo.commons.api.edm.EdmPrimitiveTypeKind;
import org.apache.olingo.commons.api.edm.FullQualifiedName;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.Nullable;
import java.io.IOException;
import java.sql.Date;
import java.sql.*;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.*;
import java.util.Base64.Decoder;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static com.openlattice.edm.EdmConstants.ID_FQN;
import static com.openlattice.edm.EdmConstants.LAST_INDEX_FQN;
import static com.openlattice.edm.EdmConstants.LAST_WRITE_FQN;
import static com.openlattice.postgres.DataTables.*;
import static com.openlattice.postgres.PostgresArrays.getTextArray;
import static com.openlattice.postgres.PostgresColumn.*;

/**
 * @author Matthew Tamayo-Rios &lt;matthew@openlattice.com&gt;
 */
public final class ResultSetAdapters {

    private static final Logger                             logger               = LoggerFactory
            .getLogger( ResultSetAdapters.class );
    private static final Decoder                            DECODER              = Base64.getMimeDecoder();
    private static final ObjectMapper                       mapper               = ObjectMappers.newJsonMapper();
    private static final TypeReference<Map<String, Object>> alertMetadataTypeRef = new TypeReference<Map<String, Object>>() {
    };

    @NotNull public static SmsInformationKey smsInformationKey(
            @NotNull ResultSet rs ) throws SQLException {
        final var phoneNumber = phoneNumber( rs );
        final var organizationId = organizationId( rs );
        return new SmsInformationKey( phoneNumber, organizationId );
    }

    @NotNull public static SmsEntitySetInformation smsEntitySetInformation( @NotNull ResultSet rs )
            throws SQLException {
        final var phoneNumber = phoneNumber( rs );
        final var organizationId = organizationId( rs );
        final var entitySetIds = entitySetIds( rs );
        final var tags = tags( rs );
        return new SmsEntitySetInformation( phoneNumber, organizationId, entitySetIds, tags );
    }

    @NotNull public static Set<UUID> entitySetIds( @NotNull ResultSet rs ) throws SQLException {
        final UUID[] entitySetIds = PostgresArrays.getUuidArray( rs, ENTITY_SET_IDS_FIELD );

        if ( entitySetIds == null ) {
            return new LinkedHashSet<>();
        }

        return new LinkedHashSet<>( Arrays.asList( entitySetIds ) );

    }

    @NotNull public static Set<String> tags( @NotNull ResultSet rs ) throws SQLException {
        return new LinkedHashSet<>( Arrays.asList( PostgresArrays.getTextArray( rs, TAGS_FIELD ) ) );
    }

    @NotNull public static String phoneNumber( @NotNull ResultSet rs ) throws SQLException {
        return rs.getString( PHONE_NUMBER_FIELD );
    }

    public static UUID clusterId( ResultSet rs ) throws SQLException {
        return (UUID) rs.getObject( LINKING_ID_FIELD );
    }

    public static GraphQueryState graphQueryState( ResultSet rs ) throws SQLException {
        final UUID queryId = (UUID) rs.getObject( QUERY_ID.getName() );
        final State state = State.valueOf( rs.getString( STATE.getName() ) );
        final long startTime = rs.getLong( START_TIME.getName() );
        return new GraphQueryState(
                queryId,
                state,
                Optional.empty(),
                System.currentTimeMillis() - startTime,
                Optional.empty() );
    }

    public static EntityDataKey entityDataKey( ResultSet rs ) throws SQLException {
        return new EntityDataKey( entitySetId( rs ), id( rs ) );
    }

    public static EntityDataKey srcEntityDataKey( ResultSet rs ) throws SQLException {
        final UUID srcEntitySetId = (UUID) rs.getObject( SRC_ENTITY_SET_ID_FIELD );
        final UUID srcEntityKeyId = (UUID) rs.getObject( SRC_ENTITY_KEY_ID_FIELD );
        return new EntityDataKey( srcEntitySetId, srcEntityKeyId );
    }

    public static EntityDataKey dstEntityDataKey( ResultSet rs ) throws SQLException {
        final UUID dstEntitySetId = (UUID) rs.getObject( DST_ENTITY_SET_ID_FIELD );
        final UUID dstEntityKeyId = (UUID) rs.getObject( DST_ENTITY_KEY_ID_FIELD );
        return new EntityDataKey( dstEntitySetId, dstEntityKeyId );
    }

    public static Double score( ResultSet rs ) throws SQLException {
        return rs.getDouble( SCORE_FIELD );
    }

    public static PropertyValueKey propertyValueKey( String propertyName, ResultSet rs ) throws SQLException {
        UUID entityKeyId = id( rs );
        Object value = propertyValue( propertyName, rs );
        return new PropertyValueKey( entityKeyId, value );
    }

    public static Object propertyValue( String propertyName, ResultSet rs ) throws SQLException {
        return rs.getObject( propertyName );
    }

    public static PropertyMetadata propertyMetadata( ResultSet rs ) throws SQLException {
        byte[] hash = rs.getBytes( PostgresColumn.HASH_FIELD );
        long version = rs.getLong( PostgresColumn.VERSION_FIELD );
        Long[] versions = PostgresArrays.getLongArray( rs, PostgresColumn.VERSIONS_FIELD );
        OffsetDateTime lastWrite = rs.getObject( PostgresColumn.LAST_WRITE_FIELD, OffsetDateTime.class );
        return new PropertyMetadata( hash, version, Arrays.asList( versions ), lastWrite );
    }

    public static EntityDataMetadata entityDataMetadata( ResultSet rs ) throws SQLException {
        long version = rs.getLong( PostgresColumn.VERSION_FIELD );
        OffsetDateTime lastWrite = rs.getObject( PostgresColumn.LAST_WRITE_FIELD, OffsetDateTime.class );
        OffsetDateTime lastIndex = rs.getObject( PostgresColumn.LAST_WRITE_FIELD, OffsetDateTime.class );
        return new EntityDataMetadata( version, lastWrite, lastIndex );
    }

    public static Set<UUID> linkingIds( ResultSet rs ) throws SQLException {
        return ImmutableSet.copyOf( PostgresArrays.getUuidArray( rs, LINKING_ID.getName() ) );
    }

    public static NeighborhoodSelection neighborhoodSelection( ResultSet rs, String colName )
            throws SQLException, IOException {
        String neighborhoodSelectionJson = rs.getString( colName );
        return mapper.readValue( neighborhoodSelectionJson, NeighborhoodSelection.class );
    }

    public static NeighborhoodSelection[] neighborhoodSelections( ResultSet rs, String colName )
            throws SQLException, IOException {
        String neighborhoodSelectionJson = rs.getString( colName );
        return mapper.readValue( neighborhoodSelectionJson, NeighborhoodSelection[].class );
    }

    public static Subscription subscriptionContact( ResultSet rs ) throws SQLException, IOException {
        return new Subscription( ResultSetAdapters.subscription( rs ),
                mapper.readValue(
                        rs.getString( CONTACT_INFO.getName() ),
                        new TypeReference<Map<SubscriptionContactType, String>>() {
                        }
                ),
                ResultSetAdapters.organizationId( rs ),
                rs.getObject( LAST_NOTIFIED_FIELD, OffsetDateTime.class )
        );
    }

    public static NeighborhoodQuery subscription( ResultSet rs ) throws SQLException, IOException {
        var id = id( rs );
        var entitySetId = entitySetId( rs );
        var dataKeys = Collections.singletonMap( entitySetId, Optional.of( Collections.singleton( id ) ) );
        List<NeighborhoodSelection> srcSelections = Arrays
                .asList( neighborhoodSelections( rs, SRC_SELECTS.getName() ) );
        List<NeighborhoodSelection> dstSelections = Arrays
                .asList( neighborhoodSelections( rs, DST_SELECTS.getName() ) );
        return new NeighborhoodQuery( dataKeys, srcSelections, dstSelections );
    }

    public static Edge edge( ResultSet rs ) throws SQLException {
        DataEdgeKey key = edgeKey( rs );
        long version = rs.getLong( VERSION.getName() );
        List<Long> versions = Arrays.asList( (Long[]) rs.getArray( VERSIONS.getName() ).getArray() );

        return new Edge( key, version, versions );
    }

    public static DataEdgeKey edgeKey( ResultSet rs ) throws SQLException {
        final UUID srcEntityKeyId = rs.getObject( SRC_ENTITY_KEY_ID.getName(), UUID.class );
        final UUID srcEntitySetId = rs.getObject( SRC_ENTITY_SET_ID.getName(), UUID.class );
        final UUID dstEntityKeyId = rs.getObject( DST_ENTITY_KEY_ID.getName(), UUID.class );
        final UUID dstEntitySetId = rs.getObject( DST_ENTITY_SET_ID.getName(), UUID.class );
        final UUID edgeEntityKeyId = rs.getObject( EDGE_ENTITY_KEY_ID.getName(), UUID.class );
        final UUID edgeEntitySetId = rs.getObject( EDGE_ENTITY_SET_ID.getName(), UUID.class );

        return new DataEdgeKey( new EntityDataKey( srcEntitySetId, srcEntityKeyId ),
                new EntityDataKey( dstEntitySetId, dstEntityKeyId ),
                new EntityDataKey( edgeEntitySetId, edgeEntityKeyId ) );
    }

    public static EntityKey entityKey( ResultSet rs ) throws SQLException {
        UUID entitySetId = (UUID) rs.getObject( ENTITY_SET_ID_FIELD );
        String entityId = rs.getString( ENTITY_ID_FIELD );
        return new EntityKey( entitySetId, entityId );
    }

    public static Range range( ResultSet rs ) throws SQLException {
        long base = rs.getLong( PARTITION_INDEX_FIELD ) << 48L;
        long msb = rs.getLong( MSB_FIELD );
        long lsb = rs.getLong( LSB_FIELD );
        return new Range( base, msb, lsb );
    }

    public static AclKey principalOfAclKey( ResultSet rs ) throws SQLException {
        final UUID[] arr;
        try {
            arr = PostgresArrays.getUuidArray( rs, PRINCIPAL_OF_ACL_KEY.getName() );
        } catch ( ClassCastException e ) {
            logger.error( "Unable to read principal of acl key of acl key: {}", aclKey( rs ) );
            throw new IllegalStateException( "Unable to read principal of acl key", e );
        }
        return new AclKey( arr );
    }

    public static SecurablePrincipal securablePrincipal( ResultSet rs ) throws SQLException {
        Principal principal = ResultSetAdapters.principal( rs );
        AclKey aclKey = aclKey( rs );
        String title = title( rs );
        String description = description( rs );
        switch ( principal.getType() ) {
            case ROLE:
                UUID id = aclKey.get( 1 );
                UUID organizationId = aclKey.get( 0 );
                return new Role( Optional.of( id ), organizationId, principal, title, Optional.of( description ) );
            default:
                return new SecurablePrincipal( aclKey, principal, title, Optional.of( description ) );
        }
    }

    public static EnumSet<Permission> permissions( ResultSet rs ) throws SQLException {
        String[] pStrArray = getTextArray( rs, PERMISSIONS_FIELD );

        if ( pStrArray.length == 0 ) {
            return EnumSet.noneOf( Permission.class );
        }

        EnumSet<Permission> permissions = EnumSet.noneOf( Permission.class );

        for ( String s : pStrArray ) {
            permissions.add( Permission.valueOf( s ) );
        }

        return permissions;
    }

    public static EnumSet<EntitySetFlag> entitySetFlags( ResultSet rs ) throws SQLException {
        String[] pStrArray = getTextArray( rs, ENTITY_SET_FLAGS_FIELD );

        EnumSet<EntitySetFlag> flags = EnumSet.noneOf( EntitySetFlag.class );

        if ( pStrArray != null && pStrArray.length > 0 ) {

            for ( String s : pStrArray ) {
                flags.add( EntitySetFlag.valueOf( s ) );
            }

        }

        return flags;
    }

    public static EnumSet<OrganizationEntitySetFlag> organizationEntitySetFlags( ResultSet rs ) throws SQLException {
        String[] pStrArray = getTextArray( rs, ENTITY_SET_FLAGS_FIELD );

        EnumSet<OrganizationEntitySetFlag> flags = EnumSet.noneOf( OrganizationEntitySetFlag.class );

        if ( pStrArray != null && pStrArray.length > 0 ) {

            for ( String s : pStrArray ) {
                flags.add( OrganizationEntitySetFlag.valueOf( s ) );
            }

        }

        return flags;
    }

    public static UUID idValue( ResultSet rs ) throws SQLException {
        return rs.getObject( ID_VALUE.getName(), UUID.class );
    }

    public static UUID id( ResultSet rs ) throws SQLException {
        return rs.getObject( ID.getName(), UUID.class );
    }

    public static String namespace( ResultSet rs ) throws SQLException {
        return rs.getString( NAMESPACE.getName() );
    }

    public static String name( ResultSet rs ) throws SQLException {
        return rs.getString( NAME.getName() );
    }

    public static FullQualifiedName fqn( ResultSet rs ) throws SQLException {
        String namespace = namespace( rs );
        String name = name( rs );
        return new FullQualifiedName( namespace, name );
    }

    public static String title( ResultSet rs ) throws SQLException {
        return rs.getString( TITLE.getName() );
    }

    public static String description( ResultSet rs ) throws SQLException {
        return rs.getString( DESCRIPTION.getName() );
    }

    public static Set<FullQualifiedName> schemas( ResultSet rs ) throws SQLException {
        return Arrays.stream( (String[]) rs.getArray( SCHEMAS.getName() ).getArray() ).map( FullQualifiedName::new )
                .collect( Collectors.toSet() );
    }

    public static EdmPrimitiveTypeKind datatype( ResultSet rs ) throws SQLException {
        return EdmPrimitiveTypeKind.valueOf( rs.getString( DATATYPE.getName() ) );
    }

    public static boolean pii( ResultSet rs ) throws SQLException {
        return rs.getBoolean( PII.getName() );
    }

    public static Analyzer analyzer( ResultSet rs ) throws SQLException {
        return Analyzer.valueOf( rs.getString( ANALYZER.getName() ) );
    }

    public static String url( ResultSet rs ) throws SQLException {
        return rs.getString( URL.getName() );
    }

    public static Principal principal( ResultSet rs ) throws SQLException {
        PrincipalType principalType = PrincipalType.valueOf( rs.getString( PRINCIPAL_TYPE_FIELD ) );
        String principalId = rs.getString( PRINCIPAL_ID_FIELD );
        return new Principal( principalType, principalId );
    }

    public static AclKey aclKey( ResultSet rs ) throws SQLException {
        return new AclKey( PostgresArrays.getUuidArray( rs, ACL_KEY_FIELD ) );
    }

    public static AceKey aceKey( ResultSet rs ) throws SQLException {
        AclKey aclKey = aclKey( rs );
        Principal principal = principal( rs );
        return new AceKey( aclKey, principal );
    }

    public static <R> LinkedHashSet<R> linkedHashSetOfType(
            ResultSet rs,
            String colName,
            Function<Object, R[]> arrayCastFunction ) throws SQLException {
        return Arrays.stream( arrayCastFunction.apply( rs.getArray( colName ).getArray() ) )
                .collect( Collectors.toCollection( LinkedHashSet::new ) );
    }

    public static LinkedHashSet<UUID> linkedHashSetUUID( ResultSet rs, String colName ) throws SQLException {
        return Arrays.stream( (UUID[]) rs.getArray( colName ).getArray() )
                .collect( Collectors.toCollection( LinkedHashSet::new ) );
    }

    public static LinkedHashSet<UUID> key( ResultSet rs ) throws SQLException {
        return linkedHashSetUUID( rs, KEY.getName() );
    }

    public static LinkedHashSet<UUID> properties( ResultSet rs ) throws SQLException {
        return linkedHashSetUUID( rs, PROPERTIES.getName() );
    }

    public static UUID baseType( ResultSet rs ) throws SQLException {
        return rs.getObject( BASE_TYPE.getName(), UUID.class );
    }

    public static SecurableObjectType category( ResultSet rs ) throws SQLException {
        return SecurableObjectType.valueOf( rs.getString( CATEGORY.getName() ) );
    }

    public static int shards( ResultSet rs ) throws SQLException {
        return rs.getInt( SHARDS.getName() );
    }

    public static UUID entityTypeId( ResultSet rs ) throws SQLException {
        return rs.getObject( ENTITY_TYPE_ID.getName(), UUID.class );
    }

    public static Set<String> contacts( ResultSet rs ) throws SQLException {
        return Sets.newHashSet( (String[]) rs.getArray( CONTACTS.getName() ).getArray() );
    }

    public static LinkedHashSet<UUID> src( ResultSet rs ) throws SQLException {
        return linkedHashSetUUID( rs, SRC.getName() );
    }

    public static LinkedHashSet<UUID> dst( ResultSet rs ) throws SQLException {
        return linkedHashSetUUID( rs, DST.getName() );
    }

    public static boolean bidirectional( ResultSet rs ) throws SQLException {
        return rs.getBoolean( BIDIRECTIONAL.getName() );
    }

    public static boolean show( ResultSet rs ) throws SQLException {
        return rs.getBoolean( SHOW.getName() );
    }

    public static UUID entitySetId( ResultSet rs ) throws SQLException {
        return rs.getObject( ENTITY_SET_ID.getName(), UUID.class );
    }

    public static UUID propertyTypeId( ResultSet rs ) throws SQLException {
        return rs.getObject( PROPERTY_TYPE_ID.getName(), UUID.class );
    }

    public static LinkedHashSet<String> members( ResultSet rs ) throws SQLException {
        return Arrays.stream( (String[]) rs.getArray( MEMBERS.getName() ).getArray() )
                .collect( Collectors
                        .toCollection( LinkedHashSet::new ) );
    }

    public static boolean flags( ResultSet rs ) throws SQLException {
        return rs.getBoolean( FLAGS.getName() );
    }

    public static UUID appId( ResultSet rs ) throws SQLException {
        return rs.getObject( APP_ID.getName(), UUID.class );
    }

    public static UUID appTypeId( ResultSet rs ) throws SQLException {
        return rs.getObject( CONFIG_TYPE_ID.getName(), UUID.class );
    }

    public static LinkedHashSet<UUID> appTypeIds( ResultSet rs ) throws SQLException {
        return linkedHashSetUUID( rs, CONFIG_TYPE_IDS.getName() );
    }

    public static double diameter( ResultSet rs ) throws SQLException {
        return rs.getDouble( GRAPH_DIAMETER.getName() );
    }

    public static Set<UUID> entityKeyIds( ResultSet rs ) throws SQLException {
        return Sets.newHashSet( (UUID[]) rs.getArray( ENTITY_KEY_IDS_COL.getName() ).getArray() );
    }

    public static UUID graphId( ResultSet rs ) throws SQLException {
        return rs.getObject( GRAPH_ID.getName(), UUID.class );
    }

    public static UUID vertexId( ResultSet rs ) throws SQLException {
        return rs.getObject( VERTEX_ID.getName(), UUID.class );
    }

    public static UUID securableObjectId( ResultSet rs ) throws SQLException {
        return rs.getObject( SECURABLE_OBJECTID.getName(), UUID.class );
    }

    public static UUID roleId( ResultSet rs ) throws SQLException {
        return rs.getObject( ROLE_ID.getName(), UUID.class );
    }

    public static UUID organizationId( ResultSet rs ) throws SQLException {
        return rs.getObject( ORGANIZATION_ID.getName(), UUID.class );
    }

    public static String nullableTitle( ResultSet rs ) throws SQLException {
        return rs.getString( NULLABLE_TITLE.getName() );
    }

    public static UUID currentSyncId( ResultSet rs ) throws SQLException {
        return rs.getObject( CURRENT_SYNC_ID.getName(), UUID.class );
    }

    public static UUID syncId( ResultSet rs ) throws SQLException {
        return rs.getObject( SYNC_ID.getName(), UUID.class );
    }

    public static SecurableObjectType securableObjectType( ResultSet rs ) throws SQLException {
        return SecurableObjectType.valueOf( rs.getString( SECURABLE_OBJECT_TYPE.getName() ) );
    }

    public static String reason( ResultSet rs ) throws SQLException {
        return rs.getString( REASON.getName() );
    }

    public static RequestStatus requestStatus( ResultSet rs ) throws SQLException {
        return RequestStatus.valueOf( rs.getString( STATUS.getName() ) );
    }

    public static PropertyType propertyType( ResultSet rs ) throws SQLException {
        UUID id = id( rs );
        FullQualifiedName fqn = fqn( rs );
        EdmPrimitiveTypeKind datatype = datatype( rs );
        String title = title( rs );
        Optional<String> description = Optional.ofNullable( description( rs ) );
        Optional<Set<String>> enumValues = Optional.ofNullable( enumValues( rs ) );
        Set<FullQualifiedName> schemas = schemas( rs );
        Optional<Boolean> pii = Optional.ofNullable( pii( rs ) );
        Optional<Boolean> multiValued = Optional.ofNullable( multiValued( rs ) );
        Optional<Analyzer> analyzer = Optional.ofNullable( analyzer( rs ) );
        Optional<IndexType> indexMethod = Optional.ofNullable( indexType( rs ) );

        return new PropertyType( Optional.of( id ),
                fqn,
                title,
                description,
                schemas,
                datatype,
                enumValues,
                pii,
                multiValued,
                analyzer,
                indexMethod );
    }

    public static Set<String> enumValues( ResultSet rs ) throws SQLException {
        final var values = PostgresArrays.getTextArray( rs, ENUM_VALUES_FIELD );
        if ( values == null || values.length == 0 ) {
            return null;
        } else {
            return ImmutableSet.copyOf( values );
        }
    }

    public static EntityType entityType( ResultSet rs ) throws SQLException {
        UUID id = id( rs );
        FullQualifiedName fqn = fqn( rs );
        String title = title( rs );
        Optional<String> description = Optional.ofNullable( description( rs ) );
        Set<FullQualifiedName> schemas = schemas( rs );
        LinkedHashSet<UUID> key = key( rs );
        LinkedHashSet<UUID> properties = properties( rs );
        LinkedHashMultimap<UUID, String> propertyTags;
        try {
            propertyTags = mapper.readValue( rs.getString( PROPERTY_TAGS_FIELD ),
                    new TypeReference<LinkedHashMultimap<UUID, String>>() {
                    } );
        } catch ( IOException e ) {
            String errMsg =
                    "Unable to deserialize json from entity type " + fqn.getFullQualifiedNameAsString() + " with id "
                            + id.toString();
            logger.error( errMsg );
            throw new SQLException( errMsg );
        }
        Optional<UUID> baseType = Optional.ofNullable( baseType( rs ) );
        Optional<SecurableObjectType> category = Optional.of( category( rs ) );
        Optional<Integer> shards = Optional.of( shards( rs ) );

        return new EntityType( id, fqn, title, description, schemas, key, properties,
                propertyTags, baseType, category, shards );
    }

    public static EntitySet entitySet( ResultSet rs ) throws SQLException {
        final var id = id( rs );
        final var name = name( rs );
        final var entityTypeId = entityTypeId( rs );
        final var title = title( rs );
        final var description = MoreObjects.firstNonNull( description( rs ), "" );
        final var contacts = contacts( rs );
        final var linkedEntitySets = linkedEntitySets( rs );
        final var organization = rs.getObject( ORGANIZATION_ID_FIELD, UUID.class );
        final var flags = entitySetFlags( rs );
        final var partitions = partitions( rs );
        final var partitionVersion = partitionVersions( rs );
        return new EntitySet( id,
                entityTypeId,
                name,
                title,
                description,
                contacts,
                linkedEntitySets,
                organization,
                flags,
                new LinkedHashSet<>( Arrays.asList( partitions ) ),
                partitionVersion );
    }

    public static int partitionVersions( ResultSet rs ) throws SQLException {
        return rs.getInt( PARTITIONS_VERSION_FIELD );
    }

    public static Integer[] partitions( ResultSet rs ) throws SQLException {
        return PostgresArrays.getIntArray( rs, PARTITIONS_FIELD );
    }

    public static AssociationType associationType( ResultSet rs ) throws SQLException {
        LinkedHashSet<UUID> src = src( rs );
        LinkedHashSet<UUID> dst = dst( rs );
        boolean bidirectional = bidirectional( rs );

        return new AssociationType( Optional.empty(), src, dst, bidirectional );
    }

    public static EntitySetPropertyMetadata entitySetPropertyMetadata( ResultSet rs ) throws SQLException {
        String title = title( rs );
        String description = description( rs );
        boolean show = show( rs );
        LinkedHashSet<String> tags = new LinkedHashSet<>( Arrays
                .asList( PostgresArrays.getTextArray( rs, PostgresColumn.TAGS_FIELD ) ) );

        return new EntitySetPropertyMetadata( title, description, tags, show );
    }

    public static EntitySetPropertyKey entitySetPropertyKey( ResultSet rs ) throws SQLException {
        UUID entitySetId = entitySetId( rs );
        UUID propertyTypeId = propertyTypeId( rs );
        return new EntitySetPropertyKey( entitySetId, propertyTypeId );
    }

    public static Boolean multiValued( ResultSet rs ) throws SQLException {
        return rs.getBoolean( MULTI_VALUED.getName() );
    }

    public static IndexType indexType( ResultSet rs ) throws SQLException {
        return IndexType.valueOf( rs.getString( INDEX_TYPE.getName() ) );
    }

    public static Status status( ResultSet rs ) throws SQLException {
        AclKey aclKey = aclKey( rs );
        Principal principal = principal( rs );
        EnumSet<Permission> permissions = ResultSetAdapters.permissions( rs );
        Optional<String> reason = Optional.of( reason( rs ) );
        Request request = new Request( aclKey, permissions, reason );
        RequestStatus status = requestStatus( rs );
        return new Status( request, principal, status );
    }

    //    public static Role role( ResultSet rs ) throws SQLException {
    //        UUID roleId = roleId( rs );
    //        UUID orgId = organizationId( rs );
    //        String title = nullableTitle( rs );
    //        String description = description( rs );
    //        return new Role( Optional.of( roleId ), orgId, title, Optional.ofNullable( description ) );
    //    }

    public static PrincipalSet principalSet( ResultSet rs ) throws SQLException {
        Array usersArray = rs.getArray( PRINCIPAL_IDS.getName() );
        if ( usersArray == null ) {
            return PrincipalSet.wrap( ImmutableSet.of() );
        }
        Stream<String> users = Arrays.stream( (String[]) usersArray.getArray() );
        return PrincipalSet
                .wrap( users.map( user -> new Principal( PrincipalType.USER, user ) ).collect( Collectors.toSet() ) );
    }

    public static AppConfigKey appConfigKey( ResultSet rs ) throws SQLException {
        UUID appId = appId( rs );
        UUID organizationId = organizationId( rs );
        UUID appTypeId = appTypeId( rs );
        return new AppConfigKey( appId, organizationId, appTypeId );
    }

    public static AppTypeSetting appTypeSetting( ResultSet rs ) throws SQLException {
        UUID entitySetId = entitySetId( rs );
        EnumSet<Permission> permissions = permissions( rs );
        return new AppTypeSetting( entitySetId, permissions );
    }

    public static App app( ResultSet rs ) throws SQLException {
        UUID id = id( rs );
        String name = name( rs );
        String title = title( rs );
        Optional<String> description = Optional.ofNullable( description( rs ) );
        LinkedHashSet<UUID> appTypeIds = appTypeIds( rs );
        String url = url( rs );
        return new App( id, name, title, description, appTypeIds, url );
    }

    public static AppType appType( ResultSet rs ) throws SQLException {
        UUID id = id( rs );
        FullQualifiedName type = fqn( rs );
        String title = title( rs );
        Optional<String> description = Optional.ofNullable( description( rs ) );
        UUID entityTypeId = entityTypeId( rs );
        return new AppType( id, type, title, description, entityTypeId );
    }

    private static List<?> propertyValue( ResultSet rs, PropertyType propertyType ) throws SQLException {
        final String fqn = propertyType.getType().getFullQualifiedNameAsString();
        List<?> objects = null;
        Array arr = rs.getArray( fqn );
        if ( arr != null ) {
            switch ( propertyType.getDatatype() ) {
                case String:
                case GeographyPoint:
                    objects = Arrays.asList( (String[]) arr.getArray() );
                    break;
                case Guid:
                    objects = Arrays.asList( (UUID[]) arr.getArray() );
                    break;
                case Byte:
                    byte[] bytes = rs.getBytes( fqn );
                    if ( bytes != null && bytes.length > 0 ) {
                        objects = Arrays.asList( rs.getBytes( fqn ) );
                    }
                    break;
                case Int16:
                    objects = Arrays.asList( (Short[]) arr.getArray() );
                    break;
                case Int32:
                    objects = Arrays.asList( (Integer[]) arr.getArray() );
                    break;
                case Duration:
                case Int64:
                    objects = Arrays.asList( (Long[]) arr.getArray() );
                    break;
                case Date:
                    objects = Stream
                            .of( (Date[]) arr.getArray() )
                            .map( Date::toLocalDate )
                            .collect( Collectors.toList() );
                    break;
                case TimeOfDay:
                    objects = Stream
                            .of( (Time[]) arr.getArray() )
                            .map( Time::toLocalTime )
                            .collect( Collectors.toList() );
                    break;
                case DateTimeOffset:
                    objects = Stream
                            .of( (Timestamp[]) arr.getArray() )
                            .map( ts -> OffsetDateTime
                                    .ofInstant( Instant.ofEpochMilli( ts.getTime() ), ZoneId.of( "UTC" ) ) )
                            .collect( Collectors.toList() );
                    break;
                case Double:
                    objects = Arrays.asList( (Double[]) arr.getArray() );
                    break;
                case Boolean:
                    objects = Arrays.asList( (Boolean[]) arr.getArray() );
                    break;
                case Binary:
                    objects = Arrays.asList( (String[]) arr.getArray() );
                    break;
                default:
                    objects = null;
                    logger.error( "Unable to read property type {}.",
                            propertyType.getId() );
            }
        }

        return objects;
    }

    public static Map<UUID, Set<Object>> implicitEntityValuesById(
            ResultSet rs,
            Map<UUID, Map<UUID, PropertyType>> authorizedPropertyTypes,
            ByteBlobDataManager byteBlobDataManager ) throws SQLException {
        final Map<UUID, Set<Object>> data = new HashMap<>();

        final Set<PropertyType> allPropertyTypes = authorizedPropertyTypes.values().stream()
                .flatMap( propertyTypesOfEntitySet -> propertyTypesOfEntitySet.values().stream() )
                .collect( Collectors.toSet() );
        for ( PropertyType propertyType : allPropertyTypes ) {
            List<?> objects = propertyValue( rs, propertyType );

            if ( objects != null ) {
                if ( propertyType.getDatatype() == EdmPrimitiveTypeKind.Binary ) {
                    data.put( propertyType.getId(), new HashSet<>( byteBlobDataManager.getObjects( objects ) ) );
                } else {
                    data.put( propertyType.getId(), new HashSet<>( objects ) );
                }
            }
        }
        return data;
    }

    public static Map<UUID, Set<Object>> implicitEntityValuesByIdWithLastWrite(
            ResultSet rs,
            Map<UUID, PropertyType> authorizedPropertyTypes,
            ByteBlobDataManager byteBlobDataManager ) throws SQLException {
        final Map<UUID, Set<Object>> data = new HashMap<>();

        for ( PropertyType propertyType : authorizedPropertyTypes.values() ) {
            List<?> objects = propertyValue( rs, propertyType );

            if ( objects != null ) {
                if ( propertyType.getDatatype() == EdmPrimitiveTypeKind.Binary ) {
                    data.put( propertyType.getId(), new HashSet<>( byteBlobDataManager.getObjects( objects ) ) );
                } else {
                    data.put( propertyType.getId(), new HashSet<>( objects ) );
                }
            }
        }

        data.put( IdConstants.LAST_WRITE_ID.getId(), ImmutableSet.of( lastWrite( rs ) ) );
        return data;
    }

    public static Map<FullQualifiedName, Set<Object>> implicitEntityValuesByFqn(
            ResultSet rs,
            Map<UUID, PropertyType> authorizedPropertyTypes,
            ByteBlobDataManager byteBlobDataManager ) throws SQLException {
        final Map<FullQualifiedName, Set<Object>> data = new HashMap<>();

        for ( PropertyType propertyType : authorizedPropertyTypes.values() ) {
            List<?> objects = propertyValue( rs, propertyType );

            if ( objects != null ) {
                if ( propertyType.getDatatype() == EdmPrimitiveTypeKind.Binary ) {
                    data.put( propertyType.getType(), new HashSet<>( byteBlobDataManager.getObjects( objects ) ) );
                } else {
                    data.put( propertyType.getType(), new HashSet<>( objects ) );
                }
            }
        }

        return data;
    }

    public static SetMultimap<FullQualifiedName, Object> implicitNormalEntity(
            ResultSet rs,
            Map<UUID, Map<UUID, PropertyType>> authorizedPropertyTypes,
            Set<MetadataOption> metadataOptions,
            ByteBlobDataManager byteBlobDataManager ) throws SQLException {
        return implicitEntity( rs, authorizedPropertyTypes, metadataOptions, byteBlobDataManager, false );
    }

    public static SetMultimap<FullQualifiedName, Object> implicitLinkedEntity(
            ResultSet rs,
            Map<UUID, Map<UUID, PropertyType>> authorizedPropertyTypes,
            Set<MetadataOption> metadataOptions,
            ByteBlobDataManager byteBlobDataManager ) throws SQLException {
        return implicitEntity( rs, authorizedPropertyTypes, metadataOptions, byteBlobDataManager, true );
    }

    private static SetMultimap<FullQualifiedName, Object> implicitEntity(
            ResultSet rs,
            Map<UUID, Map<UUID, PropertyType>> authorizedPropertyTypes,
            Set<MetadataOption> metadataOptions,
            ByteBlobDataManager byteBlobDataManager,
            Boolean linking ) throws SQLException {
        final SetMultimap<FullQualifiedName, Object> data = HashMultimap.create();
        final Collection<PropertyType> allPropertyTypes;

        if ( linking ) {
            final UUID entityKeyId = linkingId( rs );
            data.put( ID_FQN, entityKeyId );

            allPropertyTypes = authorizedPropertyTypes.values().stream()
                    .flatMap( propertyTypesOfEntitySet -> propertyTypesOfEntitySet.values().stream() )
                    .collect( Collectors.toSet() );
        } else {
            final UUID entityKeyId = id( rs );
            data.put( ID_FQN, entityKeyId );

            UUID entitySetId = entitySetId( rs );
            allPropertyTypes = authorizedPropertyTypes.get( entitySetId ).values();
        }

        if ( metadataOptions.contains( MetadataOption.LAST_WRITE ) ) {
            data.put( LAST_WRITE_FQN, lastWrite( rs ) );
        }

        if ( metadataOptions.contains( MetadataOption.LAST_INDEX ) ) {
            data.put( LAST_INDEX_FQN, lastIndex( rs ) );
        }

        for ( PropertyType propertyType : allPropertyTypes ) {
            List<?> objects = propertyValue( rs, propertyType );

            if ( objects != null ) {
                if ( propertyType.getDatatype() == EdmPrimitiveTypeKind.Binary ) {
                    data.putAll( propertyType.getType(), byteBlobDataManager.getObjects( objects ) );
                } else {
                    data.putAll( propertyType.getType(), objects );
                }
            }
        }

        return data;
    }

    public static UUID linkingId( ResultSet rs ) throws SQLException {
        return (UUID) rs.getObject( LINKING_ID.getName() );
    }

    public static OffsetDateTime lastWriteTyped( ResultSet rs ) throws SQLException {
        return rs.getObject( LAST_WRITE.getName(), OffsetDateTime.class );
    }

    public static Object lastWrite( ResultSet rs ) throws SQLException {
        return rs.getObject( LAST_WRITE.getName() );
    }

    public static Object lastIndex( ResultSet rs ) throws SQLException {
        return rs.getObject( LAST_INDEX.getName() );
    }

    public static Boolean linking( ResultSet rs ) throws SQLException {
        return (Boolean) rs.getObject( LINKING.getName() );
    }

    public static LinkedHashSet<UUID> linkedEntitySets( ResultSet rs ) throws SQLException {
        return linkedHashSetUUID( rs, LINKED_ENTITY_SETS.getName() );
    }

    public static Boolean external( ResultSet rs ) throws SQLException {
        return (Boolean) rs.getObject( EXTERNAL.getName() );
    }

    public static PropertyUsageSummary propertyUsageSummary( ResultSet rs ) throws SQLException {
        UUID entityTypeID = (UUID) rs.getObject( ENTITY_TYPE_ID_FIELD );
        String entitySetName = rs.getString( ENTITY_SET_NAME_FIELD );
        UUID entitySetId = (UUID) rs.getObject( ENTITY_SET_ID_FIELD );
        long count = count( rs );
        return new PropertyUsageSummary( entityTypeID, entitySetName, entitySetId, count );
    }

    public static Long count( ResultSet rs ) throws SQLException {
        return rs.getLong( COUNT );
    }

    public static OffsetDateTime expirationDate( ResultSet rs ) throws SQLException {
        return rs.getObject( EXPIRATION_DATE_FIELD, OffsetDateTime.class );
    }

    public static OffsetDateTime lastRead( ResultSet rs ) throws SQLException {
        return rs.getObject( LAST_READ_FIELD, OffsetDateTime.class );
    }

    public static PersistentSearchNotificationType alertType( ResultSet rs ) throws SQLException {
        return PersistentSearchNotificationType.valueOf( rs.getString( ALERT_TYPE_FIELD ) );
    }

    public static SearchConstraints searchConstraints( ResultSet rs ) throws SQLException, IOException {
        String searchConstraintsJson = rs.getString( SEARCH_CONSTRAINTS_FIELD );
        return mapper.readValue( searchConstraintsJson, SearchConstraints.class );
    }

    public static Map<String, Object> alertMetadata( ResultSet rs ) throws SQLException, IOException {
        return mapper.readValue( rs.getString( ALERT_METADATA_FIELD ), alertMetadataTypeRef );
    }

    public static PersistentSearch persistentSearch( ResultSet rs ) throws SQLException, IOException {
        UUID id = id( rs );
        OffsetDateTime lastRead = lastRead( rs );
        OffsetDateTime expiration = expirationDate( rs );
        PersistentSearchNotificationType alertType = alertType( rs );
        SearchConstraints searchConstraints = searchConstraints( rs );
        Map<String, Object> alertMetadata = alertMetadata( rs );

        return new PersistentSearch( id, lastRead, expiration, alertType, searchConstraints, alertMetadata );
    }

    public static EntityLinkingFeedback entityLinkingFeedback( ResultSet rs ) throws SQLException {
        boolean linked = isLinked( rs );

        return new EntityLinkingFeedback( entityKeyPair( rs ), linked );
    }

    public static Boolean isLinked( ResultSet rs ) throws SQLException {
        return rs.getBoolean( LINKED_FIELD );
    }

    public static EntityKeyPair entityKeyPair( ResultSet rs ) throws SQLException {
        EntityDataKey srcEntityDataKey = srcEntityDataKey( rs );
        EntityDataKey dstEntityDataKey = dstEntityDataKey( rs );

        return new EntityKeyPair( srcEntityDataKey, dstEntityDataKey );
    }

    public static String mapMetadataOptionToPostgresColumn( MetadataOption metadataOption ) {
        switch ( metadataOption ) {
            case LAST_WRITE:
                return LAST_WRITE.getName();
            case LAST_INDEX:
                return LAST_INDEX.getName();
            case LAST_LINK:
                return LAST_LINK.getName();
            case VERSION:
                return VERSION.getName();
            case ENTITY_SET_IDS:
                return "entity_set_ids";
            case ENTITY_KEY_IDS:
                return "entity_key_ids";
            default:
                return null;
        }
    }

    public static @Nullable UUID auditRecordEntitySetId( ResultSet rs ) throws SQLException {
        return rs.getObject( AUDIT_RECORD_ENTITY_SET_ID_FIELD, UUID.class );
    }

    public static AuditRecordEntitySetConfiguration auditRecordEntitySetConfiguration( ResultSet rs )
            throws SQLException {
        return new AuditRecordEntitySetConfiguration( auditRecordEntitySetId( rs ),
                auditEdgeEntitySetId( rs ),
                Lists.newArrayList( readNullableUuidArray( PostgresArrays
                        .getUuidArray( rs, AUDIT_RECORD_ENTITY_SET_IDS_FIELD ) ) ),
                Lists.newArrayList( readNullableUuidArray( PostgresArrays
                        .getUuidArray( rs, AUDIT_EDGE_ENTITY_SET_IDS_FIELD ) ) ) );
    }

    private static UUID[] readNullableUuidArray( UUID[] nullable ) {
        if ( nullable == null ) {
            return new UUID[ 0 ];
        } else {
            return nullable;
        }
    }

    public static UUID auditEdgeEntitySetId( ResultSet rs ) throws SQLException {
        return rs.getObject( AUDIT_EDGE_ENTITY_SET_ID_FIELD, UUID.class );
    }

    public static Boolean exists( ResultSet rs ) throws SQLException {
        return rs.getBoolean( "exists" );
    }

    public static EntitySetAssemblyKey entitySetAssemblyKey( ResultSet rs ) throws SQLException {
        final UUID entitySetId = entitySetId( rs );
        final UUID organizationId = organizationId( rs );

        return new EntitySetAssemblyKey( entitySetId, organizationId );
    }

    public static MaterializedEntitySet materializedEntitySet( ResultSet rs ) throws SQLException {
        final EntitySetAssemblyKey entitySetAssemblyKey = entitySetAssemblyKey( rs );
        final EnumSet<OrganizationEntitySetFlag> organizationEntitySetFlags = organizationEntitySetFlags( rs );

        final var refreshRate = rs.getLong( REFRESH_RATE.getName() );
        // default value is -infinity, which is adapted to OffsetDateTime.MIN
        final var lastRefresh = rs.getObject( LAST_REFRESH.getName(), OffsetDateTime.class );

        return new MaterializedEntitySet(
                entitySetAssemblyKey,
                refreshRate,
                organizationEntitySetFlags,
                lastRefresh );
    }

    public static Boolean initialized( ResultSet rs ) throws SQLException {
        return rs.getBoolean( INITIALIZED.getName() );
    }
}
