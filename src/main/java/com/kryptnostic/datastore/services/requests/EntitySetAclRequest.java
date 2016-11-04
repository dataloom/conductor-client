package com.kryptnostic.datastore.services.requests;

import java.util.Set;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.kryptnostic.conductor.rpc.odata.SerializationConstants;
import com.kryptnostic.datastore.Permission;

public class EntitySetAclRequest extends AclRequest {

    @JsonProperty( SerializationConstants.NAME_FIELD )
    protected String entitySetName;

    public String getName() {
        return entitySetName;
    }

    @Override
    public EntitySetAclRequest setRole( String role ) {
        this.role = role;
        return this;
    }

    @Override
    public EntitySetAclRequest setAction( Action action ) {
        this.action = action;
        return this;
    }

    @Override
    public EntitySetAclRequest setPermissions( Set<Permission> permissions ) {
        this.permissions = permissions;
        return this;
    }

    public EntitySetAclRequest setName( String entitySetName ) {
        this.entitySetName = entitySetName;
        return this;
    }

    @Override
    public int hashCode() {
        int result = super.hashCode();
        result = 31 * result + ( entitySetName != null ? entitySetName.hashCode() : 0 );
        return result;
    }

    @Override
    public boolean equals( Object obj ) {
        if ( this == obj )
            return true;
        if ( obj == null || getClass() != obj.getClass() )
            return false;
        if ( !super.equals( obj ) )
            return false;

        EntitySetAclRequest that = (EntitySetAclRequest) obj;

        return entitySetName != null ? entitySetName.equals( that.entitySetName ) : that.entitySetName == null;
    }

    @JsonCreator
    public EntitySetAclRequest createEntitySetAclRequest(
            @JsonProperty( SerializationConstants.ROLE ) String role,
            @JsonProperty( SerializationConstants.ACTION ) Action action,
            @JsonProperty( SerializationConstants.NAME_FIELD ) String entitySetName,
            @JsonProperty( SerializationConstants.PERMISSIONS ) Set<Permission> permissions ) {
        return new EntitySetAclRequest().setRole( role ).setAction( action ).setName( entitySetName )
                .setPermissions( permissions );
    }
}
