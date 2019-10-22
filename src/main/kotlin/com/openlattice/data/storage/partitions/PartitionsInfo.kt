package com.openlattice.data.storage.partitions

/**
 *
 * @author Matthew Tamayo-Rios &lt;matthew@openlattice.com&gt;
 */
data class PartitionsInfo(
        val partitions: LinkedHashSet<Int>,
        val partitionsVersion: Int
)