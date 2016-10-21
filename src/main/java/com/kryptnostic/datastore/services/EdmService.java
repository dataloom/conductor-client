package com.kryptnostic.datastore.services;

import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import javax.inject.Inject;

import org.apache.commons.lang3.StringUtils;
import org.apache.olingo.commons.api.edm.FullQualifiedName;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.junit.Assert;

import com.datastax.driver.core.PreparedStatement;
import com.datastax.driver.core.ResultSetFuture;
import com.datastax.driver.core.Session;
import com.datastax.driver.mapping.Mapper;
import com.datastax.driver.mapping.MappingManager;
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
import com.kryptnostic.datastore.Permission;
import com.kryptnostic.datastore.cassandra.CommonColumns;
import com.kryptnostic.datastore.services.requests.GetSchemasRequest.TypeDetails;
import com.kryptnostic.datastore.util.Util;
import com.kryptnostic.instrumentation.v1.exceptions.types.BadRequestException;

public class EdmService implements EdmManager {
    private static final Logger logger = LoggerFactory.getLogger( EdmService.class );

	/** 
	 * Being of debug
	 */
	private UUID                   currentId;
	@Override
	public void setCurrentUserIdForDebug( UUID currentId ){
		this.currentId = currentId;
	}
	/**
	 * End of debug
	 */
    
    private final Session              session;
    private final Mapper<EntitySet>    entitySetMapper;
    private final Mapper<EntityType>   entityTypeMapper;
    private final Mapper<PropertyType> propertyTypeMapper;

    private final CassandraEdmStore     edmStore;
    private final CassandraTableManager tableManager;
    private final PermissionsService    permissionsService;

    public EdmService( Session session, MappingManager mappingManager, CassandraTableManager tableManager, PermissionsService permissionsService ) {
        this.session = session;
        this.edmStore = mappingManager.createAccessor( CassandraEdmStore.class );
        this.entitySetMapper = mappingManager.mapper( EntitySet.class );
        this.entityTypeMapper = mappingManager.mapper( EntityType.class );
        this.propertyTypeMapper = mappingManager.mapper( PropertyType.class );
        this.tableManager = tableManager;
        this.permissionsService = permissionsService;
        List<EntityType> objectTypes = edmStore.getEntityTypes().all();
        List<Schema> schemas = ImmutableList.copyOf( getSchemas() );
        // Temp comment out the following two lines to avoid "Schema is out of sync." crash
        // and NPE for the PreparedStatement. Need to engineer it.
//        schemas.forEach( schema -> logger.info( "Namespace loaded: {}", schema ) );
//        schemas.forEach( tableManager::registerSchema );
        objectTypes.forEach( objectType -> logger.info( "Object read: {}", objectType ) );
        objectTypes.forEach( tableManager::registerEntityTypesAndAssociatedPropertyTypes );
    }

    @Override
    public Iterable<Schema> getSchemas() {
        return getSchemas( EnumSet.allOf( TypeDetails.class ) );
    }

    @Override
    public Iterable<Schema> getSchemas( Set<TypeDetails> requestedDetails ) {
        PreparedStatement stmt = tableManager.getAllSchemasStatement( ACLs.EVERYONE_ACL );

        if ( stmt == null ) {
            return null;
        }

        final SchemaDetailsAdapter adapter = new SchemaDetailsAdapter(
                currentId,
                tableManager,
                entityTypeMapper,
                propertyTypeMapper,
                permissionsService,
                requestedDetails );
        
        Iterable<Schema> results = Iterables.transform( session.execute( stmt.bind() ), adapter );
            
        return Iterables.filter( Iterables.concat( results ), Predicates.notNull() );
    }

    @Override
    public Iterable<Schema> getSchemasInNamespace( String namespace, Set<TypeDetails> requestedDetails ) {
        Preconditions.checkArgument( StringUtils.isNotBlank( namespace ), "Namespace cannot be blank." );
        
        PreparedStatement stmt = tableManager.getSchemasInNamespaceStatement( ACLs.EVERYONE_ACL );

        if ( stmt == null ) {
            return null;
        }

        final SchemaDetailsAdapter adapter = new SchemaDetailsAdapter(
                currentId,
                tableManager,
                entityTypeMapper,
                propertyTypeMapper,
                permissionsService,
                requestedDetails );
        
        Iterable<Schema> results = Iterables.transform( session.execute( stmt.bind( namespace) ), adapter );

        return Iterables.filter( Iterables.concat( results ), Predicates.notNull() );
    }

    @Override
    public Iterable<Schema> getSchema( String namespace, String name, Set<TypeDetails> requestedDetails ) {
        PreparedStatement stmt = tableManager.getSchemaStatement( ACLs.EVERYONE_ACL );

        if ( stmt == null ) {
            return null;
        }

        final SchemaDetailsAdapter adapter = new SchemaDetailsAdapter(
                currentId,
                tableManager,
                entityTypeMapper,
                propertyTypeMapper,
                permissionsService,
                requestedDetails );

        Iterable<Schema> results = Iterables.transform( session.execute( stmt.bind( namespace, name ) ), adapter ); 

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
        if( checkEntityTypeExists( entityType.getFullQualifiedName() ) &&
        	permissionsService.checkUserHasPermissionsOnEntityType( currentId, entityType.getFullQualifiedName(), Permission.ALTER )
        		){
        	//Retrieve database record of entityType
        	EntityType dbRecord = getEntityType( entityType.getFullQualifiedName() );
        	//Retrieve properties known to user
        	Set<FullQualifiedName> knownPropertyTypesInEntityType = dbRecord.getViewableProperties();
        	Set<FullQualifiedName> inertPropertyTypesInEntityType = Sets.intersection(knownPropertyTypesInEntityType, entityType.getProperties());
        	Set<FullQualifiedName> removablePropertyTypesInEntityType = Sets.difference(knownPropertyTypesInEntityType, inertPropertyTypesInEntityType);
        	Set<FullQualifiedName> newPropertyTypesInEntityType = Sets.difference(entityType.getProperties(), inertPropertyTypesInEntityType);       	
        	//Update properties and their permissions accordingly
        	  //Remove the removable property types in database properly; this step takes care of removal of permissions
        	removePropertyTypesFromEntityType(dbRecord, removablePropertyTypesInEntityType, true);
        	  //Give owner rights for the new property types
        	newPropertyTypesInEntityType.forEach( propertyTypeFqn ->
                         permissionsService.setPermissionsForPropertyTypeInEntityType( currentId, entityType.getFullQualifiedName(), propertyTypeFqn, ImmutableSet.of(Permission.OWNER) )	        
        			);
            //Update entityType
        	entityType.addProperties( newPropertyTypesInEntityType );
        	entityTypeMapper.save( entityType );

        } else {
        	createEntityType( entityType, true, true );
        }
    }

    /*
     * (non-Javadoc)
     * @see com.kryptnostic.types.services.EdmManager#createPropertyType(com.kryptnostic.types.PropertyType)
     * update propertyType (and return true upon success) if exists, return false otherwise
     */
    @Override
    public void upsertPropertyType( PropertyType propertyType ) {
    	ensureValidPropertyType( propertyType );
    	if( checkPropertyTypeExists( propertyType.getFullQualifiedName() ) &&
    			permissionsService.checkUserHasPermissionsOnPropertyType( currentId, propertyType.getFullQualifiedName(), Permission.ALTER) ){
        	Util.wasLightweightTransactionApplied(
                    edmStore.updatePropertyTypeIfExists(
                            propertyType.getDatatype(),
                            propertyType.getMultiplicity(),
                            propertyType.getSchemas(),
                            propertyType.getNamespace(),
                            propertyType.getName() ) );    		
    	} else {
    		createPropertyType( propertyType, true, true );
    	}
    }

    /*
     * (non-Javadoc)
     * @see com.kryptnostic.types.services.EdmManager#createNamespace(com.kryptnostic.types.Namespace)
     */
    @Override
    public void upsertSchema( Schema schema ) {
        session.execute( tableManager.getSchemaUpsertStatement( schema.getAclId() ).bind( schema.getNamespace(),
                schema.getName(),
                schema.getEntityTypeFqns(),
                schema.getPropertyTypeFqns() ) );
    }

    @Override
    public boolean createPropertyType( PropertyType propertyType ) {
    	ensureValidPropertyType( propertyType );
        return createPropertyType( propertyType,
        		true,
        		!checkPropertyTypeExists( propertyType.getFullQualifiedName() )
        		);
    }
    
    private boolean createPropertyType( PropertyType propertyType, boolean checkedValid, boolean doesNotExist ){
        /*
         * We retrieve or create the typename for the property. If the property already exists then lightweight
         * transaction will fail and return value will be correctly set.
         */
    	/**
    	 * Refactored by Ho Chung, so that upsertPropertyType won't do duplicate checks.
    	 * checkedValid means that ensureValidPropertyType is run; error throwing should be done there.
    	 */
    	if( checkedValid && doesNotExist ){
            boolean propertyCreated = false;
            
            propertyType.setTypename( CassandraTableManager.generateTypename() );
            propertyCreated = Util.wasLightweightTransactionApplied(
                    edmStore.createPropertyTypeIfNotExists( propertyType.getNamespace(),
                            propertyType.getName(),
                            propertyType.getTypename(),
                            propertyType.getDatatype(),
                            propertyType.getMultiplicity(),
                            propertyType.getSchemas() ) );
            
            if ( propertyCreated ) {
                propertyType.getSchemas()
        		.forEach( 
        					schemaFqn -> addPropertyTypesToSchema( schemaFqn.getNamespace(), schemaFqn.getName(), ImmutableSet.of(propertyType.getFullQualifiedName()) ) 
        				);

                tableManager.createPropertyTypeTable( propertyType );
                tableManager.insertToPropertyTypeLookupTable( propertyType );
                permissionsService.setPermissionsForPropertyType( currentId, propertyType.getFullQualifiedName(), ImmutableSet.of(Permission.OWNER) );
            }

            return propertyCreated;
    	}
    	return false;
    }

    @Override
    public boolean createSchema( String namespace, String name, UUID aclId, Set<FullQualifiedName> entityTypes, Set<FullQualifiedName> propertyTypes ) {
        tableManager.createSchemaTableForAclId( aclId );
        
        entityTypes.stream()
		.forEach( entityTypeFqn ->
					tableManager.entityTypeAddSchema(entityTypeFqn, namespace, name)	
				);
        
        propertyTypes.stream()
		.forEach( propertyTypeFqn ->
					tableManager.propertyTypeAddSchema(propertyTypeFqn, namespace, name)	
				);
        
        return Util.wasLightweightTransactionApplied(
                session.execute(
                        tableManager.getSchemaInsertStatement( aclId ).bind( namespace, name, entityTypes, propertyTypes ) ) );
    }
    
    @Override
    public boolean createSchema( String namespace, String name, UUID aclId, Set<FullQualifiedName> entityTypes) {        
        Set<FullQualifiedName> propertyTypes = entityTypes.stream()
        		.map(entityTypeFqn -> entityTypeMapper.get( entityTypeFqn.getNamespace(), entityTypeFqn.getName() ) )
        		.map(entityType -> entityType.getProperties() )
        		.reduce((left, right) -> {
        			left.addAll(right);
        			return left;
        		}).get();
       
        return createSchema( namespace, name, aclId, entityTypes, propertyTypes);
    }

    @Override
    public void deleteEntityType( FullQualifiedName entityTypeFqn ) {
        EntityType entityType = entityTypeMapper.get( entityTypeFqn.getNamespace(), entityTypeFqn.getName() );
        
        if( permissionsService.checkUserHasPermissionsOnEntityType( currentId, entityTypeFqn, Permission.OWNER )){
            entityType.getSchemas().forEach( 
            		schemaFqn -> removeEntityTypesFromSchema( schemaFqn.getNamespace(), schemaFqn.getName(), ImmutableSet.of( entityTypeFqn) )
            		);
            //TODO: remove property types from schema using reference counting
            tableManager.deleteFromEntityTypeLookupTable( entityType );
            
            permissionsService.removePermissionsForEntityType( entityTypeFqn);
            permissionsService.removePermissionsForPropertyTypeInEntityType( entityTypeFqn );
            permissionsService.removeFromEntityTypesAlterRightsTable( entityTypeFqn );
            
            entityTypeMapper.delete( entityType );
        }
    }

    @Override
    public void deletePropertyType( FullQualifiedName propertyTypeFqn ) {
        PropertyType propertyType = propertyTypeMapper
                .get( propertyTypeFqn.getNamespace(), propertyTypeFqn.getName() );

        if( permissionsService.checkUserHasPermissionsOnPropertyType( currentId, propertyTypeFqn, Permission.OWNER )){
            propertyType.getSchemas().forEach( schemaFqn -> removePropertyTypesFromSchema(schemaFqn.getNamespace(), schemaFqn.getName(), ImmutableSet.of(propertyTypeFqn) ) );
            edmStore.getEntityTypes().all().forEach( entityType -> {
            	removePropertyTypesFromEntityType(entityType, ImmutableSet.of(propertyTypeFqn) );
            	tableManager.deleteFromPropertyTypeInEntityTypesAclsTable(entityType.getFullQualifiedName(), propertyTypeFqn);
            });
            
            tableManager.deleteFromPropertyTypeLookupTable( propertyType );
            
            permissionsService.removePermissionsForPropertyType( propertyTypeFqn );
            
            propertyTypeMapper.delete( propertyType );
        }
    }

    @Override
    public void deleteSchema( Schema namespaces ) {
        // TODO: Implement delete schema
    }

    @Override
    public void addEntityTypesToSchema( String namespace, String name, Set<FullQualifiedName> entityTypes ) {
        Set<FullQualifiedName> propertyTypes = new HashSet<>();
        
        entityTypes.stream()
        		.map(entityTypeFqn -> entityTypeMapper.get( entityTypeFqn.getNamespace(), entityTypeFqn.getName() ) )
        		.forEach(entityType -> {
        			//Get all properties for each entity type
        			propertyTypes.addAll( entityType.getProperties() );
        			//Update Schema column for each Entity Type
        			tableManager.entityTypeAddSchema( entityType, namespace, name);
        		});

        addPropertyTypesToSchema( namespace, name, propertyTypes );
        
        for ( UUID aclId : AclContextService.getCurrentContextAclIds() ) {
            session.executeAsync(
                    tableManager.getSchemaAddEntityTypeStatement( aclId ).bind( entityTypes, propertyTypes, namespace, name ) );
        }
    }

    @Override
    public void removeEntityTypesFromSchema( String namespace, String name, Set<FullQualifiedName> entityTypes ) {
    	//TODO: propertyTypes not removed From Schema table when Entity Types are removed. Need reference counting on propertyTypes to do so.
        Set<FullQualifiedName> propertyTypes = new HashSet<>();
        
        entityTypes.stream()
        		.map(entityTypeFqn -> entityTypeMapper.get( entityTypeFqn.getNamespace(), entityTypeFqn.getName() ) )
        		.forEach(entityType -> {
        			//Get all properties for each entity type
        			propertyTypes.addAll( entityType.getProperties() );
        			//Update Schema column for each Entity Type
        			tableManager.entityTypeRemoveSchema( entityType, namespace, name);
        		});
        
//        removePropertyTypesFromSchema( namespace, name, propertyTypes );
        propertyTypes.stream()
				.forEach(propertyTypeFqn ->
					tableManager.propertyTypeRemoveSchema( propertyTypeFqn, namespace, name )
				);
        
        for ( UUID aclId : AclContextService.getCurrentContextAclIds() ) {
            session.executeAsync(
                    tableManager.getSchemaRemoveEntityTypeStatement( aclId ).bind( entityTypes, namespace, name ) );
        }
    }

    @Override
    public boolean createEntityType(
            EntityType entityType ) {
        // Make sure entity type is valid
        ensureValidEntityType( entityType );
        
        return createEntityType( entityType,
        		true,
        		!checkEntityTypeExists( entityType.getFullQualifiedName() )
        		);
        
    }
    
    private boolean createEntityType(EntityType entityType, boolean checkedValid, boolean doesNotExist ) {
    	/**
    	 * Refactored by Ho Chung, so that upsertEntityType won't do duplicate checks.
    	 * checkedValid means that ensureValidEntityType is run; error throwing should be done there.
    	 */
    	if( checkedValid && doesNotExist ){
            boolean entityCreated = false;
            // Generate the typename for this type
            String typename = CassandraTableManager.generateTypename();
            entityType.setTypename( typename );

            entityCreated = Util.wasLightweightTransactionApplied(
                    edmStore.createEntityTypeIfNotExists( entityType.getNamespace(),
                            entityType.getName(),
                            entityType.getTypename(),
                            entityType.getKey(),
                            entityType.getProperties(), 
                            entityType.getSchemas() ) );
                
            entityType.getSchemas()
            		.forEach( 
             					schemaFqn -> addEntityTypesToSchema( schemaFqn.getNamespace(), schemaFqn.getName(), ImmutableSet.of(entityType.getFullQualifiedName()) ) 
              				);

            // Only create entity table if insert transaction succeeded.
            if ( entityCreated ) {
                tableManager.createEntityTypeTable( entityType,
                        Maps.asMap( entityType.getKey(),
                                fqn -> getPropertyType( fqn ) ) );
                tableManager.insertToEntityTypeLookupTable( entityType );
                //Acl
                permissionsService.setPermissionsForEntityType( currentId, entityType.getFullQualifiedName(), ImmutableSet.of(Permission.OWNER) );
                permissionsService.addToEntityTypesAlterRightsTable( currentId, entityType.getFullQualifiedName() );
                entityType.getProperties().forEach( propertyTypeFqn -> {
                    permissionsService.setPermissionsForPropertyTypeInEntityType( currentId, entityType.getFullQualifiedName(), propertyTypeFqn, ImmutableSet.of(Permission.OWNER) );	
                });
            }
            return entityCreated;
    	}
    	return false;
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
    public EntityType getEntityType( FullQualifiedName entityTypeFqn ) {
    	if( permissionsService.checkUserHasPermissionsOnEntityType( currentId, entityTypeFqn, Permission.DISCOVER ) ){
    		EntityType entityType = entityTypeMapper.get( entityTypeFqn.getNamespace(), entityTypeFqn.getName() );
    	    Preconditions.checkNotNull( entityType, "Entity type does not exist" );
    	    
    	    return EdmDetailsAdapter.setViewableDetails( tableManager, permissionsService, entityType );
    	}
    	return null;
    }

    public Iterable<EntityType> getEntityTypes() {
    	if( currentId != null){
        return edmStore.getEntityTypes().all().stream()
        		.filter( entityType -> permissionsService.checkUserHasPermissionsOnEntityType( currentId, entityType.getFullQualifiedName(),
        				Permission.DISCOVER ) )
        		.map( entityType -> EdmDetailsAdapter.setViewableDetails(tableManager, permissionsService, entityType) )
        		.collect( Collectors.toList() );
    	} else {
    		// Should only be called when starting up
            return edmStore.getEntityTypes().all();
    	}
    }

    @Override
    public EntityType getEntityType( String namespace, String name ) {
        return getEntityType( new FullQualifiedName(namespace, name) );
    }

    public EntitySet getEntitySet( FullQualifiedName type, String name ) {
        return EdmDetailsAdapter.setEntitySetTypename( tableManager, entitySetMapper.get( type, name ) );
    }

    public EntitySet getEntitySet( String name ) {
        return EdmDetailsAdapter.setEntitySetTypename( tableManager, edmStore.getEntitySet( name ) );
    }

    @Override
    public Iterable<EntitySet> getEntitySets() {
        return edmStore.getEntitySets().all().stream()
        		.map( entitySet -> EdmDetailsAdapter.setEntitySetTypename( tableManager, entitySet ) )
        		.collect( Collectors.toList() );
    }

    @Override
    public PropertyType getPropertyType( FullQualifiedName propertyType ) {
    	if( permissionsService.checkUserHasPermissionsOnPropertyType( currentId, propertyType, Permission.DISCOVER ) ){
    	    return Preconditions.checkNotNull( 
    	    		propertyTypeMapper.get( propertyType.getNamespace(), propertyType.getName() )
    	    		, "Property type does not exist" );
    	}
    	return null;
    }

    @Override
    public Iterable<PropertyType> getPropertyTypesInNamespace( String namespace ) {
        return edmStore.getPropertyTypesInNamespace( namespace ).all().stream()
        		.filter( propertyType -> permissionsService.checkUserHasPermissionsOnPropertyType( 
        				currentId, propertyType.getFullQualifiedName(), Permission.DISCOVER ) )
        		.collect( Collectors.toList() );
    }

    @Override
    public Iterable<PropertyType> getPropertyTypes() {
        return edmStore.getPropertyTypes().all().stream()
        		.filter( propertyType -> permissionsService.checkUserHasPermissionsOnPropertyType( 
        				currentId, propertyType.getFullQualifiedName(), Permission.DISCOVER ) )
        		.collect( Collectors.toList() );
    }

    @Override
    public FullQualifiedName getPropertyTypeFullQualifiedName( String typename ) {
    	FullQualifiedName fqn = tableManager.getPropertyTypeForTypename( typename );
    	if ( fqn != null && permissionsService.checkUserHasPermissionsOnPropertyType( 
    			currentId, fqn, Permission.DISCOVER ) ){
    		return fqn;
    	}
        return null;
    }

    @Override
    public FullQualifiedName getEntityTypeFullQualifiedName( String typename ) {
    	FullQualifiedName fqn = tableManager.getEntityTypeForTypename( typename );
    	if ( fqn != null && permissionsService.checkUserHasPermissionsOnEntityType( currentId, fqn, Permission.DISCOVER ) ){
    		return fqn;
    	}
        return null;
    }

    @Override
    public EntityDataModel getEntityDataModel() {
        Iterable<Schema> schemas = getSchemas();
        Iterable<EntityType> entityTypes = getEntityTypes();
        Iterable<PropertyType> propertyTypes = getPropertyTypes();
        Iterable<EntitySet> entitySets = getEntitySets();
        final Set<String> namespaces = Sets.newHashSet();
        
        entityTypes.forEach( entityType -> namespaces.add( entityType.getNamespace() ));
        propertyTypes.forEach( propertyType -> namespaces.add( propertyType.getNamespace() ));

        return new EntityDataModel(
                namespaces,
                schemas,
                entityTypes,
                propertyTypes,
                entitySets);
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

	@Override
	public void addPropertyTypesToEntityType(String namespace, String name, Set<FullQualifiedName> properties) {
		EntityType entityType;
		FullQualifiedName entityTypeFqn = new FullQualifiedName( namespace, name );
		
		try{
	    	entityType = getEntityType( namespace, name );	    	
	        Preconditions.checkArgument( checkPropertyTypesExist( properties ), "Some properties do not exist." );
		} catch ( IllegalArgumentException e ){
			throw new BadRequestException();
		}
		
		if( permissionsService.checkUserHasPermissionsOnEntityType( currentId, entityTypeFqn, Permission.ALTER) ){
		    Set<FullQualifiedName> newProperties = Sets.difference( properties, entityType.getViewableProperties() );
		    entityType.addProperties( newProperties );
	        edmStore.updateExistingEntityType(
	            entityType.getNamespace(), 
	            entityType.getName(), 
	            entityType.getKey(), 
	            entityType.getProperties()
	        );
	
		    Set<FullQualifiedName> schemas = entityType.getSchemas();
		    schemas.forEach( schemaFqn -> {
		        addPropertyTypesToSchema( schemaFqn.getNamespace(), schemaFqn.getName(), newProperties);
		    });
		    
		    //Acl
		    //"Owner" rights for each new property in entity type
		    //"Discover" rights for existing users with Alter Rights on the entity type
		    Iterable<UUID> usersWithAlterRights = Iterables.transform( 
		    		edmStore.getUsersWithAlterRightsForEntityType( namespace, name ),
		    		row -> row.getUUID( CommonColumns.ACLID.cql() )
		    		);
		    newProperties.forEach( propertyTypeFqn -> {
		    	usersWithAlterRights.forEach( userId -> { 
		    	    if( permissionsService.checkUserHasPermissionsOnPropertyType( userId, propertyTypeFqn, Permission.DISCOVER) ){
		    	        permissionsService.addPermissionsForPropertyTypeInEntityType( 
		    	    	    	userId, entityTypeFqn, propertyTypeFqn, ImmutableSet.of(Permission.DISCOVER) );
		    	    }
		    	});
	    	    permissionsService.addPermissionsForPropertyTypeInEntityType( 
	    	    		currentId, entityTypeFqn, propertyTypeFqn, ImmutableSet.of(Permission.OWNER) );
		    });
		}
	    
	}     
	
	@Override
	public void removePropertyTypesFromEntityType(String namespace, String name, Set<FullQualifiedName> properties) {
	    EntityType entityType = getEntityType( namespace, name );
	    removePropertyTypesFromEntityType( entityType, properties );
	    //TODO: remove property types from Schema should be done via reference counting
	}
	
	@Override
	public void removePropertyTypesFromEntityType(EntityType entityType, Set<FullQualifiedName> properties) {
		try{
	        Preconditions.checkArgument( checkPropertyTypesExist( properties ), "Some properties do not exist." );
	        
	        removePropertyTypesFromEntityType( entityType, properties, true);
		} catch ( IllegalArgumentException e ){
			throw new BadRequestException();
		}
	}
	
	private void removePropertyTypesFromEntityType(EntityType entityType, Set<FullQualifiedName> properties, boolean checkedValid) {
    	/**
    	 * Refactored by Ho Chung, to avoid duplicate checks.
    	 * checkedValid means that entityType is checked to be valid, and property types are checked to exist; error throwing should be done there.
    	 */	
		if( checkedValid ){
			Set<FullQualifiedName> removableProperties = properties.stream().filter( propertyTypeFqn -> 
			    	permissionsService.checkUserHasPermissionsOnPropertyTypeInEntityType(
			    			currentId,
			    			entityType.getFullQualifiedName(), 
			    			propertyTypeFqn, 
			    			Permission.ALTER )
					).collect( Collectors.toSet() );
			
		    entityType.removeProperties( removableProperties );
		    //TODO: Remove properties from Schema, once reference counting is implemented.
		        	           
		    edmStore.updateExistingEntityType(
		           	entityType.getNamespace(), 
		           	entityType.getName(), 
		           	entityType.getKey(), 
		          	entityType.getProperties());
		    
		    //Acl
		    removableProperties.forEach( propertyTypeFqn ->
		    		    permissionsService.removePermissionsForPropertyTypeInEntityType( 
		    		    		entityType.getFullQualifiedName(), propertyTypeFqn )
		    		);

		}
	}
	
	@Override
	public void addPropertyTypesToSchema(String namespace, String name, Set<FullQualifiedName> properties) {
		try{
	        Preconditions.checkArgument( checkPropertyTypesExist( properties ), "Some properties do not exist." );
	        Preconditions.checkArgument( schemaExists( namespace, name ), "Schema does not exist." );
	        
	        properties.stream()
	        		.forEach(
	        				propertyTypeFqn -> tableManager.propertyTypeAddSchema( propertyTypeFqn, namespace, name)
	        		);
	        
	        for ( UUID aclId : AclContextService.getCurrentContextAclIds() ) {
	            session.executeAsync(
	                    tableManager.getSchemaAddPropertyTypeStatement( aclId ).bind( properties, namespace, name ) );
	        }
		} catch ( IllegalArgumentException e ){
			throw new BadRequestException();
		}
	}    
	
	@Override
	public void removePropertyTypesFromSchema(String namespace, String name, Set<FullQualifiedName> properties) {
		try{
	        Preconditions.checkArgument( checkPropertyTypesExist( properties ), "Some properties do not exist." );
	        Preconditions.checkArgument( schemaExists( namespace, name ), "Schema does not exist." );
	        
	        properties.stream()
					.forEach(propertyTypeFqn ->
						tableManager.propertyTypeRemoveSchema( propertyTypeFqn, namespace, name )
					);
	        
	        for ( UUID aclId : AclContextService.getCurrentContextAclIds() ) {
	            session.executeAsync(
	                    tableManager.getSchemaRemovePropertyTypeStatement( aclId ).bind( properties, namespace, name ) );
	        }
		} catch ( IllegalArgumentException e ){
			throw new BadRequestException();
		}
	}
	
	
	/**************
	 * Validation
	 **************/
    private void ensureValidEntityType( EntityType entityType ) {
    	Preconditions.checkNotNull( entityType.getNamespace(), "Namespace for Entity Type is missing");
    	Preconditions.checkNotNull( entityType.getName(), "Name of Entity Type is missing");
    	Preconditions.checkNotNull( entityType.getProperties(), "Property for Entity Type is missing");
    	Preconditions.checkNotNull( entityType.getKey(), "Key for Entity Type is missing");
        Preconditions.checkArgument( checkPropertyTypesExist( entityType.getProperties() )
                && entityType.getProperties().containsAll( entityType.getKey() ), "Invalid Entity Type provided" );
    }
    
    private void ensureValidPropertyType( PropertyType propertyType ) {
    	Preconditions.checkNotNull( propertyType.getNamespace(), "Namespace for Property Type is missing");
    	Preconditions.checkNotNull( propertyType.getName(), "Name of Property Type is missing");
    	Preconditions.checkNotNull( propertyType.getDatatype(), "Datatype of Property Type is missing");
    	Preconditions.checkNotNull( propertyType.getMultiplicity(), "Multiplicity of Property Type is missing");
    }

    private boolean checkPropertyTypesExist( Set<FullQualifiedName> properties ) {
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

    private boolean checkPropertyTypeExists( FullQualifiedName propertyTypeFqn ){
    	//Ho Chung: this check is easier for single property type
        String typename = tableManager.getTypenameForPropertyType( propertyTypeFqn );  
        return StringUtils.isNotBlank( typename );
    }
    
    private boolean checkEntityTypeExists( FullQualifiedName entityTypeFqn ){
        String typename = tableManager.getTypenameForEntityType( entityTypeFqn );  
        return StringUtils.isNotBlank( typename );
    }
    
    private boolean schemaExists( String namespace, String name ) {
    	return schemaExists( new FullQualifiedName(namespace, name) );
    }
    
    private boolean schemaExists( FullQualifiedName schema ) {
        UUID aclId = ACLs.EVERYONE_ACL;
	   	return ( session.execute(
	    		tableManager.getSchemaStatement( aclId ).bind( schema.getNamespace(), schema.getName() ) 
	    		).one()
	    != null);
    }
    
}
