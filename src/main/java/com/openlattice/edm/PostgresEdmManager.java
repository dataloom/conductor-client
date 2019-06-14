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

package com.openlattice.edm;

import static com.openlattice.postgres.DataTables.propertyTableName;
import static com.openlattice.postgres.DataTables.quote;
import static com.openlattice.postgres.PostgresColumn.ENTITY_SET_ID;
import static com.openlattice.postgres.PostgresColumn.ENTITY_TYPE_ID;
import static com.openlattice.postgres.PostgresColumn.LINKING_ID;
import static com.openlattice.postgres.PostgresTable.ENTITY_KEY_IDS;

import com.codahale.metrics.annotation.ExceptionMetered;
import com.codahale.metrics.annotation.Timed;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.hazelcast.core.HazelcastInstance;
import com.hazelcast.core.IMap;
import com.hazelcast.query.Predicates;
import com.openlattice.authorization.Permission;
import com.openlattice.authorization.Principal;
import com.openlattice.data.PropertyUsageSummary;
import com.openlattice.edm.type.PropertyType;
import com.openlattice.hazelcast.HazelcastMap;
import com.openlattice.postgres.DataTables;
import com.openlattice.postgres.PostgresArrays;
import com.openlattice.postgres.PostgresColumn;
import com.openlattice.postgres.PostgresTable;
import com.openlattice.postgres.PostgresTableDefinition;
import com.openlattice.postgres.PostgresTableManager;
import com.openlattice.postgres.ResultSetAdapters;
import com.openlattice.postgres.mapstores.EntitySetMapstore;
import com.openlattice.postgres.streams.PostgresIterable;
import com.openlattice.postgres.streams.StatementHolder;
import com.zaxxer.hikari.HikariDataSource;
import java.sql.Array;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.apache.olingo.commons.api.edm.FullQualifiedName;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * @author Matthew Tamayo-Rios &lt;matthew@openlattice.com&gt;
 */
public class PostgresEdmManager implements DbEdmManager {
    private static final String getAllEntitySets = "SELECT * FROM " + PostgresTable.ENTITY_SETS.getName();
    private static final Logger logger           = LoggerFactory.getLogger( PostgresEdmManager.class );

    private final PostgresTableManager ptm;
    private final HikariDataSource     hds;

    private final IMap<UUID, EntitySet> entitySets;

    private final String ENTITY_SETS;
    private final String PROPERTY_TYPES;
    private final String ENTITY_TYPE_ID_FIELD;
    private final String ENTITY_SET_ID_FIELD;
    private final String ENTITY_SET_NAME_FIELD;
    private final String NAME_FIELD;

    public PostgresEdmManager( HikariDataSource hds, PostgresTableManager ptm, HazelcastInstance hazelcastInstance ) {
        this.ptm = ptm;//new PostgresTableManager( hds );
        this.hds = hds;

        this.entitySets = hazelcastInstance.getMap( HazelcastMap.ENTITY_SETS.name() );

        // Tables
        this.ENTITY_SETS = PostgresTable.ENTITY_SETS.getName(); // "entity_sets"
        this.PROPERTY_TYPES = PostgresTable.PROPERTY_TYPES.getName(); // "property_types"

        // Properties
        this.ENTITY_TYPE_ID_FIELD = PostgresColumn.ENTITY_TYPE_ID_FIELD;
        this.ENTITY_SET_ID_FIELD = PostgresColumn.ENTITY_SET_ID_FIELD;
        this.ENTITY_SET_NAME_FIELD = PostgresColumn.ENTITY_SET_NAME_FIELD;
        this.NAME_FIELD = PostgresColumn.NAME_FIELD;
    }

    @Override
    @ExceptionMetered
    @Timed
    public void createEntitySet( EntitySet entitySet, Collection<PropertyType> propertyTypes ) {
        try {
            for ( PropertyType pt : propertyTypes ) {
                createPropertyTypeTableIfNotExist( pt );
            }
        } catch ( SQLException e ) {
            logger.error( "Unable to create entity set {}", entitySet );
        }
        //Method is idempotent and should be re-executable in case of a failure.
    }

    @Override public void deleteEntitySet(
            EntitySet entitySet, Collection<PropertyType> propertyTypes ) {
        removePropertiesFromEntitySet( entitySet, propertyTypes );
    }

    @Override public void removePropertiesFromEntitySet( EntitySet entitySet, PropertyType... propertyTypes ) {
        removePropertiesFromEntitySet( entitySet, Arrays.asList( propertyTypes ) );
    }

    @Override public void removePropertiesFromEntitySet(
            EntitySet entitySet, Collection<PropertyType> propertyTypes ) {
        //        for ( PropertyType propertyType : propertyTypes ) {
        //            PostgresTableDefinition ptd = DataTables.buildPropertyTableDefinition( entitySet, propertyType );
        //            dropTable( ptd.getName() );
        //        }
    }

    @Override
    public void grant(
            Principal principal,
            EntitySet entitySet,
            Collection<PropertyType> propertyTypes,
            EnumSet<Permission> permissions ) {
        if ( permissions.isEmpty() ) {
            //I hate early returns but nesting will get too messy and this is pretty clear that granting
            //no permissions is a no-op.
            return;
        }

        List<String> tables = new ArrayList<>( propertyTypes.size() + 1 );
        tables.add( DataTables.entityTableName( entitySet.getId() ) );

        for ( PropertyType pt : propertyTypes ) {
            tables.add( propertyTableName( pt.getId() ) );
        }

        String principalId = principal.getId();

        for ( String table : tables ) {
            for ( Permission p : permissions ) {
                String postgresPrivilege = DataTables.mapPermissionToPostgresPrivilege( p );
                String grantQuery = String.format( "GRANT %1$s ON TABLE %2$s TO %3$s",
                        postgresPrivilege, quote( table ), principalId );
                try ( Connection conn = hds.getConnection(); Statement s = conn.createStatement() ) {
                    s.execute( grantQuery );
                } catch ( SQLException e ) {
                    logger.error( "Unable to execute grant query {}", grantQuery, e );
                }
            }
        }
    }

    public void revoke(
            Principal principal,
            EntitySet entitySet,
            Collection<PropertyType> propertyTypes,
            EnumSet<Permission> permissions ) {

    }

    public PostgresIterable<EntitySet> getAllEntitySets() {
        return new PostgresIterable<>(
                () -> {
                    try {
                        Connection connection = hds.getConnection();
                        connection.setAutoCommit( false );
                        PreparedStatement ps = connection.prepareStatement( getAllEntitySets );
                        ps.setFetchSize( 10000 );
                        ResultSet rs = ps.executeQuery();
                        return new StatementHolder( connection, ps, rs );
                    } catch ( SQLException e ) {
                        logger.error( "Unable to load all entity sets", e );
                        throw new IllegalStateException( "Unable to load entity sets.", e );
                    }
                },
                rs -> {
                    try {
                        return ResultSetAdapters.entitySet( rs );
                    } catch ( SQLException e ) {
                        logger.error( "Unable to read entity set", e );
                        throw new IllegalStateException( "Unable to read entity set.", e );
                    }
                }
        );
    }

    public Iterable<EntitySet> getAllEntitySetsForType( UUID entityTypeId ) {
        String getEntitySetsByType = String.format( "SELECT * FROM %1$s WHERE %2$s = ?", ENTITY_SETS,
                ENTITY_TYPE_ID_FIELD );
        try ( Connection connection = hds.getConnection();
                PreparedStatement ps = connection.prepareStatement( getEntitySetsByType ) ) {
            List<EntitySet> result = Lists.newArrayList();
            ps.setObject( 1, entityTypeId );
            ResultSet rs = ps.executeQuery();
            while ( rs.next() ) {
                result.add( ResultSetAdapters.entitySet( rs ) );
            }

            return result;
        } catch ( SQLException e ) {
            logger.debug( "Unable to load entity sets for entity type id {}", entityTypeId.toString(), e );
            return ImmutableList.of();
        }
    }

    /**
     * In-memory version of
     * {@link com.openlattice.data.storage.PostgresEntityDataQueryService#getLinkingEntitySetIdsOfEntitySet(UUID)}
     */
    public Set<EntitySet> getAllLinkingEntitySetsForEntitySet( UUID entitySetId ) {
        return ImmutableSet.copyOf( entitySets
                .values( Predicates.equal( EntitySetMapstore.LINKED_ENTITY_SET_INDEX, entitySetId ) ) );
    }

    /**
     * Duplicate of
     * {@link  com.openlattice.data.EntityDatastore#getLinkingIdsByEntitySetIds(Set)}
     */
    public Map<UUID, Set<UUID>> getLinkingIdsByEntitySetIds( Set<UUID> entitySetIds ) {
        String query =
                "SELECT " + ENTITY_SET_ID.getName() + ", array_agg(" + LINKING_ID.getName() + ") AS " + LINKING_ID
                        .getName() +
                        " FROM " + ENTITY_KEY_IDS.getName() +
                        " WHERE " + LINKING_ID.getName() + " IS NOT NULL AND " + ENTITY_SET_ID.getName()
                        + " = ANY( ? ) " +
                        " GROUP BY " + ENTITY_SET_ID.getName();

        try ( Connection connection = hds.getConnection();
                PreparedStatement ps = connection.prepareStatement( query ) ) {
            Map<UUID, Set<UUID>> result = Maps.newHashMap();
            Array arr = PostgresArrays.createUuidArray( connection, entitySetIds );
            ps.setArray( 1, arr );
            ResultSet rs = ps.executeQuery();
            while ( rs.next() ) {
                result.put( ResultSetAdapters.entitySetId( rs ), ResultSetAdapters.linkingIds( rs ) );
            }

            return result;
        } catch ( SQLException e ) {
            logger.debug( "Unable to load linking ids by entity sets ids for entity sets {}", entitySetIds, e );
            return Map.of();
        }
    }

    public Iterable<PropertyUsageSummary> getPropertyUsageSummary( String propertyTableName ) {
        String getPropertyTypeSummary =
                String.format(
                        "SELECT %1$s , %6$s AS %2$s , %3$s, COUNT(*) FROM %4$s LEFT JOIN %5$s ON %3$s = %5$s.id AND %4$s.version > 0 GROUP BY ( %1$s , %2$s, %3$s )",
                        ENTITY_TYPE_ID_FIELD,
                        ENTITY_SET_NAME_FIELD,
                        ENTITY_SET_ID_FIELD,
                        propertyTableName,
                        ENTITY_SETS,
                        NAME_FIELD );
        return new PostgresIterable<>( () -> {
            try {
                Connection connection = hds.getConnection();
                PreparedStatement ps = connection.prepareStatement( getPropertyTypeSummary );
                ResultSet rs = ps.executeQuery();
                return new StatementHolder( connection, ps, rs );
            } catch ( SQLException e ) {
                logger.error( "Unable to create statement holder!", e );
                throw new IllegalStateException( "Unable to create statement holder.", e );
            }
        }, rs -> {
            try {
                return ResultSetAdapters.propertyUsageSummary( rs );
            } catch ( SQLException e ) {
                logger.error( "Unable to load property summary information.", e );
                throw new IllegalStateException( "Unable to load property summary information.", e );
            }
        } );
    }

    @ExceptionMetered
    @Timed
    public void createPropertyTypeIfNotExist( PropertyType propertyType ) {
        try {
            createPropertyTypeTableIfNotExist( propertyType );
        } catch ( SQLException e ) {
            logger.error( "Unable to process property type creation.", e );
        }
    }

    /*
     * Quick note on this function. It is IfNotExists only because PostgresTableDefinition queries
     * all include an if not exists. If the behavior of that class changes this function should be updated
     * appropriately.
     */
    @VisibleForTesting
    public void createPropertyTypeTableIfNotExist( PropertyType propertyType ) throws SQLException {
        PostgresTableDefinition ptd = DataTables.buildPropertyTableDefinition( propertyType );
        ptm.registerTables( ptd );
    }

    public void updatePropertyTypeFqn( PropertyType propertyType, FullQualifiedName newFqn ) {
        String propertyTableName = DataTables.quote( DataTables.propertyTableName( propertyType.getId() ) );
        String oldType = DataTables.quote( propertyType.getType().getFullQualifiedNameAsString() );
        String newType = DataTables.quote( newFqn.getFullQualifiedNameAsString() );
        String updatePropertyTypeFqn = String.format( "ALTER TABLE %1$s RENAME COLUMN %2$s TO %3$s",
                propertyTableName, oldType, newType );

        try ( Connection connection = hds.getConnection();
                PreparedStatement preparedStatement = connection.prepareStatement( updatePropertyTypeFqn ) ) {
            preparedStatement.executeUpdate();
        } catch ( SQLException e ) {
            logger.error( "Unable to update column full qualified name in propertytype", e );
        }
    }

    public Map<UUID, Long> countEntitySetsOfEntityTypes( Set<UUID> entityTypeIds ) {
        String query =
                "SELECT " + ENTITY_TYPE_ID.getName() + ", COUNT(*) FROM " + ENTITY_SETS + " WHERE " + ENTITY_TYPE_ID
                        .getName() + " = ANY( ? ) GROUP BY " + ENTITY_TYPE_ID.getName();
        try ( Connection connection = hds.getConnection();
                PreparedStatement ps = connection.prepareStatement( query ) ) {
            Map<UUID, Long> result = Maps.newHashMap();
            Array arr = PostgresArrays.createUuidArray( connection, entityTypeIds );
            ps.setArray( 1, arr );
            ResultSet rs = ps.executeQuery();
            while ( rs.next() ) {
                result.put( ResultSetAdapters.entityTypeId( rs ), ResultSetAdapters.count( rs ) );
            }

            return result;
        } catch ( SQLException e ) {
            logger.debug( "Unable to count entity sets for entity type ids {}", entityTypeIds, e );
            return Map.of();
        }
    }

}
