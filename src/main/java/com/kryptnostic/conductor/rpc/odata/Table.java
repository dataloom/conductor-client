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

package com.kryptnostic.conductor.rpc.odata;

import static com.kryptnostic.datastore.cassandra.CommonColumns.ACL_CHILDREN_PERMISSIONS;
import static com.kryptnostic.datastore.cassandra.CommonColumns.ACL_KEY_VALUE;
import static com.kryptnostic.datastore.cassandra.CommonColumns.ACL_ROOT;
import static com.kryptnostic.datastore.cassandra.CommonColumns.ALLOWED_EMAIL_DOMAINS;
import static com.kryptnostic.datastore.cassandra.CommonColumns.ANALYZER;
import static com.kryptnostic.datastore.cassandra.CommonColumns.AUDIT_EVENT_DETAILS;
import static com.kryptnostic.datastore.cassandra.CommonColumns.AUDIT_ID;
import static com.kryptnostic.datastore.cassandra.CommonColumns.BASE_TYPE;
import static com.kryptnostic.datastore.cassandra.CommonColumns.BIDIRECTIONAL;
import static com.kryptnostic.datastore.cassandra.CommonColumns.BLOCK;
import static com.kryptnostic.datastore.cassandra.CommonColumns.BLOCK_ID;
import static com.kryptnostic.datastore.cassandra.CommonColumns.CATEGORY;
import static com.kryptnostic.datastore.cassandra.CommonColumns.CONTACTS;
import static com.kryptnostic.datastore.cassandra.CommonColumns.COUNT;
import static com.kryptnostic.datastore.cassandra.CommonColumns.CURRENT_SYNC_ID;
import static com.kryptnostic.datastore.cassandra.CommonColumns.DATATYPE;
import static com.kryptnostic.datastore.cassandra.CommonColumns.DATA_ID;
import static com.kryptnostic.datastore.cassandra.CommonColumns.DESCRIPTION;
import static com.kryptnostic.datastore.cassandra.CommonColumns.DESTINATION_LINKING_VERTEX_ID;
import static com.kryptnostic.datastore.cassandra.CommonColumns.DST;
import static com.kryptnostic.datastore.cassandra.CommonColumns.DST_ENTITY_KEY_ID;
import static com.kryptnostic.datastore.cassandra.CommonColumns.DST_ENTITY_SET_ID;
import static com.kryptnostic.datastore.cassandra.CommonColumns.DST_SYNC_ID;
import static com.kryptnostic.datastore.cassandra.CommonColumns.DST_TYPE_ID;
import static com.kryptnostic.datastore.cassandra.CommonColumns.EDGE_ENTITY_KEY_ID;
import static com.kryptnostic.datastore.cassandra.CommonColumns.EDGE_ENTITY_SET_ID;
import static com.kryptnostic.datastore.cassandra.CommonColumns.EDGE_TYPE_ID;
import static com.kryptnostic.datastore.cassandra.CommonColumns.EDGE_VALUE;
import static com.kryptnostic.datastore.cassandra.CommonColumns.ENTITYID;
import static com.kryptnostic.datastore.cassandra.CommonColumns.ENTITY_KEY;
import static com.kryptnostic.datastore.cassandra.CommonColumns.ENTITY_KEYS;
import static com.kryptnostic.datastore.cassandra.CommonColumns.ENTITY_SET_ID;
import static com.kryptnostic.datastore.cassandra.CommonColumns.ENTITY_SET_IDS;
import static com.kryptnostic.datastore.cassandra.CommonColumns.ENTITY_TYPE_ID;
import static com.kryptnostic.datastore.cassandra.CommonColumns.ENTITY_TYPE_IDS;
import static com.kryptnostic.datastore.cassandra.CommonColumns.EVENT_TYPE;
import static com.kryptnostic.datastore.cassandra.CommonColumns.FLAGS;
import static com.kryptnostic.datastore.cassandra.CommonColumns.GRAPH_DIAMETER;
import static com.kryptnostic.datastore.cassandra.CommonColumns.GRAPH_ID;
import static com.kryptnostic.datastore.cassandra.CommonColumns.ID;
import static com.kryptnostic.datastore.cassandra.CommonColumns.KEY;
import static com.kryptnostic.datastore.cassandra.CommonColumns.MEMBERS;
import static com.kryptnostic.datastore.cassandra.CommonColumns.NAME;
import static com.kryptnostic.datastore.cassandra.CommonColumns.NAMESPACE;
import static com.kryptnostic.datastore.cassandra.CommonColumns.NAME_SET;
import static com.kryptnostic.datastore.cassandra.CommonColumns.NEW_VERTEX_ID;
import static com.kryptnostic.datastore.cassandra.CommonColumns.ORGANIZATION_ID;
import static com.kryptnostic.datastore.cassandra.CommonColumns.PII_FIELD;
import static com.kryptnostic.datastore.cassandra.CommonColumns.PRINCIPAL_ID;
import static com.kryptnostic.datastore.cassandra.CommonColumns.PRINCIPAL_IDS;
import static com.kryptnostic.datastore.cassandra.CommonColumns.PRINCIPAL_TYPE;
import static com.kryptnostic.datastore.cassandra.CommonColumns.PROPERTIES;
import static com.kryptnostic.datastore.cassandra.CommonColumns.PROPERTY_BUFFER;
import static com.kryptnostic.datastore.cassandra.CommonColumns.PROPERTY_TYPE_ID;
import static com.kryptnostic.datastore.cassandra.CommonColumns.PROPERTY_VALUE;
import static com.kryptnostic.datastore.cassandra.CommonColumns.QUERY_ID;
import static com.kryptnostic.datastore.cassandra.CommonColumns.REASON;
import static com.kryptnostic.datastore.cassandra.CommonColumns.REQUESTID;
import static com.kryptnostic.datastore.cassandra.CommonColumns.RPC_REQUEST_ID;
import static com.kryptnostic.datastore.cassandra.CommonColumns.RPC_VALUE;
import static com.kryptnostic.datastore.cassandra.CommonColumns.RPC_WEIGHT;
import static com.kryptnostic.datastore.cassandra.CommonColumns.SECURABLE_OBJECTID;
import static com.kryptnostic.datastore.cassandra.CommonColumns.SECURABLE_OBJECT_TYPE;
import static com.kryptnostic.datastore.cassandra.CommonColumns.SOURCE_LINKING_VERTEX_ID;
import static com.kryptnostic.datastore.cassandra.CommonColumns.SRC;
import static com.kryptnostic.datastore.cassandra.CommonColumns.SRC_ENTITY_KEY_ID;
import static com.kryptnostic.datastore.cassandra.CommonColumns.SRC_ENTITY_SET_ID;
import static com.kryptnostic.datastore.cassandra.CommonColumns.SRC_SYNC_ID;
import static com.kryptnostic.datastore.cassandra.CommonColumns.SRC_TYPE_ID;
import static com.kryptnostic.datastore.cassandra.CommonColumns.STATUS;
import static com.kryptnostic.datastore.cassandra.CommonColumns.SYNCID;
import static com.kryptnostic.datastore.cassandra.CommonColumns.TIME_ID;
import static com.kryptnostic.datastore.cassandra.CommonColumns.TIME_UUID;
import static com.kryptnostic.datastore.cassandra.CommonColumns.TITLE;
import static com.kryptnostic.datastore.cassandra.CommonColumns.VERTEX_ID;
import static com.kryptnostic.datastore.cassandra.CommonColumns.WEIGHT;

import com.dataloom.edm.internal.DatastoreConstants;
import com.kryptnostic.datastore.cassandra.CommonColumns;
import com.kryptnostic.rhizome.cassandra.CassandraMaterializedViewBuilder;
import com.kryptnostic.rhizome.cassandra.CassandraTableBuilder;
import com.kryptnostic.rhizome.cassandra.TableDef;
import java.util.EnumMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public enum Table implements TableDef {
    ACL_KEYS,
    AUDIT_LOG,
    AUDIT_EVENTS, // TODO: this needs to be removed
    AUDIT_METRICS, // TODO: this needs to be removed
    BACK_EDGES,
    COMPLEX_TYPES,
    DATA,
    EDGES,
    ENTITY_SETS,
    ENTITY_TYPES,
    ENUM_TYPES,
    LINKING_EDGES,
    LINKED_ENTITY_SETS,
    LINKED_ENTITY_TYPES,
    LINKING_VERTICES,
    LINKING_ENTITY_VERTICES,
    NAMES,
    ORGANIZATIONS,
    ORGANIZATIONS_ROLES,
    PERMISSIONS,
    PERMISSIONS_REQUESTS_UNRESOLVED,
    PERMISSIONS_REQUESTS_RESOLVED,
    PROPERTY_TYPES,
    REQUESTS,
    RPC_DATA_ORDERED,
    SCHEMAS,
    WEIGHTED_LINKING_EDGES,
    ASSOCIATION_TYPES,
    VERTICES,
    SYNC_IDS,
    IDS,
    KEYS,
    TOP_UTILIZER_DATA,
    VERTEX_IDS_AFTER_LINKING;

    private static final Logger                                logger   = LoggerFactory
            .getLogger( Table.class );
    private static final EnumMap<Table, CassandraTableBuilder> cache    = new EnumMap<>( Table.class );
    private static       String                                keyspace = DatastoreConstants.KEYSPACE;

    public String getName() {
        return name();
    }

    public String getKeyspace() {
        return keyspace;
    }

    public CassandraTableBuilder getBuilder() {
        return getTableDefinition( this );
    }

    public TableDef asTableDef() {
        return this;
    }

    static CassandraTableBuilder getTableDefinition( Table table ) {
        CassandraTableBuilder ctb = cache.get( table );
        if ( ctb == null ) {
            ctb = createTableDefinition( table );
            cache.put( table, ctb );
        }
        return ctb;
    }

    static CassandraTableBuilder createTableDefinition( Table table ) {
        switch ( table ) {
            case ACL_KEYS:
                return new CassandraTableBuilder( ACL_KEYS )
                        .ifNotExists()
                        .partitionKey( NAME )
                        .columns( SECURABLE_OBJECTID );

            // TODO: ENTITY_ID( DataType.uuid() ) or ENTITYID( DataType.text() )
            case AUDIT_LOG:
                return new CassandraTableBuilder( AUDIT_LOG )
                        .ifNotExists()
                        .partitionKey(
                                CommonColumns.ACL_KEYS,
                                EVENT_TYPE
                        )
                        .clusteringColumns(
                                PRINCIPAL_TYPE,
                                PRINCIPAL_ID,
                                TIME_UUID
                        )
                        .columns(
                                AUDIT_ID,
                                DATA_ID,
                                BLOCK_ID
                        )
                        .secondaryIndex(
                                PRINCIPAL_TYPE,
                                PRINCIPAL_ID,
                                TIME_UUID
                        )
                        .withDescendingOrder(
                                TIME_UUID
                        );

            // TODO: remove AUDIT_EVENTS, AUDIT_LOG replaces AUDIT_EVENTS
            case AUDIT_EVENTS:
                return new CassandraTableBuilder( AUDIT_EVENTS )
                        .ifNotExists()
                        .partitionKey( CommonColumns.ACL_KEYS )
                        .clusteringColumns( TIME_ID, PRINCIPAL_TYPE, PRINCIPAL_ID )
                        .columns( CommonColumns.PERMISSIONS, AUDIT_EVENT_DETAILS, BLOCK )
                        .secondaryIndex( PRINCIPAL_TYPE, PRINCIPAL_ID );
            // TODO: remove AUDIT_METRICS
            case AUDIT_METRICS:
                return new CassandraTableBuilder( AUDIT_METRICS )
                        .ifNotExists()
                        .partitionKey( CommonColumns.ACL_KEYS )
                        .clusteringColumns( COUNT, ACL_KEY_VALUE )
                        .withDescendingOrder( COUNT );
            case COMPLEX_TYPES:
                return new CassandraTableBuilder( COMPLEX_TYPES )
                        .ifNotExists()
                        .partitionKey( ID )
                        .clusteringColumns( NAMESPACE, NAME )
                        .columns( TITLE,
                                DESCRIPTION,
                                PROPERTIES,
                                BASE_TYPE,
                                CommonColumns.SCHEMAS,
                                CATEGORY )
                        .secondaryIndex( NAMESPACE, CommonColumns.SCHEMAS );
            case DATA:
                /*
                 * The main reason for entityid being before property_type_id is that we always have to
                 * issue an individual query per entityid to load an object. Even if we could query by
                 * property type id we'd have to group everything in memory instead of being able to stream.
                 */
                return new CassandraTableBuilder( DATA )
                        .ifNotExists()
                        .partitionKey( ID )
                        .clusteringColumns( ENTITY_SET_ID, SYNCID, ENTITYID, PROPERTY_TYPE_ID, PROPERTY_VALUE )
                        .columns( PROPERTY_BUFFER );
            case BACK_EDGES:
                return new CassandraTableBuilder( BACK_EDGES )
                        .ifNotExists()
                        .partitionKey( SRC_ENTITY_KEY_ID )
                        .clusteringColumns( DST_TYPE_ID, EDGE_TYPE_ID, DST_ENTITY_KEY_ID, EDGE_ENTITY_KEY_ID )
                        .columns( SRC_TYPE_ID, SRC_ENTITY_SET_ID, DST_ENTITY_SET_ID, EDGE_ENTITY_SET_ID )
                        .secondaryIndex(
                                DST_TYPE_ID,
                                EDGE_TYPE_ID,
                                DST_ENTITY_KEY_ID,
                                EDGE_ENTITY_KEY_ID,
                                SRC_TYPE_ID,
                                SRC_ENTITY_SET_ID,
                                DST_ENTITY_SET_ID,
                                EDGE_ENTITY_SET_ID );
            case EDGES:
                /*
                 * Allows for efficient selection of edges as long as types are provided.
                 */
                return new CassandraTableBuilder( EDGES )
                        .ifNotExists()
                        .partitionKey( SRC_ENTITY_KEY_ID )
                        .clusteringColumns( DST_TYPE_ID, EDGE_TYPE_ID, DST_ENTITY_KEY_ID, EDGE_ENTITY_KEY_ID )
                        .columns( SRC_TYPE_ID, SRC_ENTITY_SET_ID, SRC_SYNC_ID, DST_ENTITY_SET_ID, DST_SYNC_ID, EDGE_ENTITY_SET_ID )
                        .secondaryIndex(
                                DST_TYPE_ID,
                                EDGE_TYPE_ID,
                                DST_ENTITY_KEY_ID,
                                EDGE_ENTITY_KEY_ID,
                                SRC_TYPE_ID,
                                SRC_ENTITY_SET_ID,
                                SRC_SYNC_ID,
                                DST_ENTITY_SET_ID,
                                DST_SYNC_ID,
                                EDGE_ENTITY_SET_ID );
            case ENTITY_SETS:
                return new CassandraTableBuilder( ENTITY_SETS )
                        .ifNotExists()
                        .partitionKey( ID )
                        .clusteringColumns( NAME )
                        .columns( ENTITY_TYPE_ID,
                                TITLE,
                                DESCRIPTION,
                                CONTACTS )
                        .secondaryIndex( ENTITY_TYPE_ID, NAME );
            case ENTITY_TYPES:
                return new CassandraTableBuilder( ENTITY_TYPES )
                        .ifNotExists()
                        .partitionKey( ID )
                        .clusteringColumns( NAMESPACE, NAME )
                        .columns( TITLE,
                                DESCRIPTION,
                                KEY,
                                PROPERTIES,
                                BASE_TYPE,
                                CommonColumns.SCHEMAS,
                                CATEGORY )
                        .secondaryIndex( NAMESPACE, CommonColumns.SCHEMAS );
            case ENUM_TYPES:
                return new CassandraTableBuilder( ENUM_TYPES )
                        .ifNotExists()
                        .partitionKey( ID )
                        .clusteringColumns( NAMESPACE, NAME )
                        .columns( TITLE,
                                DESCRIPTION,
                                MEMBERS,
                                CommonColumns.SCHEMAS,
                                DATATYPE,
                                FLAGS,
                                PII_FIELD,
                                ANALYZER )
                        .secondaryIndex( NAMESPACE, CommonColumns.SCHEMAS );
            case IDS:
                return new CassandraTableBuilder( IDS )
                        .ifNotExists()
                        .partitionKey( ENTITY_KEY )
                        .columns( ID );
            case KEYS:
                return new CassandraMaterializedViewBuilder( IDS.getBuilder(), KEYS.getName() )
                        .ifNotExists()
                        .partitionKey( ID )
                        .clusteringColumns( ENTITY_KEY );
            case WEIGHTED_LINKING_EDGES:
                return new CassandraTableBuilder( WEIGHTED_LINKING_EDGES )
                        .ifNotExists()
                        .partitionKey( GRAPH_ID )
                        .clusteringColumns( EDGE_VALUE, SOURCE_LINKING_VERTEX_ID, DESTINATION_LINKING_VERTEX_ID )
                        .secondaryIndex( SOURCE_LINKING_VERTEX_ID, DESTINATION_LINKING_VERTEX_ID );
            case LINKING_EDGES:
                return new CassandraTableBuilder( LINKING_EDGES )
                        .ifNotExists()
                        .partitionKey( GRAPH_ID, SOURCE_LINKING_VERTEX_ID )
                        .clusteringColumns( DESTINATION_LINKING_VERTEX_ID );
            case LINKED_ENTITY_SETS:
                return new CassandraTableBuilder( LINKED_ENTITY_SETS )
                        .ifNotExists()
                        .partitionKey( ID )
                        .columns( ENTITY_SET_IDS );
            case LINKED_ENTITY_TYPES:
                return new CassandraTableBuilder( LINKED_ENTITY_TYPES )
                        .ifNotExists()
                        .partitionKey( ID )
                        .columns( ENTITY_TYPE_IDS );
            case LINKING_VERTICES:
                return new CassandraTableBuilder( LINKING_VERTICES )
                        .ifNotExists()
                        .partitionKey( VERTEX_ID )
                        .clusteringColumns( GRAPH_ID )
                        .columns( GRAPH_DIAMETER, ENTITY_KEYS );
            case LINKING_ENTITY_VERTICES:
                return new CassandraTableBuilder( LINKING_ENTITY_VERTICES )
                        .ifNotExists()
                        .partitionKey( ENTITY_SET_ID, ENTITYID, SYNCID )
                        .clusteringColumns( GRAPH_ID )
                        .columns( VERTEX_ID );
            case ASSOCIATION_TYPES:
                return new CassandraTableBuilder( ASSOCIATION_TYPES )
                        .ifNotExists()
                        .partitionKey( ID )
                        .clusteringColumns( SRC, DST )
                        .columns( BIDIRECTIONAL );
            case NAMES:
                return new CassandraTableBuilder( NAMES )
                        .ifNotExists()
                        .partitionKey( SECURABLE_OBJECTID )
                        .columns( NAME );
            case ORGANIZATIONS:
                return new CassandraTableBuilder( ORGANIZATIONS )
                        .ifNotExists()
                        .partitionKey( ID )
                        .columns( TITLE,
                                DESCRIPTION,
                                ALLOWED_EMAIL_DOMAINS,
                                MEMBERS );
            case ORGANIZATIONS_ROLES:
                return new CassandraTableBuilder( ORGANIZATIONS_ROLES )
                        .ifNotExists()
                        .partitionKey( ORGANIZATION_ID )
                        .clusteringColumns( ID )
                        .columns( TITLE,
                                DESCRIPTION,
                                PRINCIPAL_IDS );
            case PROPERTY_TYPES:
                return new CassandraTableBuilder( PROPERTY_TYPES )
                        .ifNotExists()
                        .partitionKey( ID )
                        .clusteringColumns( NAMESPACE, NAME )
                        .columns( TITLE,
                                DESCRIPTION,
                                CommonColumns.SCHEMAS,
                                DATATYPE,
                                PII_FIELD,
                                ANALYZER )
                        .secondaryIndex( NAMESPACE, CommonColumns.SCHEMAS );
            case PERMISSIONS:
                // TODO: Once Cassandra fixes SASI + Collection column inde
                return new CassandraTableBuilder( PERMISSIONS )
                        .ifNotExists()
                        .partitionKey( CommonColumns.ACL_KEYS )
                        .clusteringColumns( PRINCIPAL_TYPE, PRINCIPAL_ID )
                        .columns( CommonColumns.PERMISSIONS )
                        .staticColumns( SECURABLE_OBJECT_TYPE )
                        .secondaryIndex( PRINCIPAL_TYPE,
                                PRINCIPAL_ID,
                                CommonColumns.PERMISSIONS,
                                SECURABLE_OBJECT_TYPE );
            case PERMISSIONS_REQUESTS_UNRESOLVED:
                return new CassandraTableBuilder( PERMISSIONS_REQUESTS_UNRESOLVED )
                        .ifNotExists()
                        .partitionKey( ACL_ROOT )
                        .clusteringColumns( PRINCIPAL_ID )
                        .columns( ACL_CHILDREN_PERMISSIONS, STATUS )
                        .secondaryIndex( STATUS );
            case PERMISSIONS_REQUESTS_RESOLVED:
                return new CassandraTableBuilder( PERMISSIONS_REQUESTS_RESOLVED )
                        .ifNotExists()
                        .partitionKey( PRINCIPAL_ID )
                        .clusteringColumns( REQUESTID )
                        .columns( ACL_ROOT, ACL_CHILDREN_PERMISSIONS, STATUS )
                        .fullCollectionIndex( ACL_ROOT )
                        .secondaryIndex( STATUS );
            case REQUESTS:
                return new CassandraTableBuilder( REQUESTS )
                        .ifNotExists()
                        .partitionKey( CommonColumns.ACL_KEYS )
                        .clusteringColumns( PRINCIPAL_TYPE, PRINCIPAL_ID )
                        .columns( CommonColumns.PERMISSIONS, REASON, STATUS )
                        .secondaryIndex( PRINCIPAL_TYPE,
                                PRINCIPAL_ID,
                                STATUS );
            case RPC_DATA_ORDERED:
                return new CassandraTableBuilder( RPC_DATA_ORDERED )
                        .ifNotExists()
                        .partitionKey( RPC_REQUEST_ID )
                        .clusteringColumns( RPC_WEIGHT, RPC_VALUE )
                        .withDescendingOrder( RPC_WEIGHT );
            case SCHEMAS:
                return new CassandraTableBuilder( SCHEMAS )
                        .ifNotExists()
                        .partitionKey( NAMESPACE )
                        .columns( NAME_SET );

            case SYNC_IDS:
                return new CassandraTableBuilder( SYNC_IDS )
                        .ifNotExists()
                        .partitionKey( ENTITY_SET_ID )
                        .clusteringColumns( SYNCID )
                        .staticColumns( CURRENT_SYNC_ID )
                        .withDescendingOrder( SYNCID );

            case TOP_UTILIZER_DATA:
                return new CassandraTableBuilder( TOP_UTILIZER_DATA )
                        .ifNotExists()
                        .partitionKey( QUERY_ID )
                        .clusteringColumns( WEIGHT, VERTEX_ID )
                        .withDescendingOrder( WEIGHT );

            case VERTICES:
                return new CassandraTableBuilder( VERTICES )
                        .ifNotExists()
                        .partitionKey( VERTEX_ID );
            case VERTEX_IDS_AFTER_LINKING:
                return new CassandraTableBuilder( VERTEX_IDS_AFTER_LINKING )
                        .ifNotExists()
                        .partitionKey( VERTEX_ID )
                        .clusteringColumns( GRAPH_ID )
                        .columns( NEW_VERTEX_ID );
            default:
                logger.error( "Missing table configuration {}, unable to start.", table.name() );
                throw new IllegalStateException( "Missing table configuration " + table.name() + ", unable to start." );
        }
    }

}
