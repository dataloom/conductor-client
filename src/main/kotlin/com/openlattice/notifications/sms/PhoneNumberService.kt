package com.openlattice.notifications.sms

import com.codahale.metrics.annotation.Timed
import com.hazelcast.core.HazelcastInstance
import com.hazelcast.query.Predicates
import com.openlattice.hazelcast.HazelcastMap
import java.util.*

/**
 *
 * @author Matthew Tamayo-Rios &lt;matthew@openlattice.com&gt;
 */
open class PhoneNumberService(hazelcastInstance: HazelcastInstance) {
    private val phoneNumbers = hazelcastInstance.getMap<SmsInformationKey, SmsEntitySetInformation>(
            HazelcastMap.SMS_INFORMATION.name
    )

    @Timed
    fun lookup(phoneNumber: String): Collection<SmsEntitySetInformation> {
        return phoneNumbers.values(Predicates.equal(SmsInformationMapstore.PHONE_NUMBER_INDEX, phoneNumber))
    }

    @Timed
    fun getPhoneNumbers(organizationId: UUID): Collection<SmsEntitySetInformation> {
        return phoneNumbers.values(Predicates.equal(SmsInformationMapstore.ORGANIZATION_ID_INDEX, organizationId))
    }

    @Timed
    fun setPhoneNumber(organizationId: UUID, entitySetInformationList: Collection<SmsEntitySetInformation>) {
        val entitySetInformationMap = entitySetInformationList.associateBy { smsEsInfo ->
            SmsInformationKey(
                    smsEsInfo.phoneNumber,
                    smsEsInfo.organizationId
            )
        }

        phoneNumbers.putAll(entitySetInformationMap)
    }
}