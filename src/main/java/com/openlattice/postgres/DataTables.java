/*
 * Copyright (C) 2018. OpenLattice, Inc
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

package com.openlattice.postgres;

import com.openlattice.edm.PostgresEdmTypeConverter;
import com.openlattice.edm.type.PropertyType;
import java.util.UUID;

import static com.openlattice.postgres.PostgresColumn.*;
import static com.openlattice.postgres.PostgresDatatype.TIMESTAMPTZ;

/**
 * @author Matthew Tamayo-Rios &lt;matthew@openlattice.com&gt;
 */
public class DataTables {
    public static final PostgresColumnDefinition LAST_INDEX     = new PostgresColumnDefinition(
            LAST_INDEX_FIELD,
            TIMESTAMPTZ )
            .withDefault( "'-infinity'" )
            .notNull();
    public static final PostgresColumnDefinition LAST_LINK      = new PostgresColumnDefinition(
            LAST_LINK_FIELD,
            TIMESTAMPTZ )
            .withDefault( "'-infinity'" )
            .notNull();
    public static final PostgresColumnDefinition LAST_WRITE     = new PostgresColumnDefinition(
            LAST_WRITE_FIELD,
            TIMESTAMPTZ )
            .withDefault( "'-infinity'" )
            .notNull();
    public static final  PostgresColumnDefinition OWNERS         = new PostgresColumnDefinition(
            "owners",
            PostgresDatatype.UUID );
    public static final  PostgresColumnDefinition READERS        = new PostgresColumnDefinition(
            "readers",
            PostgresDatatype.UUID );
    public static final  PostgresColumnDefinition WRITERS        = new PostgresColumnDefinition(
            "writers",
            PostgresDatatype.UUID );

    public static String propertyTableName( UUID propertyTypeId ) {
        return "pt_" + propertyTypeId.toString();
    }

    public static String entityTableName( UUID entitySetId ) {
        return "es_" + entitySetId.toString();
    }

    public static String quote( String s ) {
        return "\"" + s + "\"";
    }

    public static PostgresColumnDefinition value( PropertyType pt ) {
        //We name the column after the full qualified name of the property so that in joins it transfers cleanly
        return new PostgresColumnDefinition( quote( pt.getType().getFullQualifiedNameAsString() ),
                PostgresEdmTypeConverter.map( pt.getDatatype() ) );

    }

    public static PostgresTableDefinition buildPropertyTableDefinition(
            PropertyType propertyType ) {
        final String idxPrefix = propertyTableName( propertyType.getId() );

        PostgresColumnDefinition valueColumn = value( propertyType );
        PostgresTableDefinition ptd = new CitusDistributedTableDefinition(
                quote( idxPrefix ) )
                .addColumns(
                        ENTITY_SET_ID,
                        ID_VALUE,
                        HASH,
                        valueColumn,
                        VERSION,
                        VERSIONS,
                        LAST_WRITE,
                        LAST_PROPAGATE,
                        READERS,
                        WRITERS,
                        OWNERS )
                .primaryKey( ENTITY_SET_ID, ID_VALUE, HASH )
                .distributionColumn( ID );

        PostgresIndexDefinition idIndex = new PostgresColumnsIndexDefinition( ptd, ID_VALUE )
                .name( quote( idxPrefix + "_id_idx" ) )
                .ifNotExists();

        if ( !propertyType.getPostgresIndexType().equals( IndexType.NONE ) ) {
            PostgresIndexDefinition valueIndex = new PostgresColumnsIndexDefinition( ptd, valueColumn )
                    .method( propertyType.getPostgresIndexType() )
                    .name( quote( idxPrefix + "_value_idx" ) )
                    .ifNotExists();

            ptd.addIndexes( valueIndex );
        }

        PostgresIndexDefinition entitySetIdIndex = new PostgresColumnsIndexDefinition( ptd, ENTITY_SET_ID )
                .name( quote( idxPrefix + "_entity_set_id_idx" ) )
                .ifNotExists();

        PostgresIndexDefinition versionIndex = new PostgresColumnsIndexDefinition( ptd, VERSION )
                .name( quote( idxPrefix + "_version_idx" ) )
                .ifNotExists()
                .desc();

        //TODO: Re-consider the value of having gin index on versions field. Checking if a value was written
        //in a specific version seems like a rare operations
        PostgresIndexDefinition versionsIndex = new PostgresColumnsIndexDefinition( ptd, VERSIONS )
                .name( quote( idxPrefix + "_versions_idx" ) )
                .method( IndexType.GIN )
                .ifNotExists();

        PostgresIndexDefinition lastWriteIndex = new PostgresColumnsIndexDefinition( ptd, LAST_WRITE )
                .name( quote( idxPrefix + "_last_write_idx" ) )
                .ifNotExists()
                .desc();

        PostgresIndexDefinition readersIndex = new PostgresColumnsIndexDefinition( ptd, READERS )
                .name( quote( idxPrefix + "_readers_idx" ) )
                .ifNotExists();

        PostgresIndexDefinition writersIndex = new PostgresColumnsIndexDefinition( ptd, WRITERS )
                .name( quote( idxPrefix + "_writers_idx" ) )
                .ifNotExists();

        PostgresIndexDefinition ownersIndex = new PostgresColumnsIndexDefinition( ptd, OWNERS )
                .name( quote( idxPrefix + "_owners_idx" ) )
                .ifNotExists();

        ptd.addIndexes(
                idIndex,
                entitySetIdIndex,
                versionIndex,
                versionsIndex,
                lastWriteIndex,
                readersIndex,
                writersIndex,
                ownersIndex );

        return ptd;
    }
}
