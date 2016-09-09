package com.kryptnostic.conductor.rpc.odata;

import java.util.Set;

import org.apache.commons.lang3.StringUtils;
import org.apache.olingo.commons.api.edm.FullQualifiedName;

import com.datastax.driver.mapping.annotations.Column;
import com.datastax.driver.mapping.annotations.Table;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.google.common.base.Preconditions;

@Table(
    keyspace = DatastoreConstants.KEYSPACE,
    name = DatastoreConstants.ENTITY_TYPES_TABLE )
public class EntityType extends TypePK {
    @Column(
        name = "typename" )
    private String                 typename = null;

    @Column(
        name = "key" )
    private Set<FullQualifiedName> key;

    @Column(
        name = "properties" )
    public Set<FullQualifiedName>  properties;

    public EntityType setNamespace( String namespace ) {
        this.namespace = namespace;
        return this;
    }

    public EntityType setName( String name ) {
        this.name = name;
        return this;
    }

    @JsonIgnore
    public String getTypename() {
        return typename;
    }

    public EntityType setTypename( String typename ) {
        // typename must only be set once
        Preconditions.checkState( StringUtils.isBlank( this.typename ) );
        this.typename = typename;
        return this;
    }

    // TODO: It seems the objects do not allow property types from the different schemas.
    public Set<FullQualifiedName> getKey() {
        return key;
    }

    public EntityType setKey( Set<FullQualifiedName> key ) {
        this.key = key;
        return this;
    }

    public Set<FullQualifiedName> getProperties() {
        return properties;
    }

    public EntityType setProperties( Set<FullQualifiedName> properties ) {
        this.properties = properties;
        return this;
    }

    @Override
    public String toString() {
        return "ObjectType [namespace=" + namespace + ", type=" + name + ", typename=" + typename + ", key=" + key
                + ", allowed=" + properties + "]";
    }

    @Override
    public int hashCode() {
        final int prime = 31;
        int result = 1;
        result = prime * result + ( ( key == null ) ? 0 : key.hashCode() );
        result = prime * result + ( ( namespace == null ) ? 0 : namespace.hashCode() );
        result = prime * result + ( ( properties == null ) ? 0 : properties.hashCode() );
        result = prime * result + ( ( name == null ) ? 0 : name.hashCode() );
        result = prime * result + ( ( typename == null ) ? 0 : typename.hashCode() );
        return result;
    }

    @Override
    public boolean equals( Object obj ) {
        if ( this == obj ) {
            return true;
        }
        if ( obj == null ) {
            return false;
        }
        if ( !( obj instanceof EntityType ) ) {
            return false;
        }
        EntityType other = (EntityType) obj;
        if ( key == null ) {
            if ( other.key != null ) {
                return false;
            }
        } else if ( !key.equals( other.key ) ) {
            return false;
        }
        if ( namespace == null ) {
            if ( other.namespace != null ) {
                return false;
            }
        } else if ( !namespace.equals( other.namespace ) ) {
            return false;
        }
        if ( properties == null ) {
            if ( other.properties != null ) {
                return false;
            }
        } else if ( !properties.equals( other.properties ) ) {
            return false;
        }
        if ( name == null ) {
            if ( other.name != null ) {
                return false;
            }
        } else if ( !name.equals( other.name ) ) {
            return false;
        }
        if ( typename == null ) {
            if ( other.typename != null ) {
                return false;
            }
        } else if ( !typename.equals( other.typename ) ) {
            return false;
        }
        return true;
    }

    @JsonCreator
    public static EntityType newEntityType(
            @JsonProperty( SerializationConstants.NAMESPACE_FIELD ) String namespace,
            @JsonProperty( SerializationConstants.TYPE_FIELD ) String type,
            @JsonProperty( SerializationConstants.KEY_FIELD ) Set<FullQualifiedName> key,
            @JsonProperty( SerializationConstants.PROPERTIES_FIELD ) Set<FullQualifiedName> properties ) {
        return new EntityType()
                .setNamespace( namespace )
                .setName( type )
                .setTypename( null )
                .setProperties( properties )
                .setKey( key );
    }
}
