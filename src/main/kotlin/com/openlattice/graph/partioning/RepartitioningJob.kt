package com.openlattice.graph.partioning

import com.geekbeast.rhizome.jobs.AbstractDistributedJob
import com.geekbeast.rhizome.jobs.JobStatus
import com.openlattice.postgres.DataTables.*
import com.openlattice.postgres.PostgresColumn.*
import com.openlattice.postgres.PostgresColumnDefinition
import com.openlattice.postgres.PostgresTable.*
import com.openlattice.postgres.PostgresTableDefinition
import com.zaxxer.hikari.HikariDataSource
import java.sql.PreparedStatement
import java.util.*

/**
 * Background job for re-partitioning data in an entity set.
 * @author Matthew Tamayo-Rios &lt;matthew@openlattice.com&gt;
 */
class RepartitioningJob(
        jobState: RepartitioningJobState
) : AbstractDistributedJob<Long, RepartitioningJobState>(jobState) {

    constructor(
            entitySetId: UUID,
            partitions: List<Int>
    ) : this(RepartitioningJobState(entitySetId, partitions, 0))

    private lateinit var hds: HikariDataSource

    private val currentlyMigratingPartition: Int
        get() = state.partitions[state.currentlyMigratingPartitionIndex]

    fun setHikariDataSource(hds: HikariDataSource) {
        this.hds = hds
    }

    override fun result(): Long? {
        //There not really any relevant work to wait on, so we just return null.
        return null
    }

    override fun processNextBatch() {
        /**
         * Do a INSERT INTO ... SELECT FROM to re-partition the data.
         *
         * Entity key id depenedent operations will not see data, until data has been inserted to the appropriate partition.
         */

        state.repartitionCount += repartition(REPARTITION_DATA_SQL)
        state.repartitionCount += repartition(REPARTITION_IDS_SQL)
        state.repartitionCount += repartition(REPARTITION_EDGES_SQL)

        /**
         * Phase 2
         * Delete data whose partition doesn't match it's computed partition.
         */

        state.deleteCount += delete(DELETE_DATA_SQL)
        state.deleteCount += delete(DELETE_IDS_SQL)
        state.deleteCount += delete(DELETE_EDGES_SQL)

        markPartitionCompleted()
    }

    private fun markPartitionCompleted() {
        if (++state.currentlyMigratingPartitionIndex == state.partitions.size) {
            hasWorkRemaining = false
        }
        this.status = JobStatus.FINISHED
    }

    private fun delete(deleteSql: String): Long = hds.connection.use { connection ->
        try {
            connection.prepareStatement(deleteSql).use { deleteData ->
                bind(deleteData)
                deleteData.executeLargeUpdate()
            }
        } finally {
            publishJobState()
        }
    }

    private fun repartition(repartitionSql: String): Long = hds.connection.use { connection ->
        try {
            connection.prepareStatement(repartitionSql).use { repartitionData ->
                bind(repartitionData)
                repartitionData.executeLargeUpdate()
            }
        } finally {
            publishJobState()
        }
    }

    private fun bind(ps: PreparedStatement) {
        ps.setObject(1, state.entitySetId)
        ps.setObject(2, currentlyMigratingPartition)
    }
}

private val REPARTITION_SELECTOR = "partitions[ 1 + ((array_length(partitions,1) + (('x'||right(${SRC_ENTITY_KEY_ID.name}::text,8))::bit(32)::int % array_length(partitions,1))) % array_length(partitions,1))]"

private fun buildRepartitionColumns(ptd: PostgresTableDefinition): String {
    return ptd.columns.joinToString(",") { if (it == PARTITION) REPARTITION_SELECTOR else it.name }
}

private fun latestSql(
        column: PostgresColumnDefinition,
        comparison: PostgresColumnDefinition = column,
        whenExcludedGreater: PostgresColumnDefinition = column,
        otherwise: PostgresColumnDefinition = column
): String = "${column.name} = CASE WHEN EXCLUDED.${comparison.name} > ${comparison.name} THEN EXCLUDED.${whenExcludedGreater.name} ELSE ${otherwise.name} END"


private val REPARTITION_DATA_COLUMNS = buildRepartitionColumns(DATA)
private val REPARTITION_IDS_COLUMNS = buildRepartitionColumns(E)
private val REPARTITION_EDGES_COLUMNS = buildRepartitionColumns(IDS)

/**
 * Query for repartition a partition of data.
 *
 * 1. entity set id
 * 2. partition
 * NOTE: We not attempt to move data values on conflict. In theory, data is immutable and a conflict wouldn't impact the
 * actual content of the data columns, unless a hash collection had occured during a re-partition.
 * NOTE: We set origin_id based on version. This should be fine in 99.999% of cases as the latest version should have
 * the most up to date linkined. See note on  for exceptional case [REPARTITION_IDS_SQL]
 */
private val REPARTITION_DATA_SQL = """
INSERT INTO ${DATA.name} SELECT $REPARTITION_DATA_COLUMNS
    FROM ${DATA.name} INNER JOIN (select id as ${ENTITY_SET_ID.name}, partitions from entity_sets) as es 
    USING ( ${ENTITY_SET_ID.name} )
    WHERE ${ENTITY_SET_ID.name} = ? AND ${PARTITION.name} = ? AND ${PARTITION.name}!=$REPARTITION_SELECTOR
    ON CONFLICT DO UPDATE SET
        ${latestSql(ORIGIN_ID, VERSION)},
        ${latestSql(VERSION, VERSION)},
        $VERSIONS = ARRAY( SELECT DISTINCT UNNEST(${VERSIONS.name} || EXCLUDED.${VERSIONS.name} ) ORDER BY 1  )  
        ${latestSql(LAST_WRITE, VERSION)},
        ${latestSql(LAST_PROPAGATE, VERSION)},
""".trimIndent()

/**
 * Query for repartition a partition of ids.
 *
 * 1. entity set id
 * 2. partition
 *
 * NOTE: Using last_link for LINKING_ID in this query because a link can happen without triggering a version update.
 */
private val REPARTITION_IDS_SQL = """
INSERT INTO ${IDS.name} SELECT $REPARTITION_IDS_COLUMNS
    FROM ${IDS.name} INNER JOIN (select id as ${ENTITY_SET_ID.name}, ${PARTITIONS.name} from entity_sets) as es 
    USING (${ENTITY_SET_ID.name})
    WHERE ${ENTITY_SET_ID.name} = ? AND ${PARTITION.name} = ? AND ${PARTITION.name}!=$REPARTITION_SELECTOR
    ON CONFLICT DO UPDATE SET
        ${latestSql(LINKING_ID, LAST_LINK)}, 
        ${latestSql(VERSION, VERSION)},
        $VERSIONS = ARRAY( SELECT DISTINCT UNNEST(${VERSIONS.name} || EXCLUDED.${VERSIONS.name} ) ORDER BY 1  ),  
        ${latestSql(LAST_WRITE, VERSION)},
        ${latestSql(LAST_INDEX, VERSION)},
        ${latestSql(LAST_PROPAGATE, VERSION)},
        ${latestSql(LAST_MIGRATE, VERSION)},
        ${latestSql(LAST_LINK_INDEX, VERSION)}
""".trimIndent()

/**
 * Query for repartition a partition of edges.
 *
 * 1. entity set id
 * 2. partition
 * NOTE: Using last_link for LINKING_ID in this query because a link can happen without triggering a version update.
 */
private val REPARTITION_EDGES_SQL = """
INSERT INTO ${E.name} SELECT $REPARTITION_EDGES_COLUMNS
    FROM ${E.name} INNER JOIN (select id as ${SRC_ENTITY_SET_ID.name}, ${PARTITIONS.name} from entity_sets) as es
    USING (${SRC_ENTITY_SET_ID.name})
    WHERE ${ENTITY_SET_ID.name} = ? AND ${PARTITION.name} = ? AND ${PARTITION.name}!=$REPARTITION_SELECTOR
    ON CONFLICT DO UPDATE SET
        ${latestSql(VERSION, VERSION)},
        $VERSIONS = ARRAY( SELECT DISTINCT UNNEST(${VERSIONS.name} || EXCLUDED.${VERSIONS.name} ) ORDER BY 1  )
""".trimIndent()

/**
 * Computes the actual partition and compares it to current partition. If partitions do not match deletes the row.
 * 1. entity set id
 * 2. partition
 */
private val DELETE_DATA_SQL = """
DELETE FROM ${DATA.name} 
    USING (SELECT ${ID.name},${ENTITY_SET_ID.name},${PARTITION.name} FROM ${DATA.name} INNER JOIN (select ${ID.name} as ${ENTITY_SET_ID.name}, ${PARTITIONS.name} from entity_sets) as es USING (${ENTITY_SET_ID.name})) as to_be_deleted
    WHERE ${ENTITY_SET_ID.name} = ? and ${PARTITION.name} = ? AND partition!=$REPARTITION_SELECTOR AND to_be_deleted.${ID.name} = ids.${ID.name} and to_be_deleted.${PARTITION.name} = ids.${PARTITION.name};  
""".trimIndent()

/**
 * Computes the actual partition and compares it to current partition. If partitions do not match deletes the row.
 * 1. entity set id
 * 2. partition
 */
private val DELETE_IDS_SQL = """
DELETE FROM ${ID.name} 
    USING (SELECT ${ID.name},${ENTITY_SET_ID.name},${PARTITION.name} FROM ${IDS.name} INNER JOIN (select ${ID.name} as ${ENTITY_SET_ID.name}, ${PARTITIONS.name} from entity_sets) as es USING (${ENTITY_SET_ID.name})) as to_be_deleted
    WHERE ${ENTITY_SET_ID.name} = ? and ${PARTITION.name} = ? AND ${PARTITION.name}!=$REPARTITION_SELECTOR AND to_be_deleted.${ID.name} = ids.${ID.name} and to_be_deleted.${PARTITION.name} = ids.${PARTITION.name};  
""".trimIndent()

/**
 * Computes the actual partition and compares it to current partition. If partitions do not match deletes the row.
 *
 * 1. entity set id
 * 2. partition
 */
private val DELETE_EDGES_SQL = """
DELETE FROM ${E.name} 
    USING (SELECT ${SRC_ENTITY_SET_ID.name},${SRC_ENTITY_KEY_ID.name},${PARTITION.name} FROM ${E.name} INNER JOIN (select ${ID.name} as ${SRC_ENTITY_SET_ID.name}, ${PARTITIONS.name} from entity_sets) as es USING (${SRC_ENTITY_SET_ID.name})) as to_be_deleted
    WHERE ${SRC_ENTITY_SET_ID.name} = ? and ${PARTITION.name} = ? AND ${PARTITION.name}!=$REPARTITION_SELECTOR AND to_be_deleted.${ID.name} = ids.${ID.name} and to_be_deleted.${PARTITION.name} = ids.${PARTITION.name};  
""".trimIndent()