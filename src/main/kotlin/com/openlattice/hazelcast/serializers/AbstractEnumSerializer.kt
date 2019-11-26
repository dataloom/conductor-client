package com.openlattice.hazelcast.serializers

import com.google.common.cache.CacheBuilder
import com.google.common.cache.CacheLoader
import com.google.common.cache.LoadingCache
import com.hazelcast.nio.ObjectDataInput
import com.hazelcast.nio.ObjectDataOutput
import com.kryptnostic.rhizome.pods.hazelcast.SelfRegisteringStreamSerializer

abstract class AbstractEnumSerializer<T : Enum<T>>(private val targetClass: Class<T> ) : SelfRegisteringStreamSerializer<Enum<T>> {

    companion object {
        private val enumCache: LoadingCache<Class<*>, Array<*>> = CacheBuilder.newBuilder().build( CacheLoader.from { key ->
            key!!.enumConstants
        })

        @JvmStatic
        fun serialize(out: ObjectDataOutput, `object`: Enum<*>) {
            out.writeInt(`object`.ordinal)
        }

        @JvmStatic
        fun deserialize(targetClass: Class<*>, `in`: ObjectDataInput): Enum<*> {
            val ord = `in`.readInt()
            return enumCache.get(targetClass)[ord] as Enum<*>
        }
    }

    override fun write(out: ObjectDataOutput, `object`: Enum<T>) {
        return serialize(out, `object` )
    }

    override fun read(`in`: ObjectDataInput): Enum<T> {
        return deserialize( targetClass, `in` ) as Enum<T>
    }
}