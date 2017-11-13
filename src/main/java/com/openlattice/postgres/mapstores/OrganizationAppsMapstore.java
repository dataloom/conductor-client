package com.openlattice.postgres.mapstores;

import com.dataloom.hazelcast.HazelcastMap;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Sets;
import com.kryptnostic.rhizome.hazelcast.objects.DelegatedUUIDSet;
import com.openlattice.postgres.PostgresArrays;
import com.openlattice.postgres.PostgresColumnDefinition;
import com.openlattice.postgres.PostgresTable;
import com.openlattice.postgres.ResultSetAdapters;
import com.zaxxer.hikari.HikariDataSource;

import java.sql.Array;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.UUID;

import static com.openlattice.postgres.PostgresColumn.APP_IDS;
import static com.openlattice.postgres.PostgresColumn.ID;

public class OrganizationAppsMapstore extends AbstractBasePostgresMapstore<UUID, DelegatedUUIDSet> {

    public OrganizationAppsMapstore( HikariDataSource hds ) {
        super( HazelcastMap.ORGANIZATION_APPS.name(), PostgresTable.ORGANIZATIONS, hds );
    }

    @Override protected List<PostgresColumnDefinition> keyColumns() {
        return ImmutableList.of( ID );
    }

    @Override protected List<PostgresColumnDefinition> valueColumns() {
        return ImmutableList.of( APP_IDS );
    }

    @Override protected void bind(
            PreparedStatement ps, UUID key, DelegatedUUIDSet value ) throws SQLException {
        ps.setObject( 1, key );

        Array arr = PostgresArrays.createUuidArray( ps.getConnection(), value.stream() );

        ps.setArray( 2, arr );

        // UPDATE
        ps.setArray( 3, arr );
    }

    @Override protected void bind( PreparedStatement ps, UUID key ) throws SQLException {
        ps.setObject( 1, key );
    }

    @Override protected DelegatedUUIDSet mapToValue( ResultSet rs ) throws SQLException {
        Array arr = rs.getArray( APP_IDS.getName() );
        if ( arr != null ) {
            UUID[] value = (UUID[]) arr.getArray();
            if ( value != null )
                return DelegatedUUIDSet.wrap( Sets.newHashSet( value ) );
        }
        return DelegatedUUIDSet.wrap( Sets.newHashSet() );
    }

    @Override protected UUID mapToKey( ResultSet rs ) throws SQLException {
        return ResultSetAdapters.id( rs );
    }

    @Override public UUID generateTestKey() {
        return UUID.randomUUID();
    }

    @Override public DelegatedUUIDSet generateTestValue() {
        return DelegatedUUIDSet.wrap( ImmutableSet.of( UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID() ) );
    }
}
