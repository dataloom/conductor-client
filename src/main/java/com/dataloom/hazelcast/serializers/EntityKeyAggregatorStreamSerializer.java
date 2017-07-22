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

import com.dataloom.data.EntityKeyAggregator;
import com.dataloom.hazelcast.StreamSerializerTypeIds;
import com.hazelcast.nio.ObjectDataInput;
import com.hazelcast.nio.ObjectDataOutput;
import com.kryptnostic.rhizome.pods.hazelcast.SelfRegisteringStreamSerializer;
import java.io.IOException;
import org.springframework.stereotype.Component;

/**
 * @author Matthew Tamayo-Rios &lt;matthew@openlattice.com&gt;
 */
@Component
public class EntityKeyAggregatorStreamSerializer implements SelfRegisteringStreamSerializer<EntityKeyAggregator> {
    @Override public Class<EntityKeyAggregator> getClazz() {
        return EntityKeyAggregator.class;
    }

    @Override public void write( ObjectDataOutput out, EntityKeyAggregator object ) throws IOException {
        UUIDStreamSerializer.serialize( out, object.getStreamId() );
        ;
    }

    @Override public EntityKeyAggregator read( ObjectDataInput in ) throws IOException {
        return new EntityKeyAggregator( UUIDStreamSerializer.deserialize( in ) );
    }

    @Override public int getTypeId() {
        return StreamSerializerTypeIds.ENTITY_KEY_AGGREGATOR.ordinal();
    }

    @Override public void destroy() {

    }
}
