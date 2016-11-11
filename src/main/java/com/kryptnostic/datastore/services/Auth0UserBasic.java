package com.kryptnostic.datastore.services;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

public class Auth0UserBasic {
    public static final String USER_ID_FIELD = "user_id";
    public static final String EMAIL_FIELD   = "email";
    public static final String ROLES_FIELD   = "roles";

    private final String       userId;
    private final String       email;
    private final List<String> roles;

    @JsonCreator
    public Auth0UserBasic(
            @JsonProperty( USER_ID_FIELD ) String userId,
            @JsonProperty( EMAIL_FIELD ) String email,
            @JsonProperty( ROLES_FIELD ) List<String> roles ) {
        this.userId = userId;
        this.email = email;
        this.roles = roles;
    }

    @JsonProperty( USER_ID_FIELD )
    public String getUserId() {
        return userId;
    }

    @JsonProperty( EMAIL_FIELD )
    public String getEmail() {
        return email;
    }

    @JsonProperty( ROLES_FIELD )
    public List<String> getRoles() {
        return roles;
    }
}
