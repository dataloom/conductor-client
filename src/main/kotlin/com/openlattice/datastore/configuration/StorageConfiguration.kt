package com.openlattice.datastore.configuration

import com.amazonaws.services.ec2.model.Storage
import com.amazonaws.services.s3.model.StorageClass
import com.fasterxml.jackson.annotation.JsonTypeInfo
import com.openlattice.data.storage.ByteBlobDataManager
import com.openlattice.data.storage.EntityLoader
import com.openlattice.data.storage.EntityWriter

const val ENGINE = "engine"

/**
 *
 * Interface that allows abstract creating an entity loader and entity writer.
 *
 * @author Matthew Tamayo-Rios &lt;matthew@openlattice.com&gt;
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.CLASS, include = JsonTypeInfo.As.PROPERTY, property = ENGINE)
interface StorageConfiguration {
    fun getLoader(byteBlobDataManager: ByteBlobDataManager): EntityLoader
    fun getWriter(byteBlobDataManager: ByteBlobDataManager): EntityWriter
}

