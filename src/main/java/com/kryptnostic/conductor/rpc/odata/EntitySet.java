package com.kryptnostic.conductor.rpc.odata;

import org.apache.commons.lang3.StringUtils;
import org.apache.olingo.commons.api.edm.FullQualifiedName;

import com.datastax.driver.mapping.annotations.ClusteringColumn;
import com.datastax.driver.mapping.annotations.Column;
import com.datastax.driver.mapping.annotations.PartitionKey;
import com.datastax.driver.mapping.annotations.Table;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.google.common.base.Preconditions;

@Table(
    keyspace = DatastoreConstants.KEYSPACE,
    name = DatastoreConstants.ENTITY_SETS_TABLE )
public class EntitySet {
    @PartitionKey(
        value = 0 )
    private FullQualifiedName type;

    @ClusteringColumn(
        value = 0 )
    private String            name;

    @Column(
        name = "title" )
    private String            title;
    
    @Column(
        name = "typename" )
    private String            typename = null;


    public String getName() {
        return name;
    }

    public EntitySet setName( String name ) {
        this.name = name;
        return this;
    }
    
    @JsonIgnore
    public String getTypename() {
        return typename;
    }

    public EntitySet setTypename( String typename ) {
        // typename must only be set once
        Preconditions.checkArgument( StringUtils.isBlank( this.typename ) );
        this.typename = typename;
        return this;
    }

    
    public String getTitle() {
        return title;
    }

    public EntitySet setTitle( String title ) {
        this.title = title;
        return this;
    }

    public FullQualifiedName getType() {
        return type;
    }

    public EntitySet setType( FullQualifiedName type ) {
        this.type = type;
        return this;
    }

    @Override
    public int hashCode() {
        final int prime = 31;
        int result = 1;
        result = prime * result + ( ( name == null ) ? 0 : name.hashCode() );
        result = prime * result + ( ( title == null ) ? 0 : title.hashCode() );
        result = prime * result + ( ( type == null ) ? 0 : type.hashCode() );
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
        if ( !( obj instanceof EntitySet ) ) {
            return false;
        }
        EntitySet other = (EntitySet) obj;
        if ( name == null ) {
            if ( other.name != null ) {
                return false;
            }
        } else if ( !name.equals( other.name ) ) {
            return false;
        }
        if ( title == null ) {
            if ( other.title != null ) {
                return false;
            }
        } else if ( !title.equals( other.title ) ) {
            return false;
        }
        if ( type == null ) {
            if ( other.type != null ) {
                return false;
            }
        } else if ( !type.equals( other.type ) ) {
            return false;
        }
        return true;
    }

    @Override
    public String toString() {
        return "EntitySet [type=" + type + ", name=" + name + ", title=" + title + "]";
    }

    @JsonCreator
    public static EntitySet newEntitySet(
            @JsonProperty( SerializationConstants.TYPE_FIELD) FullQualifiedName type,
            @JsonProperty( SerializationConstants.NAME_FIELD) String name,     
            @JsonProperty( SerializationConstants.TITLE_FIELD) String title) {
        return new EntitySet()
                .setTypename( null )
                .setType( type )
                .setName( name )
                .setTitle( title );
    }
}
