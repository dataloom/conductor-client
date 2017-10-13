package com.openlattice.postgres.mapstores;

import com.auth0.jwt.internal.org.apache.commons.lang3.RandomStringUtils;
import com.dataloom.edm.set.EntitySetPropertyKey;
import com.dataloom.edm.set.EntitySetPropertyMetadata;
import com.google.common.collect.ImmutableList;
import com.openlattice.postgres.PostgresColumnDefinition;
import com.openlattice.postgres.PostgresTableDefinition;
import com.zaxxer.hikari.HikariDataSource;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.UUID;

import static com.openlattice.postgres.PostgresColumn.*;

public class EntitySetPropertyMetadataMapstore
        extends AbstractBasePostgresMapstore<EntitySetPropertyKey, EntitySetPropertyMetadata> {

    public EntitySetPropertyMetadataMapstore(
            String mapName,
            PostgresTableDefinition table,
            HikariDataSource hds ) {
        super( mapName, table, hds );
    }

    @Override protected List<PostgresColumnDefinition> keyColumns() {
        return ImmutableList.of( ENTITY_SET_ID, PROPERTY_TYPE_ID );
    }

    @Override protected List<PostgresColumnDefinition> valueColumns() {
        return ImmutableList.of( TITLE, DESCRIPTION, SHOW );
    }

    @Override protected void bind(
            PreparedStatement ps, EntitySetPropertyKey key, EntitySetPropertyMetadata value ) throws SQLException {
        bind( ps, key );

        ps.setString( 3, value.getTitle() );
        ps.setString( 4, value.getDescription() );
        ps.setBoolean( 5, value.getDefaultShow() );

        ps.setString( 6, value.getTitle() );
        ps.setString( 7, value.getDescription() );
        ps.setBoolean( 8, value.getDefaultShow() );
    }

    @Override protected void bind( PreparedStatement ps, EntitySetPropertyKey key ) throws SQLException {
        ps.setObject( 1, key.getEntitySetId() );
        ps.setObject( 2, key.getPropertyTypeId() );
    }

    @Override protected EntitySetPropertyMetadata mapToValue( ResultSet rs ) throws SQLException {
        String title = rs.getString( TITLE.getName() );
        String description = rs.getString( DESCRIPTION.getName() );
        boolean show = rs.getBoolean( SHOW.getName() );
        return new EntitySetPropertyMetadata( title, description, show );
    }

    @Override protected EntitySetPropertyKey mapToKey( ResultSet rs ) {
        try {
            UUID entitySetId = rs.getObject( ENTITY_SET_ID.getName(), UUID.class );
            UUID propertyTypeId = rs.getObject( PROPERTY_TYPE_ID.getName(), UUID.class );
            return new EntitySetPropertyKey( entitySetId, propertyTypeId );
        } catch ( SQLException e ) {
            logger.debug( "Unable to map EntitySetPropertyKey", e );
            return null;
        }
    }

    @Override public EntitySetPropertyKey generateTestKey() {
        return new EntitySetPropertyKey( UUID.randomUUID(), UUID.randomUUID() );
    }

    @Override public EntitySetPropertyMetadata generateTestValue() {
        return new EntitySetPropertyMetadata( RandomStringUtils.random( 10 ), RandomStringUtils.random( 10 ), true );
    }
}
