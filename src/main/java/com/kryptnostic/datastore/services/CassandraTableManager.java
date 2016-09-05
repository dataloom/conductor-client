package com.kryptnostic.datastore.services;

import java.util.Map;

import org.apache.commons.lang3.NotImplementedException;
import org.apache.commons.lang3.RandomStringUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.olingo.commons.api.edm.FullQualifiedName;

import com.datastax.driver.core.DataType;
import com.datastax.driver.core.PreparedStatement;
import com.datastax.driver.core.Row;
import com.datastax.driver.core.Session;
import com.datastax.driver.core.querybuilder.QueryBuilder;
import com.datastax.driver.mapping.MappingManager;
import com.hazelcast.core.HazelcastInstance;
import com.kryptnostic.conductor.rpc.odata.DatastoreConstants;
import com.kryptnostic.conductor.rpc.odata.EntityType;
import com.kryptnostic.conductor.rpc.odata.PropertyType;
import com.kryptnostic.conductor.rpc.odata.Schema;
import com.kryptnostic.conductor.rpc.odata.Tables;
import com.kryptnostic.datastore.cassandra.CassandraEdmMapping;
import com.kryptnostic.datastore.cassandra.CassandraTableBuilder;
import com.kryptnostic.datastore.cassandra.CommonColumns;
import com.kryptnostic.datastore.cassandra.Queries;
import com.kryptnostic.datastore.util.Util;

import jersey.repackaged.com.google.common.base.Preconditions;
import jersey.repackaged.com.google.common.collect.Maps;

public class CassandraTableManager {
    static enum TableType {
        property_,
        entity_,
        index_;
    }

    private final String                                    keyspace;
    private final Session                                   session;

    private final Map<FullQualifiedName, PreparedStatement> propertyTypeUpdateStatements;
    private final Map<FullQualifiedName, PreparedStatement> propertyIndexUpdateStatements;
    private final Map<FullQualifiedName, PreparedStatement> entityTypeInsertStatements;
    private final Map<FullQualifiedName, PreparedStatement> entityTypeUpdateStatements;

    private final PreparedStatement                         getTypenameForEntityType;
    private final PreparedStatement                         getTypenameForPropertyType;
    private final PreparedStatement                         countProperty;
    private final PreparedStatement                         insertPropertyTypeLookup;
    private final PreparedStatement                         updatePropertyTypeLookup;
    private final PreparedStatement                         deletePropertyTypeLookup;
    private final PreparedStatement                         getFullQualifiedName;

    public CassandraTableManager(
            HazelcastInstance hazelcast,
            String keyspace,
            Session session,
            MappingManager mm ) {
        initCoreTables( keyspace, session );

        this.session = session;
        this.keyspace = keyspace;
        this.propertyTypeUpdateStatements = Maps.newConcurrentMap();
        this.propertyIndexUpdateStatements = Maps.newConcurrentMap();
        this.entityTypeInsertStatements = Maps.newConcurrentMap();
        this.entityTypeUpdateStatements = Maps.newConcurrentMap();

        this.getTypenameForEntityType = session.prepare( QueryBuilder
                .select()
                .from( keyspace, Tables.ENTITY_TYPES.getTableName() )
                .where( QueryBuilder.eq( CommonColumns.NAMESPACE.cql(),
                        QueryBuilder.bindMarker() ) )
                .and( QueryBuilder.eq( CommonColumns.NAME.cql(),
                        QueryBuilder.bindMarker() ) ) );
        this.getTypenameForPropertyType = session.prepare( QueryBuilder
                .select()
                .from( keyspace, Tables.PROPERTY_TYPES.getTableName() )
                .where( QueryBuilder.eq( CommonColumns.NAMESPACE.cql(),
                        QueryBuilder.bindMarker() ) )
                .and( QueryBuilder.eq( CommonColumns.NAME.cql(),
                        QueryBuilder.bindMarker() ) ) );

        this.countProperty = session.prepare( QueryBuilder
                .select().countAll()
                .from( keyspace, DatastoreConstants.PROPERTY_TYPES_TABLE )
                .where( QueryBuilder.eq( CommonColumns.NAMESPACE.cql(),
                        QueryBuilder.bindMarker() ) )
                .and( QueryBuilder.eq( CommonColumns.NAME.cql(),
                        QueryBuilder.bindMarker() ) ) );

        this.insertPropertyTypeLookup = session
                .prepare( QueryBuilder.insertInto( keyspace, DatastoreConstants.FQN_LOOKUP_TABLE )
                        .value( CommonColumns.TYPENAME.cql(), QueryBuilder.bindMarker() )
                        .value( CommonColumns.FQN.cql(), QueryBuilder.bindMarker() ) );

        this.updatePropertyTypeLookup = session
                .prepare( ( QueryBuilder.update( keyspace, DatastoreConstants.FQN_LOOKUP_TABLE ) )
                        .with( QueryBuilder.set( CommonColumns.FQN.cql(), QueryBuilder.bindMarker() ) )
                        .where( QueryBuilder.eq( CommonColumns.TYPENAME.cql(), QueryBuilder.bindMarker() ) ) );

        this.deletePropertyTypeLookup = session
                .prepare( QueryBuilder.delete().from( keyspace, DatastoreConstants.FQN_LOOKUP_TABLE )
                        .where( QueryBuilder.eq( CommonColumns.TYPENAME.cql(), QueryBuilder.bindMarker() ) ) );

        this.getFullQualifiedName = session
                .prepare( QueryBuilder.select().from( keyspace, DatastoreConstants.FQN_LOOKUP_TABLE )
                        .where( QueryBuilder.eq( CommonColumns.TYPENAME.cql(), QueryBuilder.bindMarker() ) ) );

    }

    private void initCoreTables( String keyspace, Session session ) {
        createKeyspaceSparksIfNotExists( keyspace, session );
        createSchemasTableIfNotExists( keyspace, session );
        createEntityTypesTableIfNotExists( keyspace, session );
        createPropertyTypesTableIfNotExists( keyspace, session );
        createEntitySetsTableIfNotExists( keyspace, session );
        createEntityIdToTypenameTableIfNotExists( session );
        createFullQualifiedNameLookupTableIfNotExists( session );
    }

    private static void createEntityIdToTypenameTableIfNotExists( String keyspace, Session session ) {
        session.execute( Queries.getCreateEntityIdToTypenameTableQuery( keyspace ) );

    }

    public void registerSchema( Schema schema ) {
        Preconditions.checkArgument( schema.getEntityTypeFqns().size() == schema.getEntityTypes().size(),
                "Schema is out of sync." );
        schema.getEntityTypes().forEach( et -> {
            // TODO: Solve ID generation
            /*
             * While unlikely it's possible to have a UUID collision when creating an object. Two possible solutions:
             * (1) Use Hazelcast and perform a read prior to every write (2) Maintain a self-refreshing in-memory pool
             * of available UUIDs that shifts the reads to times when cassandra is under less stress. Option (2) with a
             * fall back to random UUID generation when pool is exhausted seems like an efficient bet.
             */
            putEntityTypeInsertStatement( et.getFullQualifiedName() );
            putEntityTypeUpdateStatement( et.getFullQualifiedName() );
            et.getKey().forEach( fqn -> putPropertyIndexUpdateStatement( fqn ) );
        } );

        schema.getPropertyTypes().forEach( pt -> {
            putPropertyTypeUpdateStatement( pt.getFullQualifiedName() );
        } );
    }

    public PreparedStatement getInsertEntityPreparedStatement( EntityType entityType ) {
        return getInsertEntityPreparedStatement( entityType.getFullQualifiedName() );
    }

    public PreparedStatement getInsertEntityPreparedStatement( FullQualifiedName fqn ) {
        return entityTypeInsertStatements.get( fqn );
    }

    public PreparedStatement getUpdateEntityPreparedStatement( EntityType entityType ) {
        return getUpdateEntityPreparedStatement( entityType.getFullQualifiedName() );
    }

    public PreparedStatement getUpdateEntityPreparedStatement( FullQualifiedName fqn ) {
        return entityTypeUpdateStatements.get( fqn );
    }

    public PreparedStatement getUpdatePropertyPreparedStatement( PropertyType propertyType ) {
        return getUpdatePropertyPreparedStatement( propertyType.getFullQualifiedName() );
    }

    public PreparedStatement getUpdatePropertyPreparedStatement( FullQualifiedName fqn ) {
        return propertyTypeUpdateStatements.get( fqn );
    }

    public PreparedStatement getUpdatePropertyIndexPreparedStatement( FullQualifiedName fqn ) {
        return this.propertyIndexUpdateStatements.get( fqn );
    }

    public PreparedStatement getCountPropertyStatement() {
        return countProperty;
    }

    public void createEntityTypeTable( EntityType entityType, Map<FullQualifiedName, PropertyType> keyPropertyTypes ) {
        // Ensure that type name doesn't exist
        CassandraTableBuilder ctb;
        String entityTableQuery;

        do {
            ctb = new CassandraTableBuilder( keyspace, TableType.entity_.name() + entityType.getTypename() )
                    .ifNotExists()
                    .partitionKey( CommonColumns.OBJECTID, CommonColumns.ACLID )
                    .clusteringColumns( CommonColumns.CLOCK )
                    .columns( CommonColumns.ENTITYSETS, CommonColumns.SYNCIDS );
            entityTableQuery = ctb.buildQuery();
        } while ( !Util.wasLightweightTransactionApplied( session.execute( entityTableQuery ) ) );

        entityType.getKey().forEach( fqn -> {
            // TODO: Use elasticsearch for maintaining index instead of maintaining in Cassandra.
            /*
             * This makes sure that index tables are created if they do not exist. Other entity types may already be
             * using this propety type as a key.
             */
            PropertyType keyPropertyType = keyPropertyTypes.get( fqn );
            String typename = keyPropertyType.getTypename();
            Preconditions.checkArgument( StringUtils.isNotBlank( typename ),
                    "Typename for key property type cannot be null" );
            session.execute(
                    new CassandraTableBuilder( keyspace, TableType.index_.name() + typename )
                            .partitionKey( CommonColumns.VALUE, CommonColumns.ACLID )
                            .clusteringColumns( CommonColumns.OBJECTID )
                            .columns( CommonColumns.SYNCIDS )
                            .withTypeResolver(
                                    cc -> CassandraEdmMapping.getCassandraType( keyPropertyType.getDatatype() ) )
                            .buildQuery() );
            putPropertyIndexUpdateStatement( fqn );
        } );
        putEntityTypeInsertStatement( entityType.getFullQualifiedName() );
        putEntityTypeUpdateStatement( entityType.getFullQualifiedName() );
        // Loop until table creation succeeds.
    }

    public void createPropertyTypeTable( PropertyType propertyType ) {
        CassandraTableBuilder ctb;
        String propertyTableQuery;
        DataType valueType = CassandraEdmMapping.getCassandraType( propertyType.getDatatype() );
        do {
            ctb = new CassandraTableBuilder( keyspace, TableType.property_.name() + propertyType.getTypename() )
                    .partitionKey( CommonColumns.OBJECTID, CommonColumns.ACLID )
                    .clusteringColumns( CommonColumns.VALUE )
                    .columns( CommonColumns.SYNCIDS )
                    .withTypeResolver( cc -> valueType );
            propertyTableQuery = ctb.buildQuery();
            // Loop until table creation succeeds.
        } while ( !Util.wasLightweightTransactionApplied( session.execute( propertyTableQuery ) ) );

        putPropertyTypeUpdateStatement( propertyType.getFullQualifiedName() );
    }

    public void insertToFQNLookupTable( PropertyType propertyType ) {
        session.execute(
                insertPropertyTypeLookup.bind( propertyType.getTypename(), propertyType.getFullQualifiedName() ) );
    }

    public void updateFQNLookupTable( PropertyType propertyType ) {
        session.execute(
                updatePropertyTypeLookup.bind( propertyType.getTypename(), propertyType.getFullQualifiedName() ) );
    }

    public static String generateTypename() {
        return RandomStringUtils.randomAlphanumeric( 24 ).toLowerCase();
    }

    public void deleteEntityTypeTable( String namespace, String entityName ) {
        // We should mark tables for deletion-- we lose historical information if we hard delete properties.
        /*
         * Use Accessor interface to look up objects and retrieve typename corresponding to table to delete.
         */
        throw new NotImplementedException( "Blame MTR" );
    }

    public void deletePropertyTypeTable( String namespace, String propertyName ) {
        throw new NotImplementedException( "Blame MTR" );
    }

    public void deleteFromFQNTable( PropertyType propertyType ) {
        FullQualifiedName fqn = getFullQualifiedNameForTypename( propertyType.getTypename() );
        if ( fqn != null ) {
            session.execute(
                    deletePropertyTypeLookup.bind( propertyType.getTypename() ) );
        }
    }

    public String getTypenameForEntityType( EntityType entityType ) {
        return getTypenameForEntityType( entityType.getNamespace(), entityType.getName() );
    }

    public String getTypenameForEntityType( FullQualifiedName fullQualifiedName ) {
        return getTypenameForEntityType( fullQualifiedName.getNamespace(), fullQualifiedName.getName() );
    }

    public String getTypenameForEntityType( String namespace, String name ) {
        Row r = session.execute( this.getTypenameForEntityType.bind( namespace, name ) ).one();
        if ( r == null ) {
            return null;
        }
        return r.getString( CommonColumns.TYPENAME.cql() );
    }

    public String getTypenameForPropertyType( PropertyType propertyType ) {
        return getTypenameForPropertyType( propertyType.getNamespace(), propertyType.getName() );
    }

    public String getTypenameForPropertyType( FullQualifiedName fullQualifiedName ) {
        return getTypenameForPropertyType( fullQualifiedName.getNamespace(), fullQualifiedName.getName() );
    }

    public FullQualifiedName getFullQualifiedNameForTypename( String typename ) {
        Row r = session.execute( this.getFullQualifiedName.bind( typename ) ).one();
        if ( r == null ) {
            return null;
        }
        return new FullQualifiedName( r.getString( CommonColumns.FQN.cql() ) );
    }

    public Map<String, FullQualifiedName> getFullQualifiedNamesForTypenames( Iterable<String> typenames ) {
        return (Map<String, FullQualifiedName>) Maps.toMap( typenames, ( String input ) -> {
            return getFullQualifiedNameForTypename( input );
        } );
    }

    public String getTablenameForEntityType( EntityType entityType ) {
        return getTypenameForEntityType( entityType.getFullQualifiedName() );
    }

    public String getTablenameForEntityType( FullQualifiedName fqn ) {
        return TableType.entity_.name() + getTypenameForEntityType( fqn );
    }

    public String getTablenameForPropertyType( PropertyType propertyType ) {
        return getTablenameForPropertyType( propertyType.getFullQualifiedName() );
    }

    public String getTablenameForPropertyType( FullQualifiedName fqn ) {
        return TableType.property_.name() + getTypenameForPropertyType( fqn );
    }

    public String getTablenameForPropertyIndex( PropertyType propertyType ) {
        return getTablenameForPropertyIndex( propertyType.getFullQualifiedName() );
    }

    public String getTablenameForPropertyIndex( FullQualifiedName propertyType ) {
        return TableType.index_.name()
                + getTypenameForPropertyType( propertyType.getNamespace(), propertyType.getName() );
    }

    public String getTablenameForPropertyValues( FullQualifiedName propertyType ) {
        return TableType.property_.name()
                + getTypenameForPropertyType( propertyType.getNamespace(), propertyType.getName() );
    }

    public String getKeyspace() {
        return keyspace;
    }

    private String getTypenameForPropertyType( String namespace, String name ) {
        Row r = session.execute( this.getTypenameForPropertyType.bind( namespace, name ) ).one();
        if ( r == null ) {
            return null;
        }
        return r.getString( CommonColumns.TYPENAME.cql() );
    }

    private void putEntityTypeInsertStatement( FullQualifiedName entityTypeFqn ) {
        entityTypeInsertStatements.put( entityTypeFqn,
                session.prepare( QueryBuilder
                        .insertInto( keyspace, TableType.entity_.name() + getTypenameForEntityType( entityTypeFqn ) )
                        .value( CommonColumns.OBJECTID.cql(), QueryBuilder.bindMarker() )
                        .value( CommonColumns.ACLID.cql(), QueryBuilder.bindMarker() )
                        .value( CommonColumns.CLOCK.cql(),
                                QueryBuilder.fcall( "toTimestamp", QueryBuilder.now() ) )
                        .value( CommonColumns.ENTITYSETS.cql(), QueryBuilder.bindMarker() )
                        .value( CommonColumns.SYNCIDS.cql(), QueryBuilder.bindMarker() ) ) );
    }

    private void putEntityTypeUpdateStatement( FullQualifiedName entityTypeFqn ) {
        entityTypeUpdateStatements.put( entityTypeFqn,
                session.prepare( QueryBuilder
                        .update( keyspace, TableType.entity_.name() + getTypenameForEntityType( entityTypeFqn ) )
                        .with( QueryBuilder.addAll( CommonColumns.ENTITYSETS.cql(),
                                QueryBuilder.bindMarker() ) )
                        .and( QueryBuilder.appendAll( CommonColumns.SYNCIDS.cql(),
                                QueryBuilder.bindMarker() ) )
                        .where( QueryBuilder.eq( CommonColumns.OBJECTID.cql(), QueryBuilder.bindMarker() ) )
                        .and( QueryBuilder.eq( CommonColumns.ACLID.cql(), QueryBuilder.bindMarker() ) )
                        .and( QueryBuilder.eq( CommonColumns.CLOCK.cql(), QueryBuilder.bindMarker() ) ) ) );
    }

    private void putPropertyTypeUpdateStatement( FullQualifiedName propertyTypeFqn ) {
        // The preparation process re-orders the bind markers. Below they are set according to the order that they get
        // mapped to
        propertyTypeUpdateStatements.put( propertyTypeFqn,
                session.prepare( QueryBuilder
                        .update( keyspace, TableType.property_.name() + getTypenameForPropertyType( propertyTypeFqn ) )
                        .with( QueryBuilder.appendAll( CommonColumns.SYNCIDS.cql(),
                                QueryBuilder.bindMarker() ) )
                        .where( QueryBuilder.eq( CommonColumns.OBJECTID.cql(), QueryBuilder.bindMarker() ) )
                        .and( QueryBuilder.eq( CommonColumns.ACLID.cql(), QueryBuilder.bindMarker() ) )
                        .and( QueryBuilder.eq( CommonColumns.VALUE.cql(), QueryBuilder.bindMarker() ) ) ) );

    }

    private void putPropertyIndexUpdateStatement( FullQualifiedName propertyTypeFqn ) {
        // The preparation process re-orders the bind markers. Below they are set according to the order that they get
        // mapped to
        propertyIndexUpdateStatements.put( propertyTypeFqn,
                session.prepare( QueryBuilder
                        .update( keyspace, TableType.index_.name() + getTypenameForPropertyType( propertyTypeFqn ) )
                        .with( QueryBuilder.appendAll( CommonColumns.SYNCIDS.cql(),
                                QueryBuilder.bindMarker() ) )
                        .where( QueryBuilder.eq( CommonColumns.VALUE.cql(), QueryBuilder.bindMarker() ) )
                        .and( QueryBuilder.eq( CommonColumns.ACLID.cql(), QueryBuilder.bindMarker() ) )
                        .and( QueryBuilder.eq( CommonColumns.OBJECTID.cql(), QueryBuilder.bindMarker() ) ) ) );
    }

    private static void createKeyspaceSparksIfNotExists( String keyspace, Session session ) {
        session.execute( Queries.CREATE_KEYSPACE );
    }

    private static void createSchemasTableIfNotExists( String keyspace, Session session ) {
        session.execute( Queries.getCreateSchemasTableQuery( keyspace ) );
    }

    private static void createEntitySetsTableIfNotExists( String keyspace, Session session ) {
        session.execute( Queries.getCreateEntitySetsTable( keyspace ) );
        session.execute( Queries.CREATE_INDEX_ON_NAME );
    }

    private static void createEntityTypesTableIfNotExists( String keyspace, Session session ) {
        session.execute( Queries.getCreateEntityTypesTableQuery( keyspace ) );
    }

    private void createPropertyTypesTableIfNotExists( String keyspace, Session session ) {
        session.execute( Queries.getCreatePropertyTypesTableQuery( keyspace ) );
    }

    private void createFullQualifiedNameLookupTableIfNotExists( String keyspace, Session session ) {
        session.execute( Queries.getCreateFqnLookupTableQuery( keyspace ) );
    }
}
