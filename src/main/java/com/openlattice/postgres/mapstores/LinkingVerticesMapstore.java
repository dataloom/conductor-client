package com.openlattice.postgres.mapstores;

import com.dataloom.hazelcast.HazelcastMap;
import com.dataloom.linking.LinkingVertex;
import com.dataloom.linking.LinkingVertexKey;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Sets;
import com.hazelcast.config.MapConfig;
import com.hazelcast.config.MapIndexConfig;
import com.openlattice.postgres.PostgresArrays;
import com.openlattice.postgres.PostgresColumnDefinition;
import com.openlattice.postgres.ResultSetAdapters;
import com.zaxxer.hikari.HikariDataSource;

import java.sql.Array;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.UUID;

import static com.openlattice.postgres.PostgresColumn.*;
import static com.openlattice.postgres.PostgresTable.LINKING_VERTICES;

public class LinkingVerticesMapstore extends AbstractBasePostgresMapstore<LinkingVertexKey, LinkingVertex> {

    public LinkingVerticesMapstore( HikariDataSource hds ) {
        super( HazelcastMap.LINKING_VERTICES.name(), LINKING_VERTICES, hds );
    }

    @Override protected List<PostgresColumnDefinition> keyColumns() {
        return ImmutableList.of( GRAPH_ID, VERTEX_ID );
    }

    @Override protected List<PostgresColumnDefinition> valueColumns() {
        return ImmutableList.of( GRAPH_DIAMETER, ENTITY_KEY_IDS );
    }

    @Override protected void bind( PreparedStatement ps, LinkingVertexKey key, LinkingVertex value )
            throws SQLException {
        bind( ps, key );

        ps.setDouble( 3, value.getDiameter() );
        Array entityKeyIds = PostgresArrays.createUuidArray( ps.getConnection(), value.getEntityKeys().stream() );
        ps.setArray( 4, entityKeyIds );

        // UPDATE
        ps.setDouble( 5, value.getDiameter() );
        ps.setArray( 6, entityKeyIds );
    }

    @Override protected void bind( PreparedStatement ps, LinkingVertexKey key ) throws SQLException {
        ps.setObject( 1, key.getGraphId() );
        ps.setObject( 2, key.getVertexId() );
    }

    @Override protected LinkingVertex mapToValue( ResultSet rs ) throws SQLException {
        return ResultSetAdapters.linkingVertex( rs );
    }

    @Override protected LinkingVertexKey mapToKey( ResultSet rs ) throws SQLException {
        return ResultSetAdapters.linkingVertexKey( rs );
    }

    @Override
    public MapConfig getMapConfig() {
        return super.getMapConfig()
                .addMapIndexConfig( new MapIndexConfig( "__key#graphId", false ) );
    }

    @Override public LinkingVertexKey generateTestKey() {
        return new LinkingVertexKey( UUID.randomUUID(), UUID.randomUUID() );
    }

    @Override public LinkingVertex generateTestValue() {
        return new LinkingVertex( 0.3, Sets.newHashSet( UUID.randomUUID() ) );
    }
}
