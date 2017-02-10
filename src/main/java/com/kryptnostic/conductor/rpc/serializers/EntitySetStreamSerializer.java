/*
 * Copyright (C) 2017. Kryptnostic, Inc (dba Loom)
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
 * You can contact the owner of the copyright at support@thedataloom.com
 */

package com.kryptnostic.conductor.rpc.serializers;

import java.io.IOException;
import java.util.UUID;

import org.apache.olingo.commons.api.edm.FullQualifiedName;
import org.springframework.stereotype.Component;

import com.dataloom.edm.internal.EntitySet;
import com.dataloom.hazelcast.StreamSerializerTypeIds;
import com.google.common.base.Optional;
import com.hazelcast.nio.ObjectDataInput;
import com.hazelcast.nio.ObjectDataOutput;
import com.kryptnostic.rhizome.pods.hazelcast.SelfRegisteringStreamSerializer;

@Component
public class EntitySetStreamSerializer implements SelfRegisteringStreamSerializer<EntitySet> {

    @Override
    public void write( ObjectDataOutput out, EntitySet object )
            throws IOException {
        UUIDStreamSerializer.serialize( out, object.getId() );
        UUIDStreamSerializer.serialize( out, object.getEntityTypeId() );
        out.writeUTF( object.getName() );
        out.writeUTF( object.getTitle() );
        out.writeUTF( object.getDescription() );
    }

    @Override
    public EntitySet read( ObjectDataInput in ) throws IOException {
        UUID id = UUIDStreamSerializer.deserialize( in );
        UUID entityTypeId = UUIDStreamSerializer.deserialize( in );
        String name = in.readUTF();
        String title = in.readUTF();
        Optional<String> description = Optional.of( in.readUTF() );
        EntitySet es = new EntitySet(
                id,
                entityTypeId,
                name,
                title,
                description );
        return es;
    }

    @Override
    public int getTypeId() {
        return StreamSerializerTypeIds.ENTITY_SET.ordinal();
    }

    @Override
    public void destroy() {

    }

    @Override
    public Class<EntitySet> getClazz() {
        return EntitySet.class;
    }

}
