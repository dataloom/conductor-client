package com.kryptnostic.datastore.services;

import java.util.EnumSet;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.apache.commons.lang3.StringUtils;
import org.apache.olingo.commons.api.edm.FullQualifiedName;
import org.springframework.web.bind.annotation.RequestParam;

import com.kryptnostic.datastore.services.requests.EntityTypeAclRequest;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.kryptnostic.datastore.Permission;
import com.kryptnostic.datastore.PermissionsInfo;
import com.kryptnostic.datastore.Principal;
import com.kryptnostic.datastore.services.requests.EntitySetAclRequest;
import com.kryptnostic.datastore.services.requests.PropertyTypeInEntitySetAclRemovalRequest;
import com.kryptnostic.datastore.services.requests.PropertyTypeInEntitySetAclRequest;
import com.kryptnostic.datastore.services.requests.PropertyTypeInEntityTypeAclRemovalRequest;
import com.kryptnostic.datastore.services.requests.PropertyTypeInEntityTypeAclRequest;

import retrofit.client.Response;
import retrofit.http.Body;
import retrofit.http.DELETE;
import retrofit.http.GET;
import retrofit.http.POST;
import retrofit.http.Query;

/**
 * 
 * @author Ho Chung Siu
 *
 */
public interface PermissionsApi {

    String NAME                     = "name";
    String NAMESPACE                = "namespace";
    String ACTION                   = "action";
    String PRINCIPAL                = "principal";
    String REQUEST_ID                = "id";

    String CONTROLLER               = "/acl";
    String ENTITY_SETS_BASE_PATH    = "/entity/set";
    String ENTITY_TYPE_BASE_PATH    = "/entity/type";
    String PROPERTY_TYPE_BASE_PATH  = "/property/type";
    String ALL_PATH                 = "/all";
    
    String OWNER_PATH                 = "/owner";
    String REQUEST_PERMISSIONS_PATH = "/requests";

    /*************************************
     * Methods for modifying permissions
     *************************************/

    /**
     * 
     * @param requests Set of EntityTypeAclRequest, each updating access rights of one entity type for one role. 
     * 
     * Format of one EntityTypeAclRequest is as follows: 
     * - role: [String] role where access rights will be updated.
     * - action: [Enum add/set/remove] action for access rights 
     * - type: [FullQualifiedName] FullQualifiedName of entity type to be updated 
     * - permissions: [Set &lt; Enum discover/read/write/alter &gt; ] set of permissions to be added/set/removed, according to the action.
     * @return
     */
    @POST( CONTROLLER + ENTITY_TYPE_BASE_PATH )
    Response updateEntityTypesAcls( @Body Set<EntityTypeAclRequest> requests );

    /**
     * 
     * @param requests Set of EntitySetAclRequest, each updating access rights of one entity set for one role. 
     * 
     * Format of one EntitySetRequest is as follows: 
     * - role: [String] role where access rights will be updated. 
     * - action: [Enum add/set/remove] action for access rights 
     * - name: [String] name of entity set to be updated 
     * - permissions: [Set &lt; Enum discover/read/write/alter &gt; ] set of permissions to be added/set/removed, according to the action.
     * @return
     */
    @POST( CONTROLLER + ENTITY_SETS_BASE_PATH )
    Response updateEntitySetsAcls( @Body Set<EntitySetAclRequest> requests );

    /**
     * 
     * @param requests Set of PropertyTypeInEntityTypeAclRequest, each updating access rights of one entity set for one
     *            role. 
     * Format of one PropertyTypeInEntityTypeAclRequest is as follows: 
     * - role: [String] role where access rights will be updated. 
     * - action: [Enum add/set/remove] action for access rights 
     * - type: [FullQualifiedName] FullQualifiedName of entity type to be updated 
     * - properties: [FullQualifiedName] FullQualifiedName of property type to be updated 
     * - permissions: [Set &lt; Enum discover/read/write/alter &gt; ] set of permissions to be added/set/removed, according to the action.
     * @return
     */
    @POST( CONTROLLER + ENTITY_TYPE_BASE_PATH + PROPERTY_TYPE_BASE_PATH )
    Response updatePropertyTypeInEntityTypeAcls( @Body Set<PropertyTypeInEntityTypeAclRequest> requests );

    /**
     * 
     * @param requests Set of PropertyTypeInEntitySetAclRequest, each updating access rights of one entity set for one role. 
     *            
     * Format of one PropertyTypeInEntitySet is as follows: 
     * - role: [String] role where access rights will be updated. 
     * - action: [Enum add/set/remove] action for access rights 
     * - name: [String] name of entity set to be updated 
     * - properties: [FullQualifiedName] FullQualifiedName of property type to be updated 
     * - permissions: [Set &lt; Enum discover/read/write/alter &gt; ] set of permissions to be added/set/removed, according to the action.
     * @return
     */
    @POST( CONTROLLER + ENTITY_SETS_BASE_PATH + PROPERTY_TYPE_BASE_PATH )
    Response updatePropertyTypeInEntitySetAcls( @Body Set<PropertyTypeInEntitySetAclRequest> requests );

    /**
     * 
     * @param entityTypeFqns Set of FullQualifiedName of entity Types, where all the access rights associated to them
     *            are to be removed.
     * @return
     */
    @DELETE( CONTROLLER + ENTITY_TYPE_BASE_PATH )
    Response removeEntityTypeAcls( @Body Set<FullQualifiedName> entityTypeFqns );

    /**
     * 
     * @param entitySetNames Set of String's of names of entity sets, where all the access rights associated to them are
     *            to be removed.
     * @return
     */
    @DELETE( CONTROLLER + ENTITY_SETS_BASE_PATH )
    Response removeEntitySetAcls( @Body Set<String> entitySetNames );

    /**
     * 
     * @param requests Set of PropertyTypeInEntityTypeAclRemovalRequest, where all the access rights associated to the
     *            (entity type, property type) pairs are to be removed. 
     *            
     * Format of one PropertyTypeInEntityTypeAclRemovalRequest is as follows: 
     * - type: [FullQualifiedName] FullQualifiedNam of entity type 
     * - properties: [Set &lt; FullQualifiedName &gt; ] FullQualifiedName of properties to be removed.
     * @return
     */
    @DELETE( CONTROLLER + ENTITY_TYPE_BASE_PATH + PROPERTY_TYPE_BASE_PATH )
    Response removePropertyTypeInEntityTypeAcls( @Body Set<PropertyTypeInEntityTypeAclRemovalRequest> requests );

    /**
     * 
     * @param entityTypeFqns Set of FullQualifiedName of entity types, where the access rights of all property types
     *            associated to each entity type are removed.
     * @return
     */
    @DELETE( CONTROLLER + ENTITY_TYPE_BASE_PATH + PROPERTY_TYPE_BASE_PATH + ALL_PATH )
    Response removeAllPropertyTypesInEntityTypeAcls( @Body Set<FullQualifiedName> entityTypeFqns );

    /**
     * 
     * @param requests Set of PropertyTypeInEntitySetAclRemovalRequest, where all the access rights associated to the
     *            (entity set, property type) pairs are to be removed. 
     *            
     * Format of one PropertyTypeInEntitySetAclRemovalRequest is as follows: 
     * - name: [String] name of the entity set 
     * - properties: [Set &lt; FullQualifiedName &gt; ] FullQualifiedName of properties to be removed.
     * @return
     */
    @DELETE( CONTROLLER + ENTITY_SETS_BASE_PATH + PROPERTY_TYPE_BASE_PATH )
    Response removePropertyTypeInEntitySetAcls( @Body Set<PropertyTypeInEntitySetAclRemovalRequest> requests );

    /**
     * 
     * @param entitySetNames Set of names of entity sets, where the access rights of all property types associated to
     *            them will be removed.
     * @return
     */
    @DELETE( CONTROLLER + ENTITY_SETS_BASE_PATH + PROPERTY_TYPE_BASE_PATH + ALL_PATH )
    Response removeAllPropertyTypesInEntitySetAcls( @Body Set<String> entitySetNames );

    /***************************************
     * Methods for retrieving permissions
     ***************************************/
    
    /**
     * Get user permissions for an entity set (itself, not the property types)
     * @param entitySetName name of the entity set
     * @return
     */
    @GET( CONTROLLER + ENTITY_SETS_BASE_PATH)
    EnumSet<Permission> getEntitySetAclsForUser( @Query( NAME ) String entitySetName );

    /**
     * Get user permissions for all property types of an entity set
     * @param entitySetName
     * @return
     */
    @GET( CONTROLLER + ENTITY_SETS_BASE_PATH + PROPERTY_TYPE_BASE_PATH)
    Map<FullQualifiedName, EnumSet<Permission>> getPropertyTypesInEntitySetAclsForUser( @Query( NAME ) String entitySetName );

    /**
     * <b>Can be safely ignored, until permissions for an entity type makes sense/matters.</b>
     * Get user permissions for an entity type (itself, not the property types) 
     * @param entityTypeNamespace
     * @param entityTypeName
     * @return
     */
    @GET( CONTROLLER + ENTITY_TYPE_BASE_PATH)
    EnumSet<Permission> getEntityTypeAclsForUser( @Query( NAMESPACE ) String entityTypeNamespace, @Query( NAME ) String entityTypeName );

    /**
     * <b>Can be safely ignored, until permissions for an entity type makes sense/matters.</b>
     * Get user permissions for all property types of an entity type
     * @param entityTypeNamespace
     * @param entityTypename
     * @return
     */
    @GET( CONTROLLER + ENTITY_TYPE_BASE_PATH + PROPERTY_TYPE_BASE_PATH )
    Map<FullQualifiedName, EnumSet<Permission>> getPropertyTypesInEntityTypeAclsForUser( @Query( NAMESPACE ) String entityTypeNamespace, @Query( NAME ) String entityTypename );

    /**
     * Get all permissions of an entity set to all roles/users. 
     * This is a method for entity set owner to retrieve current permissions of the entity set.
     * @param entitySetName
     * @return
     */
    @GET( CONTROLLER + ENTITY_SETS_BASE_PATH + OWNER_PATH )
    Iterable<PermissionsInfo> getEntitySetAclsForOwner( @Query( NAME ) String entitySetName );

    /**
     * Get all permissions of all property types of an entity set, for one particular role/user. 
     * This is a method for entity set owner to retrieve current permissions of the entity set.
     * @param entitySetName
     * @param principal
     * @return
     */
    @POST( CONTROLLER + ENTITY_SETS_BASE_PATH + OWNER_PATH )
    Map<FullQualifiedName, EnumSet<Permission>> getPropertyTypesInEntitySetAclsForOwner( @Query( NAME ) String entitySetName, @Body Principal principal );

    /**
     * Get all permissions of one property type of an entity set. 
     * This is a method for entity set owner to retrieve current permissions of the entity set.
     * @param entitySetName
     * @param propertyTypeFqn
     * @return
     */
    @POST( CONTROLLER + ENTITY_SETS_BASE_PATH + OWNER_PATH + PROPERTY_TYPE_BASE_PATH )
    Iterable<PermissionsInfo> getPropertyTypesInEntitySetAclsForOwner( @Query( NAME ) String entitySetName, @Body FullQualifiedName propertyTypeFqn );

    /***************************************
     * Methods for requesting permissions
     ***************************************/
    
    /**
     * Set of PropertyTypeInEntitySetAclRequest, each requesting access rights of one entity set for one principal. 
     * 
     * Format of one PropertyTypeInEntitySet is as follows: 
     * - role: [String] role where access rights will be updated. 
     * - action: [Enum request] action for requesting rights 
     * - name: [String] name of entity set whose rights to be updated 
     * - properties: [FullQualifiedName] FullQualifiedName of property type whose rights to be updated. This field can be ignored, if one wishes to request access to the entity set itself.
     * - permissions: [Set &lt; Enum discover/read/write/alter &gt; ] set of permissions to be requested.
     * @param requests
     * @return
     */
    @POST( CONTROLLER + ENTITY_SETS_BASE_PATH + REQUEST_PERMISSIONS_PATH )
    Response addPermissionsRequestForPropertyTypesInEntitySet( @Body Set<PropertyTypeInEntitySetAclRequest> requests );
    
    /**
     * Delete a PermissionsRequest by requestId. This action is authorized for user who created the request, 
     *            or user who owns the target entity set        
     * @param id UUID for the request
     * @return
     */
    @DELETE( CONTROLLER + ENTITY_SETS_BASE_PATH + REQUEST_PERMISSIONS_PATH )
    Response removePermissionsRequestForEntitySet( @Query( REQUEST_ID ) UUID id );

    /**
     * Get All Received Permissions Request for a user. entitySetName is optional - if it goes missing, the method
     *            would return all received requests for the user. 
     * @param entitySetName name of entity set to look up, optional
     * @return
     */
    @GET( CONTROLLER + ENTITY_SETS_BASE_PATH + OWNER_PATH + REQUEST_PERMISSIONS_PATH )
    Iterable<PropertyTypeInEntitySetAclRequest> getAllReceivedRequestsForPermissions( @Query( NAME ) String entitySetName );
    
    /**
     * Get All Received Permissions Request for a user. entitySetName is optional - if it goes missing, the method
     *            would return all sent requests for the user. 
     * @param entitySetName name of entity set to look up, optional
     * @return
     */
    @GET( CONTROLLER + ENTITY_SETS_BASE_PATH + REQUEST_PERMISSIONS_PATH )
    Iterable<PropertyTypeInEntitySetAclRequest> getAllSentRequestsForPermissions( @Query( NAME ) String entitySetName );
}
