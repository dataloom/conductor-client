package com.openlattice.postgres

import com.google.common.cache.CacheBuilder
import com.google.common.cache.CacheLoader
import com.openlattice.edm.PostgresEdmTypeConverter
import com.openlattice.postgres.DataTables.LAST_WRITE
import com.openlattice.postgres.DataTables.quote
import com.openlattice.postgres.PostgresColumn.*
import org.apache.olingo.commons.api.edm.EdmPrimitiveTypeKind
import java.util.*

/**
 *
 * @author Matthew Tamayo-Rios &lt;matthew@openlattice.com&gt;
 */
class PostgresDataTables {
    companion object {
        private val supportedEdmPrimitiveTypeKinds = arrayOf(
                EdmPrimitiveTypeKind.String,
                EdmPrimitiveTypeKind.Guid,
                EdmPrimitiveTypeKind.Byte,
                EdmPrimitiveTypeKind.Int16,
                EdmPrimitiveTypeKind.Int32,
                EdmPrimitiveTypeKind.Duration,
                EdmPrimitiveTypeKind.Int64,
                EdmPrimitiveTypeKind.Date,
                EdmPrimitiveTypeKind.DateTimeOffset,
                EdmPrimitiveTypeKind.Double,
                EdmPrimitiveTypeKind.Boolean,
                EdmPrimitiveTypeKind.Binary,
                EdmPrimitiveTypeKind.Guid,
                EdmPrimitiveTypeKind.TimeOfDay
        )

        //The associate is collapsing edm primitive type kinds such that only 1 is preserved for converted type.
        //If you change this such that columns generated are sensitive to being discarded make sure you update logic.
        val dataColumns = supportedEdmPrimitiveTypeKinds
                .map(PostgresEdmTypeConverter::map)
                .associateWith { nonIndexedValueColumn(it) to btreeIndexedValueColumn(it) }

        val nonIndexedColumns = dataColumns.map { it.value.first }
        val btreeIndexedColumns = dataColumns.map { it.value.second }

        val dataTableMetadataColumns = listOf(
                ENTITY_SET_ID,
                ID_VALUE,
                ORIGIN_ID,
                PARTITION,
                PROPERTY_TYPE_ID,
                HASH,
                LAST_WRITE,
                LAST_PROPAGATE,
                VERSION,
                VERSIONS,
                PARTITIONS_VERSION
        )

        val dataTableValueColumns = btreeIndexedColumns + nonIndexedColumns
        val dataTableColumns = dataTableMetadataColumns + dataTableValueColumns

        private val columnDefinitionCache = CacheBuilder.newBuilder().build(
                object : CacheLoader<Pair<IndexType, EdmPrimitiveTypeKind>, PostgresColumnDefinition>() {
                    override fun load(key: Pair<IndexType, EdmPrimitiveTypeKind>): PostgresColumnDefinition {
                        return buildColumnDefinition(key)
                    }

                    override fun loadAll(
                            keys: MutableIterable<Pair<IndexType, EdmPrimitiveTypeKind>>
                    ): MutableMap<Pair<IndexType, EdmPrimitiveTypeKind>, PostgresColumnDefinition> {
                        return keys.associateWith { buildColumnDefinition(it) }.toMutableMap()
                    }

                    private fun buildColumnDefinition(
                            key: Pair<IndexType, EdmPrimitiveTypeKind>
                    ): PostgresColumnDefinition {
                        val (indexType, edmType) = key
                        return when (indexType) {
                            IndexType.BTREE -> btreeIndexedValueColumn(
                                    PostgresEdmTypeConverter.map(edmType)
                            )
                            IndexType.NONE -> nonIndexedValueColumn(
                                    PostgresEdmTypeConverter.map(edmType)
                            )
                            else -> throw IllegalArgumentException(
                                    "HASH or GIN indexes are not yet supported by openlattice."
                            )
                        }
                    }
                }
        )

        @JvmStatic
        fun buildDataTableDefinition(): PostgresTableDefinition {
            val columns = dataTableColumns.toTypedArray()

            val tableDefinition = CitusDistributedTableDefinition("data")
                    .addColumns(*columns)
                    .primaryKey(ENTITY_SET_ID, ID_VALUE, PARTITION, PROPERTY_TYPE_ID, HASH)
                    .distributionColumn(PARTITION)

            tableDefinition.addIndexes(
                    *btreeIndexedColumns.map { buildBtreeIndexDefinition(tableDefinition, it) }.toTypedArray()
            )

            val prefix = tableDefinition.name

            val entitySetIdIndex = PostgresColumnsIndexDefinition(tableDefinition, ENTITY_SET_ID)
                    .name(quote(prefix + "_entity_set_id_idx"))
                    .ifNotExists()
            val idIndex = PostgresColumnsIndexDefinition(tableDefinition, ID_VALUE)
                    .name(quote(prefix + "_id_idx"))
                    .ifNotExists()
                    .desc()
            val versionIndex = PostgresColumnsIndexDefinition(tableDefinition, VERSION)
                    .name(quote(prefix + "_version_idx"))
                    .ifNotExists()
                    .desc()
            val lastWriteIndex = PostgresColumnsIndexDefinition(tableDefinition, LAST_WRITE)
                    .name(quote(prefix + "_last_write_idx"))
                    .ifNotExists()
                    .desc()
            val propertyTypeIdIndex = PostgresColumnsIndexDefinition(tableDefinition, PROPERTY_TYPE_ID)
                    .name(quote(prefix + "_property_type_id_idx"))
                    .ifNotExists()
                    .desc()
            val partitionsVersionIndex = PostgresColumnsIndexDefinition(tableDefinition, PARTITIONS_VERSION)
                    .name(quote(prefix + "_partitions_version_id_idx"))
                    .ifNotExists()
                    .desc()

            val currentPropertiesForEntitySetIndex = PostgresColumnsIndexDefinition(
                    tableDefinition, ENTITY_SET_ID, VERSION
            )
                    .name(quote(prefix + "entity_set_id_version_idx"))
                    .ifNotExists()
                    .desc()

            val currentPropertiesForEntityIndex = PostgresColumnsIndexDefinition(tableDefinition, ID_VALUE, VERSION)
                    .name(quote(prefix + "id_version_idx"))
                    .ifNotExists()
                    .desc()

            tableDefinition.addIndexes(
                    idIndex,
                    entitySetIdIndex,
                    versionIndex,
                    lastWriteIndex,
                    propertyTypeIdIndex,
                    partitionsVersionIndex,
                    currentPropertiesForEntitySetIndex,
                    currentPropertiesForEntityIndex
            )

            return tableDefinition
        }

        @JvmStatic
        fun buildBtreeIndexDefinition(
                tableDefinition: PostgresTableDefinition,
                columnDefinition: PostgresColumnDefinition
        ): PostgresIndexDefinition {
            return PostgresColumnsIndexDefinition(tableDefinition, columnDefinition)
                    .name(buildBtreeIndexName(tableDefinition.name, columnDefinition.name))
                    .ifNotExists()
        }

        private fun buildBtreeIndexName(tableName: String, columnName: String): String {
            return "${tableName}_${columnName}_${IndexType.BTREE.name.toLowerCase()}_idx"
        }

        @JvmStatic
        fun nonIndexedValueColumn(datatype: PostgresDatatype): PostgresColumnDefinition {
            return PostgresColumnDefinition("n_${datatype.name}", datatype)
        }

        @JvmStatic
        fun btreeIndexedValueColumn(datatype: PostgresDatatype): PostgresColumnDefinition {
            return PostgresColumnDefinition("b_${datatype.name}", datatype)
        }

        /**
         * Utility function for retrieving the column definition for the data table.
         * @param indexType The index type for the column
         * @param edmType The [EdmPrimitiveTypeKind] of the column.
         *
         * @return The postgres column definition for the column time.
         */
        @JvmStatic
        fun getColumnDefinition(indexType: IndexType, edmType: EdmPrimitiveTypeKind): PostgresColumnDefinition {
            return columnDefinitionCache[indexType to edmType]
        }
    }

}