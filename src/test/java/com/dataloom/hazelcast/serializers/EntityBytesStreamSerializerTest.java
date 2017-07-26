/*
 * Copyright (C) 2017. OpenLattice, Inc
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 *
 * You can contact the owner of the copyright at support@openlattice.com
 *
 */

package com.dataloom.hazelcast.serializers;

import com.dataloom.data.storage.EntityBytes;
import com.dataloom.mapstores.TestDataFactory;
import com.google.common.collect.HashMultimap;
import com.google.common.collect.ImmutableSetMultimap;
import com.kryptnostic.rhizome.hazelcast.serializers.AbstractStreamSerializerTest;
import java.util.UUID;
import org.apache.commons.lang3.RandomUtils;

/**
 * @author Matthew Tamayo-Rios &lt;matthew@openlattice.com&gt;
 */
public class EntityBytesStreamSerializerTest
        extends AbstractStreamSerializerTest<EntityBytesStreamSerializer, EntityBytes> {
    @Override protected EntityBytesStreamSerializer createSerializer() {
        return new EntityBytesStreamSerializer();
    }

    @Override protected EntityBytes createInput() {
        return new EntityBytes( TestDataFactory.entityKey(),
                HashMultimap.create( ImmutableSetMultimap.of(
                        UUID.randomUUID(), RandomUtils.nextBytes( 10 ),
                        UUID.randomUUID(), RandomUtils.nextBytes( 10 ) ) ) );
    }
}
