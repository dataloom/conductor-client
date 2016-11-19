package com.kryptnostic.datastore.services;

import retrofit.client.Response;
import retrofit.http.*;

import java.util.List;
import java.util.Map;

public interface UserDirectoryApi {
    String CONTROLLER = "/admin";

    String USER_ID = "userId";
    String ROLE    = "role";

    String USERS  = "/users";
    String ROLES  = "/roles";
    String RESET    = "/reset";

    String USER_ID_PATH = "/{" + USER_ID + "}";
    String ROLE_PATH    = "/{" + ROLE + "}";

    @GET( CONTROLLER + USERS )
    Map<String, Auth0UserBasic> getAllUsers();

    @GET( CONTROLLER + USERS + USER_ID_PATH )
    Auth0UserBasic getUser( @Path( USER_ID ) String userId );

    @GET( CONTROLLER + USERS + ROLES )
    Map<String, List<Auth0UserBasic>> getAllUsersGroupByRole();

    @GET( CONTROLLER + USERS + ROLES + ROLE_PATH )
    List<Auth0UserBasic> getAllUsersOfRole( @Path( ROLE ) String role );

    @PATCH( CONTROLLER + USERS + ROLES + RESET + USER_ID_PATH )
    void resetRolesOfUser( @Path( USER_ID ) String userId, @Body List<String> roles );
}
