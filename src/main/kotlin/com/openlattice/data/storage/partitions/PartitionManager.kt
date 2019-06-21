package com.openlattice.data.storage.partitions

import com.geekbeast.rhizome.hazelcast.DelegatedIntList
import com.google.common.base.Preconditions.checkArgument
import com.hazelcast.core.HazelcastInstance
import com.openlattice.edm.EntitySet
import com.openlattice.edm.requests.MetadataUpdate
import com.openlattice.edm.set.EntitySetFlag
import com.openlattice.edm.types.processors.UpdateEntitySetMetadataProcessor
import com.openlattice.hazelcast.HazelcastMap
import com.openlattice.postgres.PostgresArrays
import com.openlattice.postgres.PostgresColumn.COUNT
import com.openlattice.postgres.PostgresColumn.PARTITION
import com.openlattice.postgres.PostgresMaterializedViews.Companion.PARTITION_COUNTS
import com.openlattice.postgres.streams.BasePostgresIterable
import com.openlattice.postgres.streams.PreparedStatementHolderSupplier
import com.zaxxer.hikari.HikariDataSource
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service
import java.util.*

const val DEFAULT_PARTITION_COUNT = 2
/**
 *
 * @author Matthew Tamayo-Rios &lt;matthew@openlattice.com&gt;
 */
@Service
class PartitionManager @JvmOverloads constructor(
        hazelcastInstance: HazelcastInstance, private val hds: HikariDataSource, partitions: Int = 257
) {
    private val partitionList = mutableListOf<Int>()
    private val entitySets = hazelcastInstance.getMap<UUID, EntitySet>(HazelcastMap.ENTITY_SETS.name)
    private val defaultPartitions = hazelcastInstance.getMap<UUID, DelegatedIntList>(
            HazelcastMap.ORGANIZATION_DEFAULT_PARTITIONS.name
    )

    init {
        createMaterializedViewIfNotExists()
        setPartitions(partitions)
    }

    fun getPartitionCount(): Int {
        return partitionList.size
    }

    @Synchronized
    fun setPartitions(partitions: Int) {

        //TODO: Support decreasing number of partitions, but this is unlikely to be needed, since decreasing
        //number of citus partitions will automatically have the desired effect.
        partitionList.addAll(partitionList.size until partitions)
    }

    fun setEntitySetPartitions(entitySetId: UUID, partitions: List<Int>) {
        val update = MetadataUpdate(
                Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
                Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
                Optional.empty(), Optional.of(LinkedHashSet(partitions))
        )
        entitySets.executeOnKey(entitySetId, UpdateEntitySetMetadataProcessor(update))
    }

    fun setDefaultPartitions(organizationId: UUID, partitions: List<Int>) {
        defaultPartitions.set(organizationId, DelegatedIntList(partitions))
    }

    fun getDefaultPartitions(organizationId: UUID): List<Int> {
        return defaultPartitions.getValue(organizationId)
    }

    fun getEntitySetPartitionsInfo(entitySetId: UUID): PartitionsInfo {
        //TODO: Consider doing this using an entry processor
        val entitySet = entitySets.getValue(entitySetId)
        return PartitionsInfo(entitySet.partitions, entitySet.partitionsVersion)
    }

    /**
     * Performs the initial allocation of partitions for an entity set based on default partitions for the organization
     * it belongs to or all partitions if an audit entity set
     * entity sets.
     *
     * @param entitySet The entity set to allocate partitions for.
     * @param partitionCount The number of partitions to attempt to assign to the entity set.
     *
     * @return Returns the entity set that was passed which has been modified with its partition allocation.
     */
    fun allocatePartitions(entitySet: EntitySet, partitionCount: Int): EntitySet {
        isValidAllocation(partitionCount)
        val allocatedPartitions = computePartitions(entitySet, partitionCount)
        entitySet.setPartitions(allocatedPartitions)
        return entitySet
    }

    /**
     * Performs the initial allocation of partitions for an entity set based on default partitions for the organization
     * it belongs to or all partitions if an audit entity set
     * entity sets.
     *
     * @param entitySetId The entity set to allocate partitions for.
     * @param partitionCount The number of partitions to attempt to assign to the entity set.
     */
    fun reallocatePartitions(entitySetId: UUID, partitionCount: Int) {
        val entitySet = entitySets.getValue(entitySetId)
        isValidAllocation(partitionCount)
        val allocatedPartitions = computePartitions(entitySet, partitionCount)
        setEntitySetPartitions(entitySetId, allocatedPartitions)
    }

    private fun computePartitions(entitySet: EntitySet, partitionCount: Int): List<Int> {
        val defaults = getDefaultPartitions(entitySet.organizationId)
        return when {
            entitySet.flags.contains(EntitySetFlag.AUDIT) -> Collections.unmodifiableList(partitionList)
            entitySet.isLinking -> listOf()
            else -> if (defaults.size < partitionCount) {
                defaults + partitionList.toList().shuffled().take(partitionCount - defaults.size)
            } else {
                defaults
            }
        }
    }

    /**
     * Allocates default partitions for an organization based on emptiest partitions.
     */
    fun allocateDefaultPartitions(organizationId: UUID, partitionCount: Int) {
        setDefaultPartitions(organizationId, allocateDefaultPartitions(partitionCount))
    }

    fun repartition(organizationId: UUID) {
        //TODO
    }

    private fun createMaterializedViewIfNotExists() {
        hds.connection.use { conn ->
            conn.createStatement().use { stmt ->
                stmt.execute(PARTITION_COUNTS.createSql)
            }
        }
    }

    @Scheduled(fixedRate = 5 * 60000) //Update every 5 minutes.
    fun refreshMaterializedView() {
        hds.connection.use { conn ->
            conn.createStatement().use { stmt ->
                stmt.execute(PARTITION_COUNTS.refreshSql)
            }
        }
    }

    fun getPartitionInformation(): Map<Int, Long> {
        return BasePostgresIterable(
                PreparedStatementHolderSupplier(hds, ALL_PARTITIONS) { ps ->
                    ps.setArray(1, PostgresArrays.createIntArray(ps.connection, partitionList))
                }) { it.getInt(PARTITION.name) to it.getLong(COUNT) }.toMap()
    }

    fun allocateDefaultPartitions(partitionCount: Int): List<Int> {
        return getEmptiestPartitions(partitionCount).map { it.first }
    }

    private fun getEmptiestPartitions(desiredPartitions: Int): BasePostgresIterable<Pair<Int, Long>> {
        //A quick note is that the partitions materialized view shouldn't be turned into a distributed table
        //with out addressing the interaction of the order by and limit clauses.
        return BasePostgresIterable(
                PreparedStatementHolderSupplier(hds, EMPTIEST_PARTITIONS) { ps ->
                    ps.setArray(1, PostgresArrays.createIntArray(ps.connection, partitionList))
                    ps.setObject(2, desiredPartitions)
                }) { it.getInt(PARTITION.name) to it.getLong(COUNT) }
    }

    private fun isValidResize(partitionCount: Int) {
        checkArgument(partitionCount > partitionList.size, "Only support resizing to a larger number of partitions.")
    }

    /**
     * Checks to see if a requested allocation of partitions is valid.
     *
     * We don't have to lock here as long as number of partitions is monotonically increasing.
     */
    private fun isValidAllocation(partitionCount: Int) {
        checkArgument(
                partitionCount <= partitionList.size,
                "Cannot request more partitions ($partitionCount) than exist (${partitionList.size}."
        )
    }

}


private val ALL_PARTITIONS = "SELECT ${PARTITION.name}, COALESCE(count,0) as partition_count FROM (SELECT unnest((?)::integer[]) as ${PARTITION.name}) as partitions LEFT JOIN ${PARTITION_COUNTS.name} USING (${PARTITION.name}) ORDER BY partition_count ASC "
private val EMPTIEST_PARTITIONS = "$ALL_PARTITIONS LIMIT ?"
