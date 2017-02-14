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

import com.dataloom.hazelcast.HazelcastMap;
import com.datastax.driver.core.BoundStatement;
import com.datastax.driver.core.Row;
import com.datastax.driver.core.Session;
import com.kryptnostic.conductor.rpc.odata.Table;
import com.kryptnostic.rhizome.cassandra.ColumnDef;
import com.kryptnostic.rhizome.mapstores.cassandra.AbstractStructuredCassandraMapstore;

public abstract class UUIDKeyMapstore<V> extends AbstractStructuredCassandraMapstore<UUID, V> {
    protected final ColumnDef keyCol;

    protected UUIDKeyMapstore( HazelcastMap map, Session session, Table table, ColumnDef keyCol ) {
        super( map.name(), session, table.getBuilder() );
        this.keyCol = keyCol;
    }

    @Override
    protected BoundStatement bind( UUID key, BoundStatement bs ) {
        return bs
                .setUUID( keyCol.cql(), key );
    }

    @Override
    protected UUID mapKey( Row row ) {
        return row.getUUID( keyCol.cql() );
    }

    @Override
    public UUID generateTestKey() {
        return UUID.randomUUID();
    }
}
