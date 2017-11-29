package com.openlattice.postgres.mapstores;

import com.dataloom.apps.AppType;
import com.dataloom.hazelcast.HazelcastMap;
import com.google.common.base.Optional;
import com.google.common.collect.ImmutableList;
import com.openlattice.postgres.PostgresColumnDefinition;
import com.openlattice.postgres.PostgresTable;
import com.openlattice.postgres.ResultSetAdapters;
import com.zaxxer.hikari.HikariDataSource;
import org.apache.commons.lang3.RandomStringUtils;
import org.apache.olingo.commons.api.edm.FullQualifiedName;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.UUID;

import static com.openlattice.postgres.PostgresColumn.*;

public class AppTypeMapstore extends AbstractBasePostgresMapstore<UUID, AppType> {
    public AppTypeMapstore( HikariDataSource hds ) {
        super( HazelcastMap.APP_TYPES.name(), PostgresTable.APP_TYPES, hds );
    }

    @Override protected List<PostgresColumnDefinition> keyColumns() {
        return ImmutableList.of( ID );
    }

    @Override protected List<PostgresColumnDefinition> valueColumns() {
        return ImmutableList.of( NAMESPACE, NAME, TITLE, DESCRIPTION, ENTITY_TYPE_ID );
    }

    @Override protected void bind( PreparedStatement ps, UUID key, AppType value ) throws SQLException {
        ps.setObject( 1, key );

        ps.setString( 2, value.getType().getNamespace() );
        ps.setString( 3, value.getType().getName() );
        ps.setString( 4, value.getTitle() );
        ps.setString( 5, value.getDescription() );
        ps.setObject( 6, value.getEntityTypeId() );

        // UPDATE
        ps.setString( 7, value.getType().getNamespace() );
        ps.setString( 8, value.getType().getName() );
        ps.setString( 9, value.getTitle() );
        ps.setString( 10, value.getDescription() );
        ps.setObject( 11, value.getEntityTypeId() );
    }

    @Override protected void bind( PreparedStatement ps, UUID key ) throws SQLException {
        ps.setObject( 1, key );
    }

    @Override protected AppType mapToValue( ResultSet rs ) throws SQLException {
        return ResultSetAdapters.appType( rs );
    }

    @Override protected UUID mapToKey( ResultSet rs ) throws SQLException {
        return ResultSetAdapters.id( rs );
    }

    @Override public UUID generateTestKey() {
        return UUID.randomUUID();
    }

    @Override public AppType generateTestValue() {
        return new AppType( UUID.randomUUID(),
                new FullQualifiedName( RandomStringUtils.randomAlphanumeric( 5 ),
                        RandomStringUtils.randomAlphanumeric( 5 ) ),
                RandomStringUtils.randomAlphanumeric( 5 ),
                Optional.of( RandomStringUtils.randomAlphanumeric( 5 ) ),
                UUID.randomUUID() );
    }
}
