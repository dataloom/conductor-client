/*
 * Copyright (C) 2017. Kryptnostic, Inc (dba Loom)
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
 * You can contact the owner of the copyright at support@thedataloom.com
 */

package com.dataloom.organizations.mapstores;

import java.util.UUID;

import com.auth0.jwt.internal.org.apache.commons.lang3.RandomStringUtils;
import com.dataloom.hazelcast.HazelcastMap;
import com.datastax.driver.core.BoundStatement;
import com.datastax.driver.core.ResultSet;
import com.datastax.driver.core.Row;
import com.datastax.driver.core.Session;
import com.kryptnostic.conductor.rpc.odata.Table;
import com.kryptnostic.rhizome.cassandra.ColumnDef;

public class StringMapstore extends UUIDKeyMapstore<String> {
    private final ColumnDef valueCol;

    public StringMapstore( HazelcastMap map, Session session, Table table, ColumnDef keyCol, ColumnDef valueCol ) {
        super( map, session, table, keyCol );
        this.valueCol = valueCol;
    }

    @Override
    public String generateTestValue() {
        return RandomStringUtils.random( 10 );
    }

    @Override
    protected BoundStatement bind( UUID key, String value, BoundStatement bs ) {
        return bs
                .setUUID( keyCol.cql(), key )
                .setString( valueCol.cql(), value );
    }

    @Override
    protected String mapValue( ResultSet rs ) {
        Row r = rs.one();
        return r == null ? null : r.getString( valueCol.cql() );
    }
}
