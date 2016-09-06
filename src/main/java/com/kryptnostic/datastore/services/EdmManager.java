package com.kryptnostic.datastore.services;

import java.util.Set;
import java.util.UUID;

import org.apache.olingo.commons.api.edm.FullQualifiedName;

import com.kryptnostic.conductor.rpc.odata.EntitySet;
import com.kryptnostic.conductor.rpc.odata.EntityType;
import com.kryptnostic.conductor.rpc.odata.PropertyType;
import com.kryptnostic.conductor.rpc.odata.Schema;

public interface EdmManager {
    boolean createSchema( String namespace, String name, UUID aclId, Set<FullQualifiedName> entityTypes );

    void upsertSchema( Schema namespace );

    void enrichSchemaWithEntityTypes( Schema schema );

    void enrichSchemaWithPropertyTypes( Schema schema );

    Iterable<Schema> getSchemas();

    Iterable<Schema> getSchemasInNamespace( String namespace );

    Iterable<Schema> getSchema( String namespace, String name );

    void deleteSchema( Schema namespaces );

    boolean createEntitySet( FullQualifiedName type, String name, String title );

    boolean createEntitySet( EntitySet entitySet );

    void upsertEntitySet( EntitySet entitySet );

    EntitySet getEntitySet( FullQualifiedName entityType, String name );

    EntitySet getEntitySet( String name );

    Iterable<EntitySet> getEntitySets();

    void deleteEntitySet( EntitySet entitySet );

    boolean createEntityType( EntityType objectType );

    void upsertEntityType( EntityType objectType );

    EntityType getEntityType( String namespace, String name );

    void deleteEntityType( EntityType objectType );

    void addEntityTypesToSchema( String namespace, String name, Set<FullQualifiedName> entityTypes );

    void removeEntityTypesFromSchema( String namespace, String name, Set<FullQualifiedName> entityTypes );

    boolean createPropertyType( PropertyType propertyType );

    void upsertPropertyType( PropertyType propertyType );

    void deletePropertyType( PropertyType propertyType );

    PropertyType getPropertyType( FullQualifiedName prop );

    EntityDataModel getEntityDataModel();

    boolean isExistingEntitySet( FullQualifiedName type, String name );

}