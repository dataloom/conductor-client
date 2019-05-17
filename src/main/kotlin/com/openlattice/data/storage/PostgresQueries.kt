/*
 * Copyright (C) 2018. OpenLattice, Inc.
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
 *
 */

package com.openlattice.data.storage

import com.openlattice.analysis.requests.Filter
import com.openlattice.postgres.DataTables.*
import com.openlattice.postgres.PostgresColumn.*
import com.openlattice.postgres.PostgresTable.IDS
import com.openlattice.postgres.PostgresTable.QUERIES
import org.slf4j.LoggerFactory
import java.util.*

/**
 * Handles reading data from linked entity sets.
 */
private val logger = LoggerFactory.getLogger(PostgresQueries::class.java)
const val ENTITIES_TABLE_ALIAS = "entity_key_ids"
const val LEFT_JOIN = "LEFT JOIN"
const val INNER_JOIN = "INNER JOIN"
const val FILTERED_ENTITY_KEY_IDS = "filtered_entity_key_ids"
val entityKeyIdColumnsList = listOf(ENTITY_SET_ID.name, ID_VALUE.name)
val linkingEntityKeyIdColumnsList = listOf(ENTITY_SET_ID.name, LINKING_ID.name)
val entityKeyIdColumns = entityKeyIdColumnsList.joinToString(",")

private val ALLOWED_LINKING_METADATA_OPTIONS = EnumSet.of(
        MetadataOption.LAST_WRITE,
        MetadataOption.ENTITY_KEY_IDS,
        MetadataOption.ENTITY_SET_IDS
)

private val ALLOWED_NON_LINKING_METADATA_OPTIONS = EnumSet.allOf(MetadataOption::class.java).minus(
        listOf(
                MetadataOption.ENTITY_KEY_IDS,
                MetadataOption.ENTITY_SET_IDS
        )
)

internal class PostgresQueries

/**
 *
 */
fun selectEntitySetWithCurrentVersionOfPropertyTypes(
        queryId: UUID,
        entityKeyIds: Map<UUID, Optional<Set<UUID>>>,
        propertyTypes: Map<UUID, String>,
        returnedPropertyTypes: Collection<UUID>,
        authorizedPropertyTypes: Map<UUID, Set<UUID>>,
        propertyTypeFilters: Map<UUID, Set<Filter>>,
        metadataOptions: Set<MetadataOption>,
        binaryPropertyTypes: Map<UUID, Boolean>,
        linking: Boolean,
        omitEntitySetId: Boolean,
        metadataFilters: String = ""
): String {
    val withClause = buildWithClause(queryId, entityKeyIds, linking)
    val joinColumns = getJoinColumns(linking, omitEntitySetId)
//    val entitiesClause = buildEntitiesClause(entityKeyIds, linking)
    val entitiesSubquerySql = selectEntityKeyIdsWithCurrentVersionSubquerySql(
            metadataOptions,
            linking,
            omitEntitySetId,
            joinColumns
    )

    val dataColumns = joinColumns
            .union(getMetadataOptions(metadataOptions, linking)) // omit metadataoptions from linking
            .union(returnedPropertyTypes.map { propertyTypes.getValue(it) })
            .joinToString(",")

    // for performance, we place inner joins of filtered property tables at the end
    val orderedPropertyTypesByJoin = propertyTypes.toList()
            .sortedBy { propertyTypeFilters.containsKey(it.first) }.toMap()
    val propertyTableJoins =
            orderedPropertyTypesByJoin
                    //Exclude any property from the join where no property is authorized.
                    .filter { pt -> authorizedPropertyTypes.any { it.value.contains(pt.key) } }
                    .map {
                        val joinType = if (propertyTypeFilters.containsKey(it.key)) INNER_JOIN else LEFT_JOIN
                        val propertyTypeEntitiesClause = buildPropertyTypeEntitiesClause(
                                entityKeyIds,
                                it.key,
                                authorizedPropertyTypes
                        )
                        val subQuerySql = selectCurrentVersionOfPropertyTypeSql(
                                propertyTypeEntitiesClause,
                                it.key,
                                propertyTypeFilters[it.key] ?: setOf(),
                                it.value,
                                joinColumns,
                                metadataFilters
                        )
                        "$joinType $subQuerySql USING (${joinColumns.joinToString(",")})"
                    }
                    .joinToString("\n")

    val fullQuery = "$withClause SELECT $dataColumns FROM $entitiesSubquerySql $propertyTableJoins"
    return fullQuery
}

internal fun buildWithClause(queryId: UUID, entityKeyIds: Map<UUID, Optional<Set<UUID>>>, linking: Boolean): String {
    val joinColumns = if (linking) {
        listOf(ENTITY_SET_ID.name, ID_VALUE.name, LINKING_ID.name)
    } else {
        listOf(ENTITY_SET_ID.name, ID_VALUE.name)
    }
    val selectColumns = joinColumns.joinToString(",") { "${IDS.name}.$it AS $it" }
    val entitiesJoinCondition = buildEntitiesJoinCondition(IDS.name, linking)
    val linkingClause = if (linking) "AND ${LINKING_ID.name} IS NOT NULL" else ""

    val emptyEntitySetIds = entityKeyIds.asSequence().filter { it.value.isEmpty }
    val completeEntitySetsUnionSql = if (emptyEntitySetIds.iterator().hasNext()) { // if there are entity set ids with no entity ids in entityKeyIds
        val completeEntitySets = emptyEntitySetIds.joinToString(",") { "'${it.key}'" }
        "UNION SELECT $selectColumns FROM ${IDS.name} WHERE ${ENTITY_SET_ID.name} IN ($completeEntitySets)"
    } else {
        ""
    }

    val queriesSql = "SELECT $selectColumns FROM ${IDS.name} INNER JOIN ${QUERIES.name} ON $entitiesJoinCondition WHERE ${VERSION.name} > 0 AND ${QUERY_ID.name} = '$queryId' $linkingClause"

    return "WITH $FILTERED_ENTITY_KEY_IDS AS ( $queriesSql $completeEntitySetsUnionSql ) "
}


/**
 *
 */
fun selectEntitySetWithPropertyTypesAndVersionSql(
        entityKeyIds: Map<UUID, Optional<Set<UUID>>>,
        propertyTypes: Map<UUID, String>,
        returnedPropertyTypes: Collection<UUID>,
        authorizedPropertyTypes: Map<UUID, Set<UUID>>,
        propertyTypeFilters: Map<UUID, Set<Filter>>,
        metadataOptions: Set<MetadataOption>,
        version: Long,
        binaryPropertyTypes: Map<UUID, Boolean>,
        linking: Boolean,
        omitEntitySetId: Boolean
): String {
    val joinColumns = getJoinColumns(linking, omitEntitySetId)
    val entitiesClause = buildEntitiesClause(entityKeyIds, linking)

    val entitiesSubquerySql = if (linking) {
        selectLinkingIdsFilteredByVersionSubquerySql(entitiesClause, version, joinColumns)
    } else {
        selectEntityKeyIdsFilteredByVersionSubquerySql(entitiesClause, version, metadataOptions, joinColumns)
    }

    val dataColumns = joinColumns
            .union(getMetadataOptions(metadataOptions, linking)) // omit metadataoptions from linking
            .union(returnedPropertyTypes.map { propertyTypes.getValue(it) })
            .filter(String::isNotBlank)
            .joinToString(",")

    // for performance, we place inner joins of filtered property tables at the end
    val orderedPropertyTypesByJoin = propertyTypes.toList()
            .sortedBy { propertyTypeFilters.containsKey(it.first) }.toMap()
    val propertyTableJoins =
            orderedPropertyTypesByJoin
                    //Exclude any property from the join where no property is authorized.
                    .filter { pt -> authorizedPropertyTypes.any { it.value.contains(pt.key) } }
                    .map {
                        val joinType = if (propertyTypeFilters.containsKey(it.key)) INNER_JOIN else LEFT_JOIN
                        val propertyTypeEntitiesClause = buildPropertyTypeEntitiesClause(
                                entityKeyIds,
                                it.key,
                                authorizedPropertyTypes
                        )
                        val subQuerySql = selectVersionOfPropertyTypeInEntitySetSql(
                                propertyTypeEntitiesClause,
                                it.key,
                                it.value,
                                version,
                                propertyTypeFilters[it.key] ?: setOf<Filter>(),
                                linking,
                                binaryPropertyTypes[it.key]!!,
                                joinColumns
                        )
                        "$joinType $subQuerySql USING (${joinColumns.joinToString(",")}) "
                    }
                    .joinToString("\n")

    return "SELECT $dataColumns FROM $entitiesSubquerySql as $ENTITIES_TABLE_ALIAS $propertyTableJoins"

}

/**
 * This routine generates SQL that performs the following steps:
 *
 * 1. Build the property table name, selection columns,
 * 2. Choose the right selection columns for this level.
 * 3. Prepare the property value aggregate fragment.
 * 4. Prepare the subquery fragment for retrieving entity key ids satisfying the provided clauses.
 *
 * @param entitiesClause The clause that controls which entities will be evaluated by this query.
 *
 * @return A SQL subquery statement that will return all
 */
internal fun selectVersionOfPropertyTypeInEntitySetSql(
        entitiesClause: String,
        propertyTypeId: UUID,
        fqn: String,
        version: Long,
        filters: Set<Filter>,
        linking: Boolean,
        binary: Boolean,
        joinColumns: List<String>
): String {
    val propertyTable = quote(propertyTableName(propertyTypeId))

    val selectColumns = joinColumns.joinToString(",")

    val arrayAgg = arrayAggSql(fqn)

    val selectPropertyTypeIdsFilteredByVersion = selectPropertyTypeDataKeysFilteredByVersionSubquerySql(
            entitiesClause,
            buildFilterClause(fqn, filters),
            propertyTable,
            version
    )

    /*
     * The reason that the linking id is joined in at this level is so that filtering works properly when tables are
     * joined together.
     */
    val linkingIdSubquerySql =
            if (linking) {
                "INNER JOIN (SELECT $entityKeyIdColumns,${LINKING_ID.name} FROM ${IDS.name}) as linking_ids USING($entityKeyIdColumns)"
            } else {
                ""
            }
    //We do an inner join here since we are effectively doing a self-join.
    return "(SELECT $selectColumns, $arrayAgg " +
            "FROM $selectPropertyTypeIdsFilteredByVersion " +
            linkingIdSubquerySql +
            "INNER JOIN $propertyTable USING($entityKeyIdColumns) " +
            "GROUP BY($selectColumns)) as $propertyTable "
}

internal fun selectCurrentVersionOfPropertyTypeSql(
        entitiesClause: String,
        propertyTypeId: UUID,
        filters: Set<Filter>,
        fqn: String,
        joinColumns: List<String>,
        metadataFilters: String = ""
): String {
    val propertyTable = quote(propertyTableName(propertyTypeId))
    val groupByColumns = joinColumns.joinToString(",")
    val arrayAgg = arrayAggSql(fqn)
    val filtersClause = buildFilterClause(fqn, filters)

    return "(SELECT $groupByColumns, $arrayAgg " +
            "FROM $propertyTable INNER JOIN $FILTERED_ENTITY_KEY_IDS USING ($groupByColumns) " +
            "WHERE ${VERSION.name} > 0 $entitiesClause $filtersClause $metadataFilters" +
            "GROUP BY ($groupByColumns)) as $propertyTable "
}

/**
 * This routine generates SQL that performs the following steps:
 *
 * 1. Unnests the versions arrays for the given ids.
 * 2. Filters out any versions greater than the request version.
 * 3. Computes max and max absolute value of the remaining versions,
 * 4. Filters out any entity keys where the max and max absolute
 *    value are not identical, which will only happen when the
 *    greatest present version has been tombstoned for that property.
 *
 * @param entitiesClause The clause that controls which entities will be evaluated by this query.
 * @param filtersClause The clause that controls which filters will be applied when performing the read
 * @param propertyTable The property table to use for building the query.
 * @param version The version of the properties being read.
 *
 * @return A named SQL subquery fragment consisting of all entity key satisfying the provided clauses.
 */
internal fun selectPropertyTypeDataKeysFilteredByVersionSubquerySql(
        entitiesClause: String,
        filtersClause: String,
        propertyTable: String,
        version: Long
): String {
    return "(SELECT $entityKeyIdColumns " +
            "FROM ( SELECT $entityKeyIdColumns, max(versions) as abs_max, max(abs(versions)) as max_abs " +
            "       FROM (  SELECT $entityKeyIdColumns, unnest(versions) as versions " +
            "           FROM $propertyTable " +
            "           WHERE TRUE $entitiesClause $filtersClause) as $EXPANDED_VERSIONS " +
            "       WHERE abs(versions) <= $version " +
            "       GROUP BY($entityKeyIdColumns)) as unfiltered_data_keys " +
            "WHERE max_abs=abs_max ) as data_keys "

}


/**
 * This routine generates SQL that performs the following steps:
 *
 * 1. Unnests the versions arrays for the given ids.
 * 2. Filters out any versions greater than the request version.
 * 3. Computes max and max absolute value of the remaining versions,
 * 4. Filters out any entity keys where the max and max absolute
 *    value are not identical, which will only happen when the
 *    greatest present version has been tombstoned for that property.
 *
 *  If metadata options are reuquested it does an additional level of nesting to get metadata from entity key ids
 *  table.
 *
 * @param entitiesClause The clause that controls which entities will be evaluated by this query.
 * @param version The version of the properties being read.
 *
 * @return A named SQL subquery fragment consisting of all entity key satisfying the provided clauses from the
 * ids table.
 */
internal fun selectEntityKeyIdsFilteredByVersionSubquerySql(
        entitiesClause: String,
        version: Long,
        metadataOptions: Set<MetadataOption>,
        joinColumns: List<String>
): String {
    val selectedColumns = joinColumns.joinToString(",")

    return if (metadataOptions.isEmpty()) {
        "(SELECT $selectedColumns " +
                "FROM ( SELECT $selectedColumns, max(versions) as abs_max, max(abs(versions)) as max_abs " +
                "       FROM (  SELECT $selectedColumns, unnest(versions) as versions " +
                "           FROM ${IDS.name} " +
                "           WHERE TRUE $entitiesClause ) as $EXPANDED_VERSIONS " +
                "       WHERE abs(versions) <= $version " +
                "       GROUP BY($selectedColumns)) as unfiltered_data_keys " +
                "WHERE max_abs=abs_max ) "
    } else {
        //TODO: needs fix with aliases
        val metadataColumns = metadataOptions.map(::mapMetadataOptionToPostgresColumn).joinToString(",")
        return "(SELECT $selectedColumns,$metadataColumns FROM ${IDS.name} INNER JOIN (SELECT $selectedColumns " +
                "FROM ( SELECT $selectedColumns, max(versions) as abs_max, max(abs(versions)) as max_abs " +
                "       FROM (  SELECT $selectedColumns, unnest(versions) as versions " +
                "           FROM ${IDS.name} " +
                "           WHERE TRUE $entitiesClause ) as $EXPANDED_VERSIONS " +
                "       WHERE abs(versions) <= $version " +
                "       GROUP BY($selectedColumns)) as unfiltered_data_keys " +
                "WHERE max_abs=abs_max ) as data_keys ) as decorated_keys USING ($entityKeyIdColumns)"
    }
}

internal fun selectLinkingIdsFilteredByVersionSubquerySql(
        entitiesClause: String,
        version: Long,
        joinColumns: List<String>
): String {
    val selectedColumns = joinColumns.joinToString(",")

    // Distinct needed, when we select linking_ids (and group by them)
    // since we select from entity_key_ids where linking_id can correspond to multiple rows
    return "(SELECT DISTINCT $selectedColumns " +
            "FROM ( SELECT $selectedColumns, max(versions) as abs_max, max(abs(versions)) as max_abs " +
            "       FROM (  SELECT $selectedColumns, unnest(versions) as versions " +
            "           FROM ${IDS.name} " +
            "           WHERE TRUE $entitiesClause ) as $EXPANDED_VERSIONS " +
            "       WHERE abs(versions) <= $version " +
            "       GROUP BY($selectedColumns)) as unfiltered_data_keys " +
            "WHERE max_abs=abs_max ) "
}

/**
 * This routine generates SQL that selects all entity key ids that are not tombstoned for the given entities clause. If
 * metadata is requested on a linked read, behavior is undefined as it will end up
 *
 * @param entitiesClause The clause that controls which entities will be evaluated by this query.
 * @param metadataOptions The metadata to return for the entity.
 *
 * @return A named SQL subquery fragment consisting of all entity key satisfying the provided clauses from the
 * ids table.
 */
internal fun selectEntityKeyIdsWithCurrentVersionSubquerySql(
        metadataOptions: Set<MetadataOption>,
        linking: Boolean,
        omitEntitySetId: Boolean,
        joinColumns: List<String>
): String {
    val metadataColumns = getMetadataOptions(metadataOptions, linking).joinToString(",")
    val joinColumnsSql = joinColumns.joinToString(",")
    val selectColumns = joinColumnsSql +
            if (metadataColumns.isNotEmpty()) {
                if (linking) {
                    if (metadataOptions.contains(MetadataOption.LAST_WRITE)) {
                        ", max(${LAST_WRITE.name}) AS ${LAST_WRITE.name}"
                    } else {
                        ""
                    } + if (metadataOptions.contains(MetadataOption.ENTITY_KEY_IDS)) {
                        ", array_agg(${ID.name}) as entity_key_ids"
                    } else {
                        ""
                    }
                } else {
                    ", $metadataColumns"
                }
            } else {
                ""
            }

    val groupBy = if (linking && omitEntitySetId) {
        "GROUP BY ${LINKING_ID.name}"
    } else if (linking && !omitEntitySetId) {
        "GROUP BY (${ENTITY_SET_ID.name},${LINKING_ID.name})"
    } else {
        ""
    }

    return "( SELECT $selectColumns FROM $FILTERED_ENTITY_KEY_IDS INNER JOIN ${IDS.name} USING($joinColumnsSql) $groupBy ) as $ENTITIES_TABLE_ALIAS"
}

internal fun buildEntitiesJoinCondition(joinTableName: String, linking: Boolean): String {
    return if (linking) {
        "($joinTableName.${ENTITY_SET_ID.name} = ${QUERIES.name}.${ENTITY_SET_ID.name} AND ($joinTableName.${LINKING_ID.name} = ${QUERIES.name}.${ID.name} ) )"
    } else {
        "($joinTableName.${ENTITY_SET_ID.name} = ${QUERIES.name}.${ENTITY_SET_ID.name} AND ($joinTableName.${ID.name} = ${QUERIES.name}.${ID.name} ) )"
    }
}

internal fun arrayAggSql(fqn: String): String {
    return " array_agg($fqn) as $fqn "
}

internal fun buildEntitiesClause(entityKeyIds: Map<UUID, Optional<Set<UUID>>>, linking: Boolean): String {
    if (entityKeyIds.isEmpty()) return ""

    val filterLinkingIds = if (linking) " AND ${LINKING_ID.name} IS NOT NULL " else ""

    val idsColumn = if (linking) LINKING_ID.name else ID_VALUE.name
    return "$filterLinkingIds " /*AND (" + entityKeyIds.entries.joinToString(" OR ") {
        val idsClause = it.value
                .filter { ids -> ids.isNotEmpty() }
                .map { ids -> " AND $idsColumn IN (" + ids.joinToString(",") { id -> "'$id'" } + ")" }
                .orElse("")
        " (${ENTITY_SET_ID.name} = '${it.key}' $idsClause)"
    } + ")"*/
}

internal fun buildPropertyTypeEntitiesClause(
        entityKeyIds: Map<UUID, Optional<Set<UUID>>>,
        propertyTypeId: UUID,
        authorizedPropertyTypes: Map<UUID, Set<UUID>>
): String {
    /*
     * Filter out any entity sets for which you aren't authorized to read this property.
     * There should always be at least one entity set as this isn't invoked for any properties
     * with not readable entity sets.
     */
    val authorizedEntitySetIds = entityKeyIds.asSequence().filter {
        authorizedPropertyTypes[it.key]?.contains(propertyTypeId) ?: false
    }.joinToString(",") { "'${it.key}'" }

    return "AND ${ENTITY_SET_ID.name} IN ($authorizedEntitySetIds)"
}


internal fun buildFilterClause(fqn: String, filter: Set<Filter>): String {
    if (filter.isEmpty()) return ""
    return filter.joinToString(" AND ", prefix = " AND ") { it.asSql(fqn) }
}

internal fun selectEntityKeyIdsByLinkingIds(linkingIds: Set<UUID>): String {
    val linkingEntitiesClause = buildLinkingEntitiesClause(linkingIds)
    return "SELECT ${LINKING_ID.name}, array_agg(${ID_VALUE.name}) AS ${ENTITY_KEY_IDS.name} " +
            "FROM  ${IDS.name} " +
            "WHERE ${VERSION.name} > 0 AND ${LINKING_ID.name} IS NOT NULL $linkingEntitiesClause " +
            "GROUP BY ${LINKING_ID.name} "
}

internal fun buildLinkingEntitiesClause(linkingIds: Set<UUID>): String {
    return " AND ${LINKING_ID.name} IN ( ${linkingIds.joinToString(",") { "'$it'" }} ) "
}

private fun getJoinColumns(linking: Boolean, omitEntitySetId: Boolean): List<String> {
    return if (linking) {
        if (omitEntitySetId) {
            listOf(LINKING_ID.name)
        } else {
            listOf(ENTITY_SET_ID.name, LINKING_ID.name)
        }
    } else {
        listOf(ENTITY_SET_ID.name, ID_VALUE.name)
    }
}

private fun getMetadataOptions(metadataOptions: Set<MetadataOption>, linking: Boolean): List<String> {
    val allowedMetadataOptions = if (linking) // we omit some metadataOptions from linking queries
        metadataOptions.intersect(ALLOWED_LINKING_METADATA_OPTIONS)
    else
        metadataOptions.intersect(ALLOWED_NON_LINKING_METADATA_OPTIONS)


    //TODO: Make this an error and fix cases by providing static method for getting all allowed metadata options
    //as opposed to just EnumSet.allOf(...)
    val invalidMetadataOptions = metadataOptions - allowedMetadataOptions
    if (invalidMetadataOptions.isNotEmpty()) {
        logger.warn("Invalid metadata options requested: {}", invalidMetadataOptions)
    }

    return allowedMetadataOptions.map(::mapMetadataOptionToPostgresColumn)
}

private fun mapMetadataOptionToPostgresColumn(metadataOption: MetadataOption): String {
    return when (metadataOption) {
        MetadataOption.LAST_WRITE -> LAST_WRITE.name
        MetadataOption.LAST_INDEX -> LAST_INDEX.name
        MetadataOption.LAST_LINK -> LAST_LINK.name
        MetadataOption.VERSION -> VERSION.name
        MetadataOption.ENTITY_SET_IDS -> "entity_set_ids"
        MetadataOption.ENTITY_KEY_IDS -> "entity_key_ids"
    }
}

val REGISTER_QUERY_SQL = "INSERT INTO ${QUERIES.name} VALUES(?,?,?,?,?)"
val CHECK_OFF_QUERY_SQL = "DELETE FROM ${QUERIES.name} WHERE ${QUERY_ID.name} = ?"