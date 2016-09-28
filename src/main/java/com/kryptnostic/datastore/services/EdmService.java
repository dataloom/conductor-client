package com.kryptnostic.datastore.services;

import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.stream.Stream;

import org.apache.commons.lang3.StringUtils;
import org.apache.olingo.commons.api.data.Entity;
import org.apache.olingo.commons.api.edm.FullQualifiedName;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.datastax.driver.core.PreparedStatement;
import com.datastax.driver.core.ResultSetFuture;
import com.datastax.driver.core.Session;
import com.datastax.driver.mapping.Mapper;
import com.datastax.driver.mapping.MappingManager;
import com.datastax.driver.mapping.Result;
import com.google.common.base.Preconditions;
import com.google.common.base.Predicates;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;
import com.google.common.collect.Maps;
import com.google.common.collect.Sets;
import com.google.common.util.concurrent.Futures;
import com.kryptnostic.conductor.rpc.UUIDs.ACLs;
import com.kryptnostic.conductor.rpc.odata.EntitySet;
import com.kryptnostic.conductor.rpc.odata.EntityType;
import com.kryptnostic.conductor.rpc.odata.PropertyType;
import com.kryptnostic.conductor.rpc.odata.Schema;
import com.kryptnostic.datastore.services.GetSchemasRequest.TypeDetails;
import com.kryptnostic.datastore.util.Util;

public class EdmService implements EdmManager {
    private static final Logger logger = LoggerFactory.getLogger( EdmService.class );

    private final Session              session;
    private final Mapper<EntitySet>    entitySetMapper;
    private final Mapper<EntityType>   entityTypeMapper;
    private final Mapper<PropertyType> propertyTypeMapper;

    private final CassandraEdmStore     edmStore;
    private final CassandraTableManager tableManager;

    public EdmService( Session session, MappingManager mappingManager, CassandraTableManager tableManager ) {
        this.session = session;
        this.edmStore = mappingManager.createAccessor( CassandraEdmStore.class );
        this.entitySetMapper = mappingManager.mapper( EntitySet.class );
        this.entityTypeMapper = mappingManager.mapper( EntityType.class );
        this.propertyTypeMapper = mappingManager.mapper( PropertyType.class );
        this.tableManager = tableManager;
        Result<EntityType> objectTypes = edmStore.getEntityTypes();
        List<Schema> schemas = ImmutableList.copyOf( getSchemas() );
        schemas.forEach( schema -> logger.info( "Namespace loaded: {}", schema ) );
        schemas.forEach( tableManager::registerSchema );
        objectTypes.forEach( objectType -> logger.info( "Object read: {}", objectType ) );
    }

    @Override
    public Iterable<Schema> getSchemas() {
        return getSchemas( EnumSet.allOf( TypeDetails.class ) );
    }

    @Override
    public Iterable<Schema> getSchemas( Set<TypeDetails> requestedDetails ) {
        Iterable<UUID> aclIds = ImmutableSet.of( ACLs.EVERYONE_ACL );
        Iterable<Iterable<Schema>> results = Iterables.transform( aclIds, ( UUID aclId ) -> {
            PreparedStatement stmt = tableManager.getAllSchemasStatement( aclId );

            if ( stmt == null ) {
                return null;
            }

            final SchemaDetailsAdapter adapter = new SchemaDetailsAdapter(
                    aclId,
                    entityTypeMapper,
                    propertyTypeMapper,
                    requestedDetails );
            return Iterables.transform( session.execute( stmt.bind() ), adapter );
        } );
        return Iterables.filter( Iterables.concat( results ), Predicates.notNull() );
    }

    @Override
    public Iterable<Schema> getSchemasInNamespace( String namespace, Set<TypeDetails> requestedDetails ) {
        Preconditions.checkArgument( StringUtils.isNotBlank( namespace ), "Namespace cannot be blank." );
        Iterable<UUID> aclIds = ImmutableSet.of( ACLs.EVERYONE_ACL );
        Iterable<Iterable<Schema>> results = Iterables.transform( aclIds, aclId -> {
            PreparedStatement stmt = tableManager.getSchemasInNamespaceStatement( aclId );

            if ( stmt == null ) {
                return null;
            }

            final SchemaDetailsAdapter adapter = new SchemaDetailsAdapter(
                    aclId,
                    entityTypeMapper,
                    propertyTypeMapper,
                    requestedDetails );

            return Iterables.transform( session.execute( stmt.bind( namespace ) ), adapter );
        } );
        ;
        return Iterables.filter( Iterables.concat( results ), Predicates.notNull() );
    }

    @Override
    public Iterable<Schema> getSchema( String namespace, String name, Set<TypeDetails> requestedDetails ) {
        Iterable<UUID> aclIds = ImmutableSet.of( ACLs.EVERYONE_ACL );
        Iterable<Iterable<Schema>> results = Iterables.transform( aclIds, aclId -> {
            PreparedStatement stmt = tableManager.getSchemaStatement( aclId );

            if ( stmt == null ) {
                return null;
            }

            final SchemaDetailsAdapter adapter = new SchemaDetailsAdapter(
                    aclId,
                    entityTypeMapper,
                    propertyTypeMapper,
                    requestedDetails );

            return Iterables.transform( session.execute( stmt.bind( namespace, name ) ), adapter );
        } );

        return Iterables.filter( Iterables.concat( results ), Predicates.notNull() );
    }

    /*
     * (non-Javadoc)
     * @see com.kryptnostic.types.services.EdmManager#updateObjectType(com.kryptnostic.types.ObjectType)
     */
    @Override
    public void upsertEntityType( EntityType entityType ) {
        // This call will fail if the typename has already been set for the entity.
        ensureValidEntityType( entityType );
        String typename = tableManager.getTypenameForEntityType( entityType );
        entityType.setTypename( typename );
        entityTypeMapper.save( entityType );
        tableManager.updateEntityTypeLookupTable( entityType );
    }

    /*
     * (non-Javadoc)
     * @see com.kryptnostic.types.services.EdmManager#createPropertyType(com.kryptnostic.types.PropertyType)
     */
    @Override
    public void upsertPropertyType( PropertyType propertyType ) {
        // Create or retrieve it's typename.
        String typename = tableManager.getTypenameForPropertyType( propertyType );
        propertyType.setTypename( typename );
        propertyTypeMapper.save( propertyType );
        tableManager.updatePropertyTypeLookupTable( propertyType );
    }

    /*
     * (non-Javadoc)
     * @see com.kryptnostic.types.services.EdmManager#createNamespace(com.kryptnostic.types.Namespace)
     */
    @Override
    public void upsertSchema( Schema schema ) {
        session.execute( tableManager.getSchemaUpsertStatement( schema.getAclId() ).bind( schema.getNamespace(),
                schema.getName(),
                schema.getEntityTypeFqns() ) );
    }

    @Override
    public boolean createPropertyType( PropertyType propertyType ) {
        /*
         * We retrieve or create the typename for the property. If the property already exists then lightweight
         * transaction will fail and return value will be correctly set.
         */
        String typename = tableManager.getTypenameForPropertyType( propertyType );
        boolean propertyCreated = false;
        if ( StringUtils.isBlank( typename ) ) {

            propertyType.setTypename( CassandraTableManager.generateTypename() );
            propertyCreated = Util.wasLightweightTransactionApplied(
                    edmStore.createPropertyTypeIfNotExists( propertyType.getNamespace(),
                            propertyType.getName(),
                            propertyType.getTypename(),
                            propertyType.getDatatype(),
                            propertyType.getMultiplicity() ) );
        }

        if ( propertyCreated ) {
            tableManager.createPropertyTypeTable( propertyType );
            tableManager.insertToPropertyTypeLookupTable( propertyType );
        }

        return propertyCreated;
    }

    @Override
    public boolean createSchema( String namespace, String name, UUID aclId, Set<FullQualifiedName> entityTypes ) {
        tableManager.createSchemaTableForAclId( aclId );
        return Util.wasLightweightTransactionApplied(
                session.execute(
                        tableManager.getSchemaInsertStatement( aclId ).bind( namespace, name, entityTypes ) ) );
    }

    @Override
    public void deleteEntityType( FullQualifiedName entityTypeFqn ) {
        EntityType entityType = entityTypeMapper.get( entityTypeFqn.getNamespace(), entityTypeFqn.getName() );
        tableManager.deleteFromEntityTypeLookupTable( entityType );
        entityTypeMapper.delete( entityType );
    }

    @Override
    public void deletePropertyType( FullQualifiedName propertyTypeFqn ) {
        PropertyType propertyType = propertyTypeMapper
                .get( propertyTypeFqn.getNamespace(), propertyTypeFqn.getName() );
        tableManager.deleteFromPropertyTypeLookupTable( propertyType );
        propertyTypeMapper.delete( propertyType );
    }

    @Override
    public void deleteSchema( Schema namespaces ) {
        // TODO: Implement delete schema
    }

    @Override
    public void addEntityTypesToSchema( String namespace, String name, Set<FullQualifiedName> entityTypes ) {
        for ( UUID aclId : AclContextService.getCurrentContextAclIds() ) {
            session.executeAsync(
                    tableManager.getSchemaAddEntityTypeStatement( aclId ).bind( entityTypes, namespace, name ) );
        }
    }

    @Override
    public void removeEntityTypesFromSchema( String namespace, String name, Set<FullQualifiedName> entityTypes ) {
        for ( UUID aclId : AclContextService.getCurrentContextAclIds() ) {
            session.executeAsync(
                    tableManager.getSchemaRemoveEntityTypeStatement( aclId ).bind( entityTypes, namespace, name ) );
        }
    }

    @Override
    public boolean createEntityType(
            EntityType entityType ) {
        boolean entityCreated = false;
        // Make sure entity type is valid
        ensureValidEntityType( entityType );

        // Make sure that this type doesn't already exist
        String typename = tableManager.getTypenameForEntityType( entityType );

        if ( StringUtils.isBlank( typename ) ) {
            // Generate the typename for this type
            typename = CassandraTableManager.generateTypename();
            entityType.setTypename( typename );

            entityCreated = Util.wasLightweightTransactionApplied(
                    edmStore.createEntityTypeIfNotExists( entityType.getNamespace(),
                            entityType.getName(),
                            entityType.getTypename(),
                            entityType.getKey(),
                            entityType.getProperties() ) );
        }

        // Only create entity table if insert transaction succeeded.
        if ( entityCreated ) {
            tableManager.createEntityTypeTable( entityType,
                    Maps.asMap( entityType.getKey(),
                            fqn -> getPropertyType( fqn ) ) );
            tableManager.insertToEntityTypeLookupTable( entityType );
        }
        return entityCreated;
    }

    private void ensureValidEntityType( EntityType entityType ) {
        Preconditions.checkArgument( propertiesExist( entityType.getProperties() )
                && entityType.getProperties().containsAll( entityType.getKey() ), "Invalid entity provided" );
    }

    private boolean propertiesExist( Set<FullQualifiedName> properties ) {
        Stream<ResultSetFuture> futures = properties.parallelStream()
                .map( prop -> session
                        .executeAsync(
                                tableManager.getCountPropertyStatement().bind( prop.getNamespace(),
                                        prop.getName() ) ) );
        // Cause Java 8
        try {

            return Futures.allAsList( (Iterable<ResultSetFuture>) futures::iterator ).get().stream()
                    .map( rs -> rs.one().getLong( "count" ) )
                    .noneMatch( count -> count == 0 );
        } catch ( InterruptedException | ExecutionException e ) {
            logger.error( "Unable to verify all properties exist." );
            return false;
        }
    }

    @Override
    public void upsertEntitySet( EntitySet entitySet ) {
        entitySetMapper.save( entitySet );
    }

    @Override
    public void deleteEntitySet( EntitySet entitySet ) {
        entitySetMapper.delete( entitySet );
        //TODO: no need to delete row of EntitySetsTable?
    }

    @Override
    public boolean assignEntityToEntitySet( UUID entityId, String name ) {
        String typename = tableManager.getTypenameForEntityId( entityId );
        if ( StringUtils.isBlank( typename ) ) {
            return false;
        }
        if ( !isExistingEntitySet( typename, name ) ) {
            return false;
        }
        return tableManager.assignEntityToEntitySet( entityId, typename, name );
    }

    @Override
    public boolean assignEntityToEntitySet( UUID entityId, EntitySet es ) {
        String typenameForEntity = tableManager.getTypenameForEntityId( entityId );
        if ( StringUtils.isBlank( typenameForEntity ) ) {
            return false;
        }
        if ( !isExistingEntitySet( typenameForEntity, es.getName() ) ) {
            return false;
        }
        return tableManager.assignEntityToEntitySet( entityId, es );
    }

    @Override
    public boolean createEntitySet( FullQualifiedName type, String name, String title ) {
        String typename = tableManager.getTypenameForEntityType( type );
        return createEntitySet( typename, name, title );
    }

    @Override
    public boolean createEntitySet( String typename, String name, String title ) {
        if ( isExistingEntitySet( typename, name ) ) {
            return false;
        }
        return Util.wasLightweightTransactionApplied( edmStore.createEntitySetIfNotExists( typename, name, title ) );
    }

    @Override
    public boolean createEntitySet( EntitySet entitySet ) {
        if ( StringUtils.isNotBlank( entitySet.getTypename() ) ) {
            return false;
        }
        String typename = tableManager.getTypenameForEntityType( entitySet.getType() );
        System.out.println( "typename upon entity set creation: " + typename );
        entitySet.setTypename( typename );
        return createEntitySet( typename, entitySet.getName(), entitySet.getTitle() );
    }

    @Override
    public EntityType getEntityType( FullQualifiedName fqn ) {
        return getEntityType( fqn.getNamespace(), fqn.getName() );
    }

    public Iterable<EntityType> getEntityTypes() {
        return edmStore.getEntityTypes().all();
    }

    @Override
    public EntityType getEntityType( String namespace, String name ) {
        return entityTypeMapper.get( namespace, name );
    }

    public EntitySet getEntitySet( FullQualifiedName type, String name ) {
        return entitySetMapper.get( type, name );
    }

    public EntitySet getEntitySet( String name ) {
        return edmStore.getEntitySet( name );
    }

    @Override
    public Iterable<EntitySet> getEntitySets() {
        return edmStore.getEntitySets().all();
    }

    @Override
    public PropertyType getPropertyType( FullQualifiedName propertyType ) {
        return propertyTypeMapper.get( propertyType.getNamespace(), propertyType.getName() );
    }

    @Override
    public FullQualifiedName getPropertyTypeFullQualifiedName( String typename ) {
        return tableManager.getPropertyTypeForTypename( typename );
    }

    @Override
    public FullQualifiedName getEntityTypeFullQualifiedName( String typename ) {
        return tableManager.getEntityTypeForTypename( typename );
    }

    @Override
    public EntityDataModel getEntityDataModel() {
        Iterable<Schema> schemas = getSchemas();
        final Set<EntityType> entityTypes = Sets.newHashSet();
        final Set<PropertyType> propertyTypes = Sets.newHashSet();
        final Set<EntitySet> entitySets = Sets.newHashSet();
        final Set<String> namespaces = Sets.newHashSet();

        schemas.forEach( schema -> {
            entityTypes.addAll( schema.getEntityTypes() );
            propertyTypes.addAll( schema.getPropertyTypes() );
            schema.getEntityTypes().forEach( entityType -> namespaces.add( entityType.getNamespace() ) );
            schema.getPropertyTypes().forEach( propertyType -> namespaces.add( propertyType.getNamespace() ) );
        } );

        return new EntityDataModel(
                namespaces,
                ImmutableSet.copyOf( schemas ),
                entityTypes,
                propertyTypes,
                entitySets );
    }

    @Override
    public boolean isExistingEntitySet( FullQualifiedName type, String name ) {
        String typename = tableManager.getTypenameForEntityType( type );
        System.out.println( "typename upon entity set creation: " + typename );
        if ( StringUtils.isBlank( typename ) ) {
            return false;
        }
        return isExistingEntitySet( typename, name );
    }

    public boolean isExistingEntitySet( String typename, String name ) {
        return Util
                .isCountNonZero( session.execute( tableManager.getCountEntitySetsStatement().bind( typename, name ) ) );
    }

}
