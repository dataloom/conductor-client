package com.openlattice.hazelcast.serializers

import com.hazelcast.nio.ObjectDataInput
import com.hazelcast.nio.ObjectDataOutput
import com.kryptnostic.rhizome.pods.hazelcast.SelfRegisteringStreamSerializer
import com.openlattice.assembler.AssemblerConnectionManager
import com.openlattice.assembler.AssemblerConnectionManagerDependent
import com.openlattice.assembler.processors.InitializeOrganizationAssemblyProcessor
import com.openlattice.hazelcast.StreamSerializerTypeIds
import org.springframework.stereotype.Component

/**
 *
 * @author Matthew Tamayo-Rios &lt;matthew@openlattice.com&gt;
 */

@Component
class InitializeOrganizationAssemblyProcessorStreamSerializer
    : SelfRegisteringStreamSerializer<InitializeOrganizationAssemblyProcessor>,
        AssemblerConnectionManagerDependent<Void?> {
    private lateinit var acm: AssemblerConnectionManager

    override fun getClazz(): Class<InitializeOrganizationAssemblyProcessor> {
        return InitializeOrganizationAssemblyProcessor::class.java
    }

    override fun write(out: ObjectDataOutput, obj: InitializeOrganizationAssemblyProcessor) {
        out.writeUTF(obj.dbName)
    }

    override fun read(input: ObjectDataInput): InitializeOrganizationAssemblyProcessor {
        val dbName = input.readUTF()
        return InitializeOrganizationAssemblyProcessor(dbName).init(acm)
    }

    override fun getTypeId(): Int {
        return StreamSerializerTypeIds.INITIALIZE_ORGANIZATION_ASSEMBLY_PROCESSOR.ordinal
    }

    override fun init(acm: AssemblerConnectionManager): Void? {
        this.acm = acm
        return null
    }
}