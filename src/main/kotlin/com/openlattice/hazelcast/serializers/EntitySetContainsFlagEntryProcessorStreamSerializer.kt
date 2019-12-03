package com.openlattice.hazelcast.serializers

import com.hazelcast.nio.ObjectDataInput
import com.hazelcast.nio.ObjectDataOutput
import com.kryptnostic.rhizome.pods.hazelcast.SelfRegisteringStreamSerializer
import com.openlattice.assembler.processors.EntitySetContainsFlagEntryProcessor
import com.openlattice.hazelcast.StreamSerializerTypeIds
import org.springframework.stereotype.Component

@Component
class EntitySetContainsFlagEntryProcessorStreamSerializer  : SelfRegisteringStreamSerializer<EntitySetContainsFlagEntryProcessor> {
    override fun getTypeId(): Int {
        return StreamSerializerTypeIds.ENTITY_SET_CONTAINS_FLAG_ENTRY_PROCESSOR.ordinal
    }

    override fun getClazz(): Class<out EntitySetContainsFlagEntryProcessor> {
        return EntitySetContainsFlagEntryProcessor::class.java
    }

    override fun write(out: ObjectDataOutput, `object`: EntitySetContainsFlagEntryProcessor) {
        EntitySetFlagStreamSerializer.serialize(out, `object`.flag)
    }

    override fun read(`in`: ObjectDataInput): EntitySetContainsFlagEntryProcessor {
        return EntitySetContainsFlagEntryProcessor(EntitySetFlagStreamSerializer.deserialize(`in`))
    }

}