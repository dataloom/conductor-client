package com.kryptnostic.conductor.rpc.odata;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.dataloom.edm.internal.DatastoreConstants;
import com.kryptnostic.datastore.cassandra.CommonColumns;
import com.kryptnostic.rhizome.cassandra.CassandraTableBuilder;
import com.kryptnostic.rhizome.cassandra.TableDef;

public enum Tables implements TableDef {
    ACL_KEYS,
    ENTITIES,
    ENTITY_SETS,
    ENTITY_TYPES,
    FQNS,
    ORGANIZATIONS,
    PERMISSIONS,
    PROPERTY_TYPES,
    SCHEMAS;

    private static final Logger         logger = LoggerFactory.getLogger( Tables.class );
    private final CassandraTableBuilder builder;

    private Tables() {
        this.builder = getTableDefinition( this );
    }

    public String getName() {
        return builder.getName();
    }

    public String getKeyspace() {
        return builder.getKeyspace().or( DatastoreConstants.KEYSPACE );
    }

    public CassandraTableBuilder getBuilder() {
        return builder;
    }

    private static CassandraTableBuilder getTableDefinition( Tables table ) {
        switch ( table ) {
            case ACL_KEYS:
                return new CassandraTableBuilder( ACL_KEYS )
                        .ifNotExists()
                        .partitionKey( CommonColumns.FQN )
                        .columns( CommonColumns.SECURABLE_OBJECT_TYPE, CommonColumns.SECURABLE_OBJECTID );
            case ENTITIES:
                return new CassandraTableBuilder( ENTITIES )
                        .ifNotExists()
                        .partitionKey( CommonColumns.SYNCID, CommonColumns.ENTITY_SET, CommonColumns.ENTITY_SET_ID )
                        .clusteringColumns( CommonColumns.PROPERTY_TYPE_ID, CommonColumns.PROPERTY_VALUE );
            case ENTITY_SETS:
                return new CassandraTableBuilder( ENTITY_SETS )
                        .ifNotExists()
                        .partitionKey( CommonColumns.ID )
                        .clusteringColumns( CommonColumns.TYPE, CommonColumns.NAME )
                        .columns( CommonColumns.ENTITY_TYPE_ID, CommonColumns.TITLE, CommonColumns.DESCRIPTION );
            case ENTITY_TYPES:
                return new CassandraTableBuilder( ENTITY_SETS )
                        .ifNotExists()
                        .partitionKey( CommonColumns.ID )
                        .clusteringColumns( CommonColumns.NAMESPACE, CommonColumns.NAME )
                        .columns( CommonColumns.TITLE,
                                CommonColumns.DESCRIPTION,
                                CommonColumns.KEY,
                                CommonColumns.PROPERTIES,
                                CommonColumns.SCHEMAS );
            case FQNS:
                return new CassandraTableBuilder( FQNS )
                        .ifNotExists()
                        .partitionKey( CommonColumns.SECURABLE_OBJECT_TYPE, CommonColumns.SECURABLE_OBJECTID )
                        .columns( CommonColumns.FQN );
            case ORGANIZATIONS:
                return new CassandraTableBuilder( ORGANIZATIONS )
                        .ifNotExists()
                        .partitionKey( CommonColumns.ID )
                        .clusteringColumns( CommonColumns.NAMESPACE, CommonColumns.NAME )
                        .columns( CommonColumns.TITLE,
                                CommonColumns.DESCRIPTION,
                                CommonColumns.SCHEMAS );
            case PROPERTY_TYPES:
                return new CassandraTableBuilder( PROPERTY_TYPES )
                        .ifNotExists()
                        .partitionKey( CommonColumns.ID )
                        .clusteringColumns( CommonColumns.NAMESPACE, CommonColumns.NAME )
                        .columns( CommonColumns.TITLE,
                                CommonColumns.DESCRIPTION,
                                CommonColumns.SCHEMAS );
            case PERMISSIONS:
                return new CassandraTableBuilder( PERMISSIONS )
                        .ifNotExists()
                        .partitionKey( CommonColumns.ACL_KEYS )
                        .clusteringColumns( CommonColumns.PRINCIPAL_TYPE, CommonColumns.PRINCIPAL_ID )
                        .columns( CommonColumns.SECURABLE_OBJECT_TYPE, CommonColumns.PERMISSIONS )
                        .secondaryIndex( CommonColumns.PERMISSIONS, CommonColumns.SECURABLE_OBJECT_TYPE );
            case SCHEMAS:
                new CassandraTableBuilder( SCHEMAS )
                        .ifNotExists()
                        .partitionKey( CommonColumns.NAMESPACE )
                        .clusteringColumns( CommonColumns.NAME );
            default:
                logger.error( "Missing table configuration {}, unable to start.", table.name() );
                throw new IllegalStateException( "Missing table configuration " + table.name() + ", unable to start." );
        }
    }

}
