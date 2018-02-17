package com.dataloom.linking.mapstores;

import java.util.Set;
import java.util.UUID;

import com.hazelcast.config.MapConfig;
import com.hazelcast.config.MapIndexConfig;
import org.apache.commons.lang.math.RandomUtils;

import com.dataloom.hazelcast.HazelcastMap;
import com.dataloom.linking.LinkingVertex;
import com.dataloom.linking.LinkingVertexKey;
import com.datastax.driver.core.BoundStatement;
import com.datastax.driver.core.ResultSet;
import com.datastax.driver.core.Row;
import com.datastax.driver.core.Session;
import com.google.common.collect.ImmutableSet;
import com.kryptnostic.conductor.rpc.odata.Table;
import com.kryptnostic.datastore.cassandra.CommonColumns;
import com.kryptnostic.rhizome.mapstores.cassandra.AbstractStructuredCassandraMapstore;

public class LinkingVerticesMapstore extends AbstractStructuredCassandraMapstore<LinkingVertexKey, LinkingVertex> {
    public LinkingVerticesMapstore( Session session ) {
        super( HazelcastMap.LINKING_VERTICES.name(), session, Table.LINKING_VERTICES.getBuilder() );
    }

    @Override
    protected BoundStatement bind( LinkingVertexKey key, BoundStatement bs ) {
        return bs.setUUID( CommonColumns.VERTEX_ID.cql(), key.getVertexId() )
                .setUUID( CommonColumns.GRAPH_ID.cql(), key.getGraphId() );
    }

    @Override
    protected BoundStatement bind( LinkingVertexKey key, LinkingVertex value, BoundStatement bs ) {
        return bind( key, bs ).setDouble( CommonColumns.GRAPH_DIAMETER.cql(), value.getDiameter() )
                .setSet( CommonColumns.ENTITY_KEY_IDS.cql(), value.getEntityKeys(), UUID.class );

    }

    @Override
    protected LinkingVertexKey mapKey( Row row ) {
        if ( row == null ) {
            return null;
        }
        UUID graphId = row.getUUID( CommonColumns.GRAPH_ID.cql() );
        UUID vertexId = row.getUUID( CommonColumns.VERTEX_ID.cql() );
        return new LinkingVertexKey( graphId, vertexId );
    }

    @Override
    protected LinkingVertex mapValue( ResultSet rs ) {
        Row row = rs.one();
        if ( row == null ) {
            return null;
        }
        double diameter = row.getDouble( CommonColumns.GRAPH_DIAMETER.cql() );
        Set<UUID> entityKeys = row.getSet( CommonColumns.ENTITY_KEY_IDS.cql(), UUID.class );
        return new LinkingVertex( diameter, entityKeys );
    }

    @Override
    public LinkingVertexKey generateTestKey() {
        return new LinkingVertexKey( UUID.randomUUID(), UUID.randomUUID() );
    }

    @Override
    public LinkingVertex generateTestValue() {
        return randomLinkingVertex();
    }

    @Override
    public MapConfig getMapConfig() {
        return super.getMapConfig()
                .addMapIndexConfig( new MapIndexConfig( "__key#graphId", false ) );
    }

    public static LinkingVertex randomLinkingVertex() {
        return new LinkingVertex(
                RandomUtils.nextDouble(),
                ImmutableSet.of( UUID.randomUUID(), UUID.randomUUID() ) );
    }
}
