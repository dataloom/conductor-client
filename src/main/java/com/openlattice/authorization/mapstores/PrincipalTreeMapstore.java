/*
 * Copyright (C) 2017. OpenLattice, Inc
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 *
 * You can contact the owner of the copyright at support@openlattice.com
 *
 */

package com.openlattice.authorization.mapstores;

import com.dataloom.hazelcast.HazelcastMap;
import com.dataloom.mapstores.TestDataFactory;
import com.google.common.collect.ImmutableList;
import com.openlattice.authorization.AclKey;
import com.openlattice.authorization.AclKeySet;
import com.openlattice.postgres.PostgresArrays;
import com.openlattice.postgres.PostgresColumn;
import com.openlattice.postgres.PostgresColumnDefinition;
import com.openlattice.postgres.PostgresTable;
import com.openlattice.postgres.ResultSetAdapters;
import com.openlattice.postgres.mapstores.AbstractBasePostgresMapstore;
import com.zaxxer.hikari.HikariDataSource;
import java.sql.Array;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.UUID;

/**
 * @author Matthew Tamayo-Rios &lt;matthew@openlattice.com&gt;
 */
public class PrincipalTreeMapstore extends AbstractBasePostgresMapstore<AclKey, AclKeySet> {
    public PrincipalTreeMapstore( HikariDataSource hds ) {
        super( HazelcastMap.PRINCIPAL_TREES.name(), PostgresTable.PRINCIPAL_TREES, hds );
    }

    @Override public AclKey generateTestKey() {
        return TestDataFactory.aclKey();
    }

    @Override public AclKeySet generateTestValue() {
        return new AclKeySet( ImmutableList.of( generateTestKey(), generateTestKey(), generateTestKey() ) );
    }

    @Override protected List<PostgresColumnDefinition> keyColumns() {
        return ImmutableList.of( PostgresColumn.ACL_KEY );
    }

    @Override protected List<PostgresColumnDefinition> valueColumns() {
        return ImmutableList.of( PostgresColumn.ACL_KEY_SET );
    }

    @Override protected void bind( PreparedStatement ps, AclKey key, AclKeySet value ) throws SQLException {
        bind( ps, key );
        Array arr = PostgresArrays.createUuidArrayOfArrays( ps.getConnection(),
                value.stream().map( aclKey -> aclKey.toArray( new UUID[ 0 ] ) ) );
        ps.setArray( 2, arr );
        ps.setArray( 3, arr ); //For update on conflict statement.

    }

    @Override protected void bind( PreparedStatement ps, AclKey key ) throws SQLException {
        ps.setArray( 1, PostgresArrays.createUuidArray( ps.getConnection(), key.stream() ) );
    }

    @Override protected AclKeySet mapToValue( ResultSet rs ) throws SQLException {
        return ResultSetAdapters.aclKeySet( rs );
    }

    @Override protected AclKey mapToKey( ResultSet rs ) throws SQLException {
        return ResultSetAdapters.aclKey( rs );
    }
}
