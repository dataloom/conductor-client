package com.dataloom.linking.mapstores;

import java.util.UUID;

import com.openlattice.data.EntityKey;
import com.dataloom.hazelcast.HazelcastMap;
import com.dataloom.linking.LinkingEntityKey;
import com.openlattice.mapstores.TestDataFactory;
import com.datastax.driver.core.BoundStatement;
import com.datastax.driver.core.ResultSet;
import com.datastax.driver.core.Row;
import com.datastax.driver.core.Session;
import com.kryptnostic.conductor.rpc.odata.Table;
import com.kryptnostic.datastore.cassandra.CommonColumns;
import com.kryptnostic.rhizome.mapstores.cassandra.AbstractStructuredCassandraMapstore;

public class LinkingEntityVerticesMapstore extends AbstractStructuredCassandraMapstore<LinkingEntityKey, UUID> {
    public LinkingEntityVerticesMapstore( Session session ) {
        super( HazelcastMap.LINKING_ENTITY_VERTICES.name(), session, Table.LINKING_ENTITY_VERTICES.getBuilder() );
    }

    @Override
    protected BoundStatement bind( LinkingEntityKey key, BoundStatement bs ) {
        EntityKey ek = key.getEntityKey();
        return bs.setUUID( CommonColumns.ENTITY_SET_ID.cql(), ek.getEntitySetId() )
                .setString( CommonColumns.ENTITYID.cql(), ek.getEntityId() )
                .setUUID( CommonColumns.SYNCID.cql(), ek.getSyncId() )
                .setUUID( CommonColumns.GRAPH_ID.cql(), key.getGraphId() );
    }

    @Override
    protected BoundStatement bind( LinkingEntityKey key, UUID value, BoundStatement bs ) {
        return bind( key, bs ).setUUID( CommonColumns.VERTEX_ID.cql(), value );

    }

    @Override
    protected LinkingEntityKey mapKey( Row row ) {
        if ( row == null ) {
            return null;
        }
        UUID entitySetId = row.getUUID( CommonColumns.ENTITY_SET_ID.cql() );
        UUID graphId = row.getUUID( CommonColumns.GRAPH_ID.cql() );
        String entityId = row.getString( CommonColumns.ENTITYID.cql() );
        UUID syncId = row.getUUID( CommonColumns.SYNCID.cql() );
        return new LinkingEntityKey( graphId, new EntityKey( entitySetId, entityId, syncId ) );
    }

    @Override
    protected UUID mapValue( ResultSet rs ) {
        Row row = rs.one();
        return row == null ? null : row.getUUID( CommonColumns.VERTEX_ID.cql() );
    }

    @Override
    public LinkingEntityKey generateTestKey() {
        return new LinkingEntityKey( UUID.randomUUID(), TestDataFactory.entityKey() );
    }

    @Override
    public UUID generateTestValue() {
        return UUID.randomUUID();
    }
}