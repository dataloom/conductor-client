package com.openlattice.postgres

import com.openlattice.postgres.PostgresColumn.ENTITY_SET_ID
import com.openlattice.postgres.PostgresColumn.PARTITION
import com.openlattice.postgres.PostgresTable.ENTITY_KEY_IDS

/**
 *
 * @author Matthew Tamayo-Rios &lt;matthew@openlattice.com&gt;
 */

class PostgresMaterializedViews {
    data class MaterializedView(
            val name: String,
            val createSql: String,
            val refreshSql: String = "REFRESH MATERIALIZED VIEW $name"
    )

    companion object {
        @JvmField
        val PARTITION_COUNTS = MaterializedView(
                "partition_counts",
                "CREATE MATERIALIZED VIEW IF NOT EXISTS partition_counts AS " +
                        "SELECT ${ENTITY_SET_ID.name},${PARTITION.name},COUNT(*) " +
                        "FROM ${ENTITY_KEY_IDS.name} " +
                        "GROUP BY (${ENTITY_SET_ID.name},${PARTITION.name})"
        )


    }
}