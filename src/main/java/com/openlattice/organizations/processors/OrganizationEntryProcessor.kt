package com.openlattice.organizations.processors

import com.kryptnostic.rhizome.hazelcast.processors.AbstractRhizomeEntryProcessor
import com.openlattice.organizations.Organization
import org.slf4j.LoggerFactory
import java.util.*

private val logger = LoggerFactory.getLogger(OrganizationEntryProcessor::class.java)

/**
 *
 * @author Matthew Tamayo-Rios &lt;matthew@openlattice.com&gt;
 */
data class OrganizationEntryProcessor(
        val update: (organization: Organization) -> Unit
) : AbstractRhizomeEntryProcessor<UUID, Organization, Void?>() {
    override fun process(entry: MutableMap.MutableEntry<UUID, Organization?>): Void? {
        val organization = entry.value
        if (organization != null) {
            update(organization)
            entry.setValue(organization)
        } else {
            logger.warn("Organization not found when trying to update value.")
        }
        return null
    }
}

