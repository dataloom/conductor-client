package com.openlattice.data.storage

import com.google.common.collect.SetMultimap
import com.openlattice.data.DeleteType
import com.openlattice.data.EntitySetData
import com.openlattice.data.WriteEvent
import com.openlattice.edm.type.PropertyType
import com.openlattice.postgres.streams.BasePostgresIterable
import org.apache.olingo.commons.api.edm.FullQualifiedName
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.nio.ByteBuffer
import java.util.*
import java.util.stream.Stream

/**
 *
 * @author Matthew Tamayo-Rios &lt;matthew@openlattice.com&gt;
 */
interface EntityDatastore : EntityLoader, EntityWriter