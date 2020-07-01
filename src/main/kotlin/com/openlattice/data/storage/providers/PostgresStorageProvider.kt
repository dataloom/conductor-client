package com.openlattice.data.storage.providers

import com.codahale.metrics.MetricRegistry
import com.google.common.base.Suppliers
import com.google.common.eventbus.EventBus
import com.openlattice.data.storage.*
import com.openlattice.datastore.services.EdmManager
import com.openlattice.datastore.services.EntitySetManager
import com.openlattice.linking.LinkingQueryService
import com.openlattice.linking.PostgresLinkingFeedbackService
import java.util.function.Supplier

/**
 *
 * @author Matthew Tamayo-Rios &lt;matthew@openlattice.com&gt;
 */
class PostgresStorageProvider(
        private val dataQueryService: PostgresEntityDataQueryService,
        private val edmManager: EdmManager,
        private val entitySetManager: EntitySetManager,
        private val metricRegistry: MetricRegistry,
        private val eventBus: EventBus,
        private val feedbackQueryService: PostgresLinkingFeedbackService,
        private val linkingQueryService: LinkingQueryService,
        override val storageConfiguration: PostgresStorageConfiguration
) : StorageProvider {
    override val entityLoader: Supplier<EntityLoader> = Suppliers.memoize(::getLoader)
    override val entityWriter: Supplier<EntityWriter> = Suppliers.memoize(::getWriter)

    private fun getLoader(): EntityLoader {
        return getPostgresEntityDatastore(byteBlobDataManager)
    }

    private fun getWriter(): EntityWriter {
        return getPostgresEntityDatastore(byteBlobDataManager)
    }

    private fun getPostgresEntityDatastore(): PostgresEntityDatastore {
        return PostgresEntityDatastore(
                dataQueryService,
                edmManager,
                entitySetManager,
                metricRegistry,
                eventBus,
                feedbackQueryService,
                linkingQueryService
        )
    }

}