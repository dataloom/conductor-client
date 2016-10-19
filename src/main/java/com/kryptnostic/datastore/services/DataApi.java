package com.kryptnostic.datastore.services;

import com.google.common.collect.Multimap;
import com.kryptnostic.conductor.rpc.*;
import com.squareup.okhttp.Response;
import org.apache.olingo.commons.api.edm.FullQualifiedName;
import retrofit.http.*;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public interface DataApi {
    String CONTROLLER = "/data";

    String FULLQUALIFIEDNAME = "fqn";
    String NAME              = "name";
    String NAME_SPACE        = "namespace";
    String TYPE_NAME         = "typename";

    String MULTIPLE                        = "/multiple";
    String ENTITYSET                       = "/entityset";
    String ENTITY_DATA                     = "/entitydata";
    String FILTERED                        = "/filtered";
    String INTEGRATION                     = "/integration";
    String FULLQUALIFIEDNAME_PATH          = "/{" + FULLQUALIFIEDNAME + "}";
    String FULLQUALIFIEDNAME_PATH_WITH_DOT = "/{" + FULLQUALIFIEDNAME + ":.+}";
    String NAME_PATH                       = "/{" + NAME + "}";
    String NAME_SPACE_PATH                 = "/{" + NAME_SPACE + "}";
    String TYPE_NAME_PATH                  = "/{" + TYPE_NAME + "}";

    @GET( CONTROLLER + ENTITY_DATA + NAME_SPACE_PATH + TYPE_NAME_PATH + NAME_PATH )
    Iterable<Multimap<FullQualifiedName, Object>> getAllEntitiesOfEntitySet(
            @Path( NAME ) String entitySetName,
            @Path( NAME_SPACE ) String entityTypeNamespace, @Path( TYPE_NAME ) String entityTypeName );

    @PUT( CONTROLLER + ENTITY_DATA )
    Iterable<Multimap<FullQualifiedName, Object>> getAllEntitiesOfType( @Body FullQualifiedName fqn );

    @PUT( CONTROLLER + ENTITY_DATA + MULTIPLE )
    Iterable<Iterable<Multimap<FullQualifiedName, Object>>> getAllEntitiesOfTypes(
            @Body List<FullQualifiedName> fqns );

    @GET( CONTROLLER + ENTITY_DATA + FULLQUALIFIEDNAME_PATH )
    Iterable<Multimap<FullQualifiedName, Object>> getAllEntitiesOfType(
            @Path( FULLQUALIFIEDNAME ) String fqnAsString );

    @GET( CONTROLLER + ENTITY_DATA + NAME_SPACE_PATH + NAME_PATH )
    Iterable<Multimap<FullQualifiedName, Object>> getAllEntitiesOfType(
            @Path( NAME_SPACE ) String namespace,
            @Path( NAME ) String name );

    @PUT( CONTROLLER + ENTITY_DATA + FILTERED )
    /**
     * 
     * @param obj ObjectNode that builds a LookupEntitiesRequest. Should be JSON of the form 
     * SerializationConstants.USER_ID (userId): UUID, 
     * SerializationConstants.TYPE_FIELD (type): Set\<FullQualifiedName\>, 
     * SerializationConstants.PROPERTIES_FIELD (properties): Map\<FullQualifiedName as String, Object\>  
     * @return Iterable of UUID matching the request
     */
    Iterable<UUID> getFilteredEntities( @Body LookupEntitiesRequest lookupEntitiesRequest );

    @POST( CONTROLLER + ENTITY_DATA )
    Response createEntityData( @Body CreateEntityRequest createEntityRequest );

    @GET( CONTROLLER + INTEGRATION )
    Map<String, String> getAllIntegrationScripts();

    @PUT( CONTROLLER + INTEGRATION )
    Map<String, String> getIntegrationScript( @Body Set<String> url );

    @POST( CONTROLLER + INTEGRATION )
    Response createIntegrationScript( @Body Map<String, String> integrationScripts );

}
