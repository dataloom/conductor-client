package com.kryptnostic.datastore.services;

import java.util.Set;

import org.apache.olingo.commons.api.edm.EdmPrimitiveTypeKind;
import org.apache.olingo.commons.api.edm.FullQualifiedName;

import com.datastax.driver.core.ResultSet;
import com.datastax.driver.mapping.Result;
import com.datastax.driver.mapping.annotations.Accessor;
import com.datastax.driver.mapping.annotations.Param;
import com.datastax.driver.mapping.annotations.Query;
import com.google.common.util.concurrent.ListenableFuture;
import com.kryptnostic.conductor.rpc.odata.EntitySet;
import com.kryptnostic.conductor.rpc.odata.EntityType;
import com.kryptnostic.conductor.rpc.odata.PropertyType;
import com.kryptnostic.datastore.cassandra.Queries;

@Accessor
public interface CassandraEdmStore {
    @Query( Queries.GET_ALL_ENTITY_TYPES_QUERY )
    public Result<EntityType> getEntityTypes();

    @Query( Queries.GET_ALL_ENTITY_TYPES_QUERY )
    public ListenableFuture<Result<EntityType>> getObjectTypesAsync();

    @Query( Queries.GET_ALL_PROPERTY_TYPES_IN_NAMESPACE )
    public Result<PropertyType> getPropertyTypesInNamespace( String namespace );

    @Query( Queries.GET_ALL_PROPERTY_TYPES_QUERY )
    public Result<PropertyType> getPropertyTypes();

    @Query( Queries.CREATE_ENTITY_TYPE_IF_NOT_EXISTS )
    public ResultSet createEntityTypeIfNotExists(
            String namespace,
            String type,
            String typename,
            Set<FullQualifiedName> key,
            Set<FullQualifiedName> properties,
            Set<FullQualifiedName> schemas);

    @Query( Queries.CREATE_PROPERTY_TYPE_IF_NOT_EXISTS )
    public ResultSet createPropertyTypeIfNotExists(
            String namespace,
            String type,
            String typename,
            EdmPrimitiveTypeKind datatype,
            long multiplicity,
            Set<FullQualifiedName> schemas);
    
    @Query( Queries.UPDATE_PROPERTY_TYPE_IF_EXISTS )
    public ResultSet updatePropertyTypeIfExists(
            EdmPrimitiveTypeKind datatype,
            long multiplicity,
            Set<FullQualifiedName> schemas,
            String namespace,
            String type);
    

    @Query( Queries.CREATE_ENTITY_SET_IF_NOT_EXISTS )
    public ResultSet createEntitySetIfNotExists( 
            String typename, 
            String name, 
            String title);

    @Query( Queries.GET_ENTITY_SET_BY_NAME )
    public EntitySet getEntitySet( String name );

    @Query( Queries.GET_ALL_ENTITY_SETS )
    public Result<EntitySet> getEntitySets();
    
    @Query( Queries.GET_ALL_ENTITY_SETS_FOR_ENTITY_TYPE )
    public Result<EntitySet> getEntitySetsForEntityType( String typename );
    
    @Query( Queries.UPDATE_EXISTING_ENTITY_TYPE)
    public ResultSet updateExistingEntityType(
    		@Param(Queries.ParamNames.NAMESPACE) String namespace,
    		@Param(Queries.ParamNames.NAME) String name,
    		@Param(Queries.ParamNames.KEY) Set<FullQualifiedName> key,
    		@Param(Queries.ParamNames.PROPERTIES) Set<FullQualifiedName> properties
    		);
}
