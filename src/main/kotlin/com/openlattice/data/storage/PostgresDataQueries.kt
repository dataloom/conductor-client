package com.openlattice.data.storage


import com.openlattice.IdConstants
import com.openlattice.analysis.SqlBindInfo
import com.openlattice.analysis.requests.Filter
import com.openlattice.edm.PostgresEdmTypeConverter
import com.openlattice.edm.type.PropertyType
import com.openlattice.postgres.*
import com.openlattice.postgres.DataTables.LAST_WRITE
import com.openlattice.postgres.PostgresColumn.*
import com.openlattice.postgres.PostgresDataTables.Companion.dataTableValueColumns
import com.openlattice.postgres.PostgresDataTables.Companion.getColumnDefinition
import com.openlattice.postgres.PostgresDataTables.Companion.getSourceDataColumnName
import com.openlattice.postgres.PostgresTable.DATA
import com.openlattice.postgres.PostgresTable.IDS
import java.sql.PreparedStatement
import java.util.*

/**
 * This class is responsible for generating all the SQL for creating, reading, upated, and deleting entities.
 * @author Matthew Tamayo-Rios &lt;matthew@openlattice.com&gt;
 */
internal class PostgresDataQueries


const val VALUE = "value"
const val PROPERTIES = "properties"

val dataTableColumnsSql = PostgresDataTables.dataTableColumns.joinToString(",") { it.name }

// @formatter:off
val detailedValueColumnsSql =
    "COALESCE( " + dataTableValueColumns.joinToString(",") {
        "jsonb_agg(" +
            "json_build_object(" +
                "'$VALUE',${it.name}, " +
                "'${ID_VALUE.name}', ${ORIGIN_ID.name}, " +
                "'${LAST_WRITE.name}', ${LAST_WRITE.name}" +
            ")" +
        ") FILTER ( WHERE ${it.name} IS NOT NULL) "
} + ") as $PROPERTIES"

val valuesColumnsSql = "COALESCE( " + dataTableValueColumns.joinToString(",") {
    "jsonb_agg(${it.name}) " +
    "FILTER (WHERE ${it.name} IS NOT NULL)"
} + ") as $PROPERTIES"
// @formatter:on

val primaryKeyColumnNamesAsString = PostgresDataTables.buildDataTableDefinition().primaryKey.joinToString(
        ","
) { it.name }


/**
 * Builds a preparable SQL query for reading filterable data.
 *
 * The first three columns om
 * @return A preparable sql query to read the data to a ordered set of [SqlBinder] objects. The prepared query
 * must be have the first three parameters bound separately from the [SqlBinder] objects. The parameters are as follows:
 * 1. entity set ids (array)
 * 2. entity key ids (array)
 * 3. partition(s) (array)
 *
 */
fun buildPreparableFiltersSql(
        startIndex: Int,
        propertyTypes: Map<UUID, PropertyType>,
        propertyTypeFilters: Map<UUID, Set<Filter>>,
        metadataOptions: Set<MetadataOption>,
        linking: Boolean,
        idsPresent: Boolean,
        partitionsPresent: Boolean,
        detailed: Boolean = false
): Pair<String, Set<SqlBinder>> {
    val filtersClauses = buildPreparableFiltersClause(startIndex, propertyTypes, propertyTypeFilters)
    val filtersClause = if (filtersClauses.first.isNotEmpty()) " AND ${filtersClauses.first} " else ""
    val metadataOptionColumns = metadataOptions.associateWith(::mapOuterMetaDataToColumnSql)
    val metadataOptionColumnsSql = metadataOptionColumns.values.joinToString("")

    val (innerGroupBy, outerGroupBy) = if (metadataOptions.contains(MetadataOption.ENTITY_KEY_IDS)) {
        groupBy("$ESID_EKID_PART_PTID,${ORIGIN_ID.name}") to groupBy(ESID_EKID_PART_PTID)
    } else groupBy(ESID_EKID_PART_PTID) to groupBy(ESID_EKID_PART)
    val linkingClause = if (linking) " AND ${ORIGIN_ID.name} != '${IdConstants.EMPTY_ORIGIN_ID.id}' " else ""

    val innerSql = selectEntitiesGroupedByIdAndPropertyTypeId(
            metadataOptions,
            idsPresent = idsPresent,
            partitionsPresent = partitionsPresent,
            detailed = detailed
    ) + linkingClause + filtersClause + innerGroupBy

    val sql = "SELECT ${ENTITY_SET_ID.name},${ID_VALUE.name},${PARTITION.name}$metadataOptionColumnsSql," +
            "jsonb_object_agg(${PROPERTY_TYPE_ID.name},$PROPERTIES) as $PROPERTIES " +
            "FROM ($innerSql) entities $outerGroupBy"

    return sql to filtersClauses.second
}

internal fun selectEntitiesGroupedByIdAndPropertyTypeId(
        metadataOptions: Set<MetadataOption>,
        idsPresent: Boolean = true,
        partitionsPresent: Boolean = true,
        entitySetsPresent: Boolean = true,
        detailed: Boolean = false
): String {
    //Already have the comma prefix
    val metadataOptionsSql = metadataOptions.joinToString("") { mapMetaDataToSelector(it) }
    val columnsSql = if (detailed) detailedValueColumnsSql else valuesColumnsSql
    return "SELECT ${ENTITY_SET_ID.name},${ID_VALUE.name},${PARTITION.name},${PROPERTY_TYPE_ID.name}$metadataOptionsSql,$columnsSql " +
            "FROM ${DATA.name} ${optionalWhereClauses(idsPresent, partitionsPresent, entitySetsPresent)}"
}

/**
 * Returns the correspondent column name used for the metadata option with a comma prefix.
 */
private fun mapOuterMetaDataToColumnSql(metadataOption: MetadataOption): String {
    return when (metadataOption) {
        // TODO should be just last_write with comma prefix after empty rows are eliminated https://jira.openlattice.com/browse/LATTICE-2254
        MetadataOption.LAST_WRITE -> ",max(${LAST_WRITE.name}) AS ${mapMetaDataToColumnName(metadataOption)}"
        MetadataOption.ENTITY_KEY_IDS -> ",array_agg(${ORIGIN_ID.name}) as ${ENTITY_KEY_IDS_COL.name}"
        else -> throw UnsupportedOperationException("No implementation yet for metadata option $metadataOption")
    }
}

/**
 * Returns the correspondent column name used for the metadata option.
 */
private fun mapMetaDataToColumnName(metadataOption: MetadataOption): String {
    return when (metadataOption) {
        MetadataOption.LAST_WRITE -> LAST_WRITE.name
        MetadataOption.ENTITY_KEY_IDS -> ENTITY_KEY_IDS_COL.name
        else -> throw UnsupportedOperationException("No implementation yet for metadata option $metadataOption")
    }
}

/**
 * Returns the select sql snippet for the requested metadata option.
 */
private fun mapMetaDataToSelector(metadataOption: MetadataOption): String {
    return when (metadataOption) {
        MetadataOption.LAST_WRITE -> ",max(${LAST_WRITE.name}) AS ${mapMetaDataToColumnName(metadataOption)}"
        MetadataOption.ENTITY_KEY_IDS -> ",${ORIGIN_ID.name}"
        else -> throw UnsupportedOperationException("No implementation yet for metadata option $metadataOption")
    }
}

/*
 * Creates a preparable query with the following clauses.
 */
private fun buildPreparableFiltersClause(
        startIndex: Int,
        propertyTypes: Map<UUID, PropertyType>,
        propertyTypeFilters: Map<UUID, Set<Filter>>
): Pair<String, Set<SqlBinder>> {
    val bindList = propertyTypeFilters.entries
            .filter { (_, filters) -> filters.isEmpty() }
            .flatMap { (propertyTypeId, filters) ->
                val nCol = PostgresDataTables
                        .nonIndexedValueColumn(
                                PostgresEdmTypeConverter.map(propertyTypes.getValue(propertyTypeId).datatype)
                        )
                val bCol = PostgresDataTables
                        .btreeIndexedValueColumn(
                                PostgresEdmTypeConverter.map(propertyTypes.getValue(propertyTypeId).datatype)
                        )

                //Generate sql preparable sql fragments
                var currentIndex = startIndex
                val nFilterFragments = filters.map { filter ->
                    val bindDetails = buildBindDetails(currentIndex, propertyTypeId, filter, nCol.name)
                    currentIndex = bindDetails.nextIndex
                    bindDetails
                }

                val bFilterFragments = filters
                        .map { filter ->
                            val bindDetails = buildBindDetails(currentIndex, propertyTypeId, filter, bCol.name)
                            currentIndex = bindDetails.nextIndex
                            bindDetails
                        }

                nFilterFragments + bFilterFragments
            }

    val sql = bindList.joinToString(" AND ") { "(${it.sql})" }
    val bindInfo = bindList.flatMap { it.bindInfo }.toSet()
    return sql to bindInfo
}

private fun buildBindDetails(
        startIndex: Int,
        propertyTypeId: UUID,
        filter: Filter,
        col: String
): BindDetails {
    val bindInfo = linkedSetOf<SqlBinder>()
    bindInfo.add(SqlBinder(SqlBindInfo(startIndex, propertyTypeId), ::doBind))
    bindInfo.addAll(filter.bindInfo(startIndex).map { SqlBinder(it, ::doBind) })
    return BindDetails(startIndex + bindInfo.size, bindInfo, "${PROPERTY_TYPE_ID.name} = ? AND " + filter.asSql(col))
}

@Suppress("UNCHECKED_CAST")
internal fun doBind(ps: PreparedStatement, info: SqlBindInfo) {
    when (val v = info.value) {
        is String -> ps.setString(info.bindIndex, v)
        is Int -> ps.setInt(info.bindIndex, v)
        is Long -> ps.setLong(info.bindIndex, v)
        is Boolean -> ps.setBoolean(info.bindIndex, v)
        is Short -> ps.setShort(info.bindIndex, v)
        is java.sql.Array -> ps.setArray(info.bindIndex, v)
        //TODO: Fix this bustedness.
        is Collection<*> -> {
            val array = when (val elem = v.first()!!) {
                is String -> PostgresArrays.createTextArray(ps.connection, v as Collection<String>)
                is Int -> PostgresArrays.createIntArray(ps.connection, v as Collection<Int>)
                is Long -> PostgresArrays.createLongArray(ps.connection, v as Collection<Long>)
                is Boolean -> PostgresArrays.createBooleanArray(ps.connection, v as Collection<Boolean>)
                is Short -> PostgresArrays.createShortArray(ps.connection, v as Collection<Short>)
                else -> throw IllegalArgumentException(
                        "Collection with elements of ${elem.javaClass} are not " +
                                "supported in filters"
                )
            }
            ps.setArray(info.bindIndex, array)
        }
        else -> ps.setObject(info.bindIndex, v)
    }
}

internal val ESID_EKID_PART = "${ENTITY_SET_ID.name},${ID_VALUE.name},${PARTITION.name}"
internal val ESID_EKID_PART_PTID = "${ENTITY_SET_ID.name},${ID_VALUE.name}, ${PARTITION.name},${PROPERTY_TYPE_ID.name}"

internal fun groupBy(columns: String): String {
    return "GROUP BY ($columns)"
}

/**
 * Preparable SQL that selects entities across multiple entity sets grouping by id and property type id from the [DATA]
 * table with the following bind order:
 *
 * 1. entity set ids (array)
 * 2. entity key ids (array)
 * 3. partition (array)
 *
 */

fun optionalWhereClauses(
        idsPresent: Boolean = true,
        partitionsPresent: Boolean = true,
        entitySetsPresent: Boolean = true
): String {
    val entitySetClause = if (entitySetsPresent) "${ENTITY_SET_ID.name} = ANY(?)" else ""
    val idsClause = if (idsPresent) "${ID_VALUE.name} = ANY(?)" else ""
    val partitionClause = if (partitionsPresent) "${PARTITION.name} = ANY(?)" else ""
    val versionsClause = "${VERSION.name} > 0 "

    val optionalClauses = listOf(entitySetClause, idsClause, partitionClause, versionsClause).filter { it.isNotBlank() }
    return "WHERE ${optionalClauses.joinToString(" AND ")}"
}

fun optionalWhereClausesSingleEdk(
        idPresent: Boolean = true,
        partitionsPresent: Boolean = true,
        entitySetPresent: Boolean = true
): String {
    val entitySetClause = if (entitySetPresent) "${ENTITY_SET_ID.name} = ?" else ""
    val idsClause = if (idPresent) "${ID_VALUE.name} = ?" else ""
    val partitionClause = if (partitionsPresent) "${PARTITION.name} = ANY(?)" else ""
    val versionsClause = "${VERSION.name} > 0 "

    val optionalClauses = listOf(entitySetClause, idsClause, partitionClause, versionsClause).filter { it.isNotBlank() }
    return "WHERE ${optionalClauses.joinToString(" AND ")}"
}

/**
 * Preparable sql to upsert entities in [IDS] table.
 *
 * It sets a positive version and updates last write to current time.
 *
 * The bind order is the following:
 *
 * 1 - versions
 *
 * 2 - version
 *
 * 3 - version
 *
 * 4 - entity set id
 *
 * 5 - entity key ids
 *
 * 6 - partition
 *
 * 7 - partition
 *
 * 8 - version
 */
fun buildUpsertEntitiesAndLinkedData(): String {
    val insertColumns = dataTableValueColumns.joinToString(",") { it.name }

    val metadataColumns = listOf(
            ENTITY_SET_ID,
            ID_VALUE,
            PARTITION,
            ORIGIN_ID,
            PROPERTY_TYPE_ID,
            HASH,
            LAST_WRITE,
            VERSION,
            VERSIONS,
            PARTITIONS_VERSION
    )
    val metadataColumnsSql = metadataColumns.joinToString(",") { it.name }

    val metadataReadColumnsSql = listOf(
            ENTITY_SET_ID.name,
            LINKING_ID.name,
            "?",
            ID_VALUE.name,
            PROPERTY_TYPE_ID.name,
            HASH.name,
            LAST_WRITE.name,
            VERSION.name,
            VERSIONS.name,
            PARTITIONS_VERSION.name
    ).joinToString(",")

    val conflictClause = (metadataColumns + dataTableValueColumns).joinToString(
            ","
    ) { "${it.name} = EXCLUDED.${it.name}" }


    return "WITH linking_map as ($upsertEntitiesSql) INSERT INTO ${DATA.name} ($metadataColumnsSql,$insertColumns) " +
            "SELECT $metadataReadColumnsSql,$insertColumns FROM ${DATA.name} INNER JOIN " +
            "linking_map USING(${ENTITY_SET_ID.name},${ID.name},${PARTITION.name}) " +
            "WHERE ${LINKING_ID.name} IS NOT NULL AND version > ? " +
            "ORDER BY ${ENTITY_SET_ID.name},${ID.name},${PARTITION.name},${PROPERTY_TYPE_ID.name},${HASH.name},${PARTITIONS_VERSION.name},${ORIGIN_ID.name}" +
            "ON CONFLICT ($primaryKeyColumnNamesAsString) " +
            "DO UPDATE SET $conflictClause"
}

/**
 * Preparable sql to upsert entities in [IDS] table.
 *
 * It sets a positive version and updates last write to current time.
 *
 * The bind order is the following:
 *
 * 1 - versions
 *
 * 2 - version
 *
 * 3 - version
 *
 * 4 - entity set id
 *
 * 5 - entity key ids
 *
 * 6 - partition
 */
// @formatter:off
val upsertEntitiesSql = "UPDATE ${IDS.name} " +
        "SET ${VERSIONS.name} = ${VERSIONS.name} || ?, " +
            "${LAST_WRITE.name} = now(), " +
            "${VERSION.name} = CASE " +
                "WHEN abs(${IDS.name}.${VERSION.name}) <= abs(?) THEN ? " +
                "ELSE ${IDS.name}.${VERSION.name} " +
            "END " +
        "WHERE ${ENTITY_SET_ID.name} = ? AND ${ID_VALUE.name} = ANY(?) AND ${PARTITION.name} = ? " +
        "RETURNING ${ENTITY_SET_ID.name},${ID.name},${PARTITION.name},${LINKING_ID.name} "
// @formatter:on

/**
 * Preparable sql to lock entities with the following bind order:
 * 1. entity key ids
 * 2. partition
 * 3. partition version
 */
internal val lockEntitiesSql = "SELECT 1 FROM ${IDS.name} " +
        "WHERE ${ID_VALUE.name} = ANY(?) AND ${PARTITION.name} = ? AND ${PARTITIONS_VERSION.name} = ? " +
        "FOR UPDATE"

/**
 * Preparable SQL that upserts a version and sets last write to current datetime for all entities in a given entity set
 * in [IDS] table.
 *
 * The following bind order is expected:
 *
 * 1. version
 * 2. version
 * 3. version
 * 4. entity set id
 */
// @formatter:off
internal val updateVersionsForEntitySet = "UPDATE ${IDS.name} " +
        "SET " +
            "${VERSIONS.name} = ${VERSIONS.name} || ARRAY[?], " +
            "${VERSION.name} = " +
                "CASE " +
                    "WHEN abs(${IDS.name}.${VERSION.name}) <= abs(?) " +
                    "THEN ? " +
                    "ELSE ${IDS.name}.${VERSION.name} " +
                "END, " +
            "${LAST_WRITE.name} = 'now()' " +
        "WHERE ${ENTITY_SET_ID.name} = ? "
// TODO do we need partition + partition version here??
// @formatter:on
/**
 * Preparable SQL that upserts a version and sets last write to current datetime for all entities in a given entity set
 * in [IDS] table.
 *
 * The following bind order is expected:
 *
 * 1. version
 * 2. version
 * 3. version
 * 4. entity set id
 * 5. entity key ids (uuid array)
 * 6. partitions (int array)
 * 7. partition version
 */
internal val updateVersionsForEntitiesInEntitySet = "$updateVersionsForEntitySet " +
        "AND ${ID_VALUE.name} = ANY(?) " +
        "AND ${PARTITION.name} = ANY(?) " +
        "AND ${PARTITIONS_VERSION.name} = ? "


/**
 * Preparable SQL that zeroes out the version and sets last write to current datetime for all entities in a given
 * entity set in [IDS] table.
 *
 * The following bind order is expected:
 *
 * 1. entity set id
 * 2. partition (uuid array)
 * 3. partition version
 */
// @formatter:off
internal val zeroVersionsForEntitySet = "UPDATE ${IDS.name} " +
        "SET " +
            "${VERSIONS.name} = ${VERSIONS.name} || ARRAY[0]::bigint[], " +
            "${VERSION.name} = 0, " +
            "${LAST_WRITE.name} = 'now()' " +
        "WHERE " +
            "${ENTITY_SET_ID.name} = ? AND " +
            "${PARTITION.name} = ANY(?) AND " +
            "${PARTITIONS_VERSION.name} = ? "
// @formatter:on


/**
 * Preparable SQL that zeroes out the version and sets last write to current datetime for all entities in a given
 * entity set in [IDS] table.
 *
 * The following bind order is expected:
 *
 * 1. entity set id
 * 2. partition (uuid array)
 * 3. partition version
 * 4. id (uuid array)
 */
internal val zeroVersionsForEntitiesInEntitySet = "$zeroVersionsForEntitySet AND ${ID.name} = ANY(?) "

/**
 * Preparable SQL that updates a version and sets last write to current datetime for all properties in a given entity
 * set in [DATA] table.
 *
 * The following bind order is expected:
 *
 * 1. version
 * 2. version
 * 3. version
 * 4. entity set id
 */
// @formatter:off
internal val updateVersionsForPropertiesInEntitySet = "UPDATE ${DATA.name} " +
        "SET " +
            "${VERSIONS.name} = ${VERSIONS.name} || ARRAY[?], " +
            "${VERSION.name} = " +
                "CASE " +
                    "WHEN abs(${DATA.name}.${VERSION.name}) <= abs(?) " +
                    "THEN ? " +
                    "ELSE ${DATA.name}.${VERSION.name} " +
                "END, " +
            "${LAST_WRITE.name} = 'now()' " +
        "WHERE ${ENTITY_SET_ID.name} = ? "
// @formatter:on
/**
 * Preparable SQL that updates a version and sets last write to current datetime for all properties in a given entity
 * set in [DATA] table.
 *
 * The following bind order is expected:
 *
 * 1. version
 * 2. version
 * 3. version
 * 4. entity set id
 * 5. property type ids
 */
internal val updateVersionsForPropertyTypesInEntitySet = "$updateVersionsForPropertiesInEntitySet " +
        "AND ${PROPERTY_TYPE_ID.name} = ANY(?)"

/**
 * Preparable SQL that updates a version and sets last write to current datetime for all properties in a given entity
 * set in [DATA] table.
 *
 * The following bind order is expected:
 *
 * 1. version
 * 2. version
 * 3. version
 * 4. entity set id
 * 5. property type ids
 * 6. entity key ids
 *    IF LINKING    checks against ORIGIN_ID
 *    ELSE          checks against ID column
 * 7. partitions
 * 8. partition version
 */
fun updateVersionsForPropertyTypesInEntitiesInEntitySet(linking: Boolean = false): String {
    val idsSql = if (linking) {
        "AND ${ORIGIN_ID.name} = ANY(?)"
    } else {
        "AND ${ID_VALUE.name} = ANY(?)"
    }

    return "$updateVersionsForPropertyTypesInEntitySet $idsSql " +
            "AND ${PARTITION.name} = ANY(?) " +
            "AND ${PARTITIONS_VERSION.name} = ? "
}

/**
 * Preparable SQL updates a version and sets last write to current datetime for all property values in a given entity
 * set in [DATA] table.
 *
 * The following bind order is expected:
 *
 * 1. version
 * 2. version
 * 3. version
 * 4. entity set id
 * 5. property type ids
 * 6. entity key ids (if linking: linking ids)
 * 7. partitions
 * 8. partition version
 * 9. {origin ids}: only if linking
 * 10. hash
 */
internal fun updateVersionsForPropertyValuesInEntitiesInEntitySet(linking: Boolean = false): String {
    return "${updateVersionsForPropertyTypesInEntitiesInEntitySet(linking)} AND ${HASH.name} = ? "
}

/**
 * Preparable SQL that updates a version and sets last write to current datetime for all properties in a given linked
 * entity set in [DATA] table.
 *
 * Update set:
 * 1. VERSION: system.currentTime
 * 2. VERSION: system.currentTime
 * 3. VERSION: system.currentTime
 *
 * Where :
 * 4. ENTITY_SET: entity set id
 * 5. ID_VALUE: linking id
 * 6. ORIGIN_ID: entity key id
 * 7. PARTITION: partition(s) (array)
 */
val tombstoneLinkForEntity = "$updateVersionsForPropertiesInEntitySet " +
        "AND ${ID_VALUE.name} = ? " +
        "AND ${ORIGIN_ID.name} = ? " +
        "AND ${PARTITION.name} = ANY(?) "

/**
 * Preparable SQL deletes a given property in a given entity set in [DATA]
 *
 * The following bind order is expected:
 *
 * 1. entity set id
 * 2. property type id
 */
internal val deletePropertyInEntitySet = "DELETE FROM ${DATA.name} WHERE ${ENTITY_SET_ID.name} = ? AND ${PROPERTY_TYPE_ID.name} = ? "

/**
 * Preparable SQL deletes all entity ids a given entity set in [IDS]
 *
 * The following bind order is expected:
 *
 * 1. entity set id
 */
internal val deleteEntitySetEntityKeys = "DELETE FROM ${IDS.name} WHERE ${ENTITY_SET_ID.name} = ? "

/**
 * Preparable SQL deletes all property values of entities in a given entity set in [DATA]
 *
 * The following bind order is expected:
 *
 * 1. entity set id
 * 2. entity key ids
 * 3. partition
 * 4. partition version
 * 5. property type ids
 */
internal val deletePropertiesOfEntitiesInEntitySet = "DELETE FROM ${DATA.name} " +
        "WHERE ${ENTITY_SET_ID.name} = ? AND ${ID_VALUE.name} = ANY(?) AND ${PARTITION.name} = ? AND ${PARTITIONS_VERSION.name} = ? AND ${PROPERTY_TYPE_ID.name} = ANY(?) "

/**
 * Preparable SQL deletes all property values of entities and entity key id in a given entity set in [DATA]
 *
 * The following bind order is expected:
 *
 * 1. entity set id
 * 2. entity key ids
 * 3. partition
 * 4. partition version
 */
internal val deleteEntitiesInEntitySet = "DELETE FROM ${DATA.name} " +
        "WHERE ${ENTITY_SET_ID.name} = ? AND ${ID_VALUE.name} = ANY(?) AND ${PARTITION.name} = ? AND ${PARTITIONS_VERSION.name} = ? "

/**
 * Preparable SQL deletes all entities in a given entity set in [IDS]
 *
 * The following bind order is expected:
 *
 * 1. entity set id
 * 2. entity key ids
 * 3. partition
 * 4. partition version
 */
// @formatter:off
internal val deleteEntityKeys =
        "$deleteEntitySetEntityKeys AND " +
            "${ID.name} = ANY(?) AND " +
            "${PARTITION.name} = ? AND " +
            "${PARTITIONS_VERSION.name} = ? "
// @formatter:on

/**
 * Selects a text properties from entity sets with the following bind order:
 * 1. entity set ids  (array)
 * 2. property type ids (array)
 *
 */
internal val selectEntitySetTextProperties = "SELECT COALESCE(${getSourceDataColumnName(
        PostgresDatatype.TEXT, IndexType.NONE
)},${getSourceDataColumnName(PostgresDatatype.TEXT, IndexType.BTREE)}) AS ${getMergedDataColumnName(
        PostgresDatatype.TEXT
)} " +
        "FROM ${DATA.name} " +
        "WHERE (${getSourceDataColumnName(
                PostgresDatatype.TEXT, IndexType.NONE
        )} IS NOT NULL OR ${getSourceDataColumnName(PostgresDatatype.TEXT, IndexType.BTREE)} IS NOT NULL) AND " +
        "${ENTITY_SET_ID.name} = ANY(?) AND ${PROPERTY_TYPE_ID.name} = ANY(?) "


/**
 * Selects a text properties from specific entities with the following bind order:
 * 1. entity set ids  (array)
 * 2. property type ids (array)
 * 3. entity key ids (array)
 */
internal val selectEntitiesTextProperties = "$selectEntitySetTextProperties AND ${ID_VALUE.name} = ANY(?)"

fun partitionSelectorFromId(entityKeyId: UUID): Int {
    return entityKeyId.leastSignificantBits.toInt()
}

fun getPartition(entityKeyId: UUID, partitions: List<Int>): Int {
    return partitions[partitionSelectorFromId(entityKeyId) % partitions.size]
}

/**
 * Builds the list of partitions for a given set of entity key ids.
 * @param entityKeyIds The entity key ids whose partitions will be retrieved.
 * @param partitions The partitions to select from.
 * @return A list of partitions.
 */
fun getPartitionsInfo(entityKeyIds: Set<UUID>, partitions: List<Int>): List<Int> {
    return entityKeyIds.map { entityKeyId -> getPartition(entityKeyId, partitions) }
}

/**
 * Builds a mapping of entity key id to partition.
 *
 * @param entityKeyIds The entity key ids whose partitions will be retrieved.
 * @param partitions The partitions to select from.
 *
 * @return A map of entity key ids to partitions.
 */
@Deprecated("Unused")
fun getPartitionsInfoMap(entityKeyIds: Set<UUID>, partitions: List<Int>): Map<UUID, Int> {
    return entityKeyIds.associateWith { entityKeyId -> getPartition(entityKeyId, partitions) }
}

fun getMergedDataColumnName(datatype: PostgresDatatype): String {
    return "v_${datatype.name}"
}

/**
 * This function generates preparable sql with the following bind order:
 *
 * 1.  ENTITY_SET_ID
 * 2.  ID_VALUE
 * 3.  PARTITION
 * 4.  PROPERTY_TYPE_ID
 * 5.  HASH
 *     LAST_WRITE = now()
 * 6.  VERSION,
 * 7.  VERSIONS
 * 8.  PARTITIONS_VERSION
 * 9.  Value Column
 */
fun upsertPropertyValueSql(propertyType: PropertyType): String {
    val insertColumn = getColumnDefinition(propertyType.postgresIndexType, propertyType.datatype)
    val metadataColumnsSql = listOf(
            ENTITY_SET_ID,
            ID_VALUE,
            PARTITION,
            PROPERTY_TYPE_ID,
            HASH,
            LAST_WRITE,
            VERSION,
            VERSIONS,
            PARTITIONS_VERSION
    ).joinToString(",") { it.name }

    return "INSERT INTO ${DATA.name} ($metadataColumnsSql,${insertColumn.name}) " +
            "VALUES (?,?,?,?,?,now(),?,?,?,?) " +
            "ON CONFLICT ($primaryKeyColumnNamesAsString) " +
            "DO UPDATE SET " +
            "${VERSIONS.name} = ${DATA.name}.${VERSIONS.name} || EXCLUDED.${VERSIONS.name}, " +
            "${LAST_WRITE.name} = GREATEST(${DATA.name}.${LAST_WRITE.name},EXCLUDED.${LAST_WRITE.name}), " +
            "${PARTITIONS_VERSION.name} = EXCLUDED.${PARTITIONS_VERSION.name}, " +
            "${VERSION.name} = CASE " +
            "WHEN abs(${DATA.name}.${VERSION.name}) <= EXCLUDED.${VERSION.name} " +
            "THEN EXCLUDED.${VERSION.name} " +
            "ELSE ${DATA.name}.${VERSION.name} " +
            "END"
}

/**
 * This function generates preparable sql with the following bind order:
 *
 * 1.  ENTITY_SET_ID
 * 2.  ID_VALUE         --> expects linking ID
 * 3.  PARTITION
 * 4.  PROPERTY_TYPE_ID
 * 5.  HASH
 *     LAST_WRITE = now()
 * 6.  VERSION,
 * 7.  VERSIONS
 * 8.  PARTITIONS_VERSION
 * 9.  Value Column
 * 10. ORIGIN ID        --> expects entity key id
 */
fun upsertPropertyValueLinkingRowSql(propertyType: PropertyType): String {
    val insertColumn = getColumnDefinition(propertyType.postgresIndexType, propertyType.datatype)
    val metadataColumnsSql = listOf(
            ENTITY_SET_ID,
            ID_VALUE,
            PARTITION,
            PROPERTY_TYPE_ID,
            HASH,
            LAST_WRITE,
            VERSION,
            VERSIONS,
            PARTITIONS_VERSION
    ).joinToString(",") { it.name }
// @formatter:off
    return "INSERT INTO ${DATA.name} ($metadataColumnsSql,${insertColumn.name},${ORIGIN_ID.name}) " +
            "VALUES (?,?,?,?,?,now(),?,?,?,?,?) " +
            "ON CONFLICT ($primaryKeyColumnNamesAsString) " +
            "DO UPDATE SET " +
            "${VERSIONS.name} = ${DATA.name}.${VERSIONS.name} || EXCLUDED.${VERSIONS.name}, " +
            "${LAST_WRITE.name} = GREATEST(${DATA.name}.${LAST_WRITE.name},EXCLUDED.${LAST_WRITE.name}), " +
            "${PARTITIONS_VERSION.name} = EXCLUDED.${PARTITIONS_VERSION.name}, " +
            "${VERSION.name} = CASE WHEN abs(${DATA.name}.${VERSION.name}) <= EXCLUDED.${VERSION.name} " +
                "THEN EXCLUDED.${VERSION.name} " +
                "ELSE ${DATA.name}.${VERSION.name} " +
            "END"
    // @formatter:on
}

/**
 * Used to C(~RUD~) a link from linker
 * This function generates preparable sql with the following bind order:
 *
 * Insert into:
 * 1. ID_VALUE: linkingId
 * 2. VERSION: system.currentTime
 *
 * Select ƒrom where:
 * 3. ENTITY_SET: entity set id
 * 4. ID_VALUE: entity key id
 * 5. PARTITION: partition(s) (array)
 *
 */
fun createOrUpdateLinkFromEntity(): String {
    val existingColumnsUpdatedForLinking = PostgresDataTables.dataTableColumns.joinToString(",") {
        when (it) {
            VERSION, ID_VALUE -> "?"
            ORIGIN_ID -> ID_VALUE.name
            LAST_WRITE -> "now()"
            else -> it.name
        }
    }

    // @formatter:off
    return "INSERT INTO ${DATA.name} ($dataTableColumnsSql) " +
            "SELECT $existingColumnsUpdatedForLinking " +
            "FROM ${DATA.name} " +
            "${optionalWhereClausesSingleEdk(idPresent = true, partitionsPresent = true, entitySetPresent = true)} " +
            "ON CONFLICT ($primaryKeyColumnNamesAsString) " +
            "DO UPDATE SET " +
                "${VERSIONS.name} = ${DATA.name}.${VERSIONS.name} || EXCLUDED.${VERSIONS.name}, " +
                "${LAST_WRITE.name} = GREATEST(${DATA.name}.${LAST_WRITE.name},EXCLUDED.${LAST_WRITE.name}), " +
                "${PARTITIONS_VERSION.name} = EXCLUDED.${PARTITIONS_VERSION.name}, " +
                "${VERSION.name} = CASE " +
                    "WHEN abs(${DATA.name}.${VERSION.name}) <= EXCLUDED.${VERSION.name} " +
                    "THEN EXCLUDED.${VERSION.name} " +
                    "ELSE ${DATA.name}.${VERSION.name} " +
                "END"
    // @formatter:on
}

/* For materialized views */

/**
 * This function generates preparable sql for selecting property values columnar for a given entity set.
 * Bind order is the following:
 * 1. entity set ids (uuid array)
 * 2. partitions (int array)
 */
fun selectPropertyTypesOfEntitySetColumnar(
        authorizedPropertyTypes: Map<UUID, PropertyType>,
        linking: Boolean
): String {
    val idColumnsList = listOf(ENTITY_SET_ID.name, ID.name, ENTITY_KEY_IDS_COL.name)
    val (entitySetData, _) = buildPreparableFiltersSql(
            3,
            authorizedPropertyTypes,
            mapOf(),
            EnumSet.of(MetadataOption.ENTITY_KEY_IDS),
            linking,
            idsPresent = false,
            partitionsPresent = true
    )

    val selectColumns = (idColumnsList +
            (authorizedPropertyTypes.map { selectPropertyColumn(it.value) }))
            .joinToString()
    val groupByColumns = idColumnsList.joinToString()
    val selectArrayColumns = (idColumnsList +
            (authorizedPropertyTypes.map { selectPropertyArray(it.value) }))
            .joinToString()

    return "SELECT $selectArrayColumns FROM (SELECT $selectColumns FROM ($entitySetData) as entity_set_data) as grouped_data GROUP BY ($groupByColumns)"
}


private fun selectPropertyColumn(propertyType: PropertyType): String {
    val dataType = PostgresEdmTypeConverter.map(propertyType.datatype).sql()
    val propertyColumnName = propertyColumnName(propertyType)

    return "jsonb_array_elements_text($PROPERTIES -> '${propertyType.id}')::$dataType AS $propertyColumnName"
}

private fun selectPropertyArray(propertyType: PropertyType): String {
    val propertyColumnName = propertyColumnName(propertyType)
    return if( propertyType.isMultiValued ) {
        "array_agg($propertyColumnName) FILTER (WHERE $propertyColumnName IS NOT NULL) as $propertyColumnName"
    } else {
        "(array_agg($propertyColumnName))[1] FILTER (WHERE $propertyColumnName IS NOT NULL) as $propertyColumnName"
    }
}

private fun propertyColumnName(propertyType: PropertyType): String {
    return DataTables.quote(propertyType.type.fullQualifiedNameAsString)
}
