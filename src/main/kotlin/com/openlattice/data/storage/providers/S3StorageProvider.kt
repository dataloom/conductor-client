package com.openlattice.data.storage.providers

import com.codahale.metrics.MetricRegistry
import com.google.common.base.Suppliers
import com.hazelcast.core.HazelcastInstance
import com.openlattice.data.storage.*
import com.openlattice.data.storage.aws.S3EntityDatastore
import com.openlattice.hazelcast.HazelcastMap
import java.util.function.Supplier

/**
 *
 * @author Matthew Tamayo-Rios &lt;matthew@openlattice.com&gt;
 */
data class S3StorageProvider(
        private val hazelcastInstance: HazelcastInstance,
        private val byteBlobDataManager: ByteBlobDataManager,
        private val metricRegistry: MetricRegistry,
        override val storageConfiguration: S3StorageConfiguration
) : StorageProvider {
    private val datastore = getS3EntityDatastore(byteBlobDataManager)
    override val entityLoader: EntityLoader
        get() = datastore
    override val entityWriter: EntityWriter
        get() = datastore

    private fun getS3EntityDatastore(
            byteBlobDataManager: ByteBlobDataManager
    ): S3EntityDatastore {
        return S3EntityDatastore(
                storageConfiguration,
                byteBlobDataManager,
                HazelcastMap.S3_OBJECT_STORE.getMap(hazelcastInstance),
                metricRegistry
        )
    }
}