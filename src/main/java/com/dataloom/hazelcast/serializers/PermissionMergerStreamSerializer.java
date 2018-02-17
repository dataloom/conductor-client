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

package com.dataloom.hazelcast.serializers;

import com.openlattice.authorization.Permission;
import com.dataloom.authorization.processors.PermissionMerger;
import com.openlattice.authorization.securable.SecurableObjectType;
import com.dataloom.hazelcast.StreamSerializerTypeIds;
import com.hazelcast.nio.ObjectDataInput;
import com.hazelcast.nio.ObjectDataOutput;
import com.kryptnostic.rhizome.pods.hazelcast.SelfRegisteringStreamSerializer;
import java.io.IOException;
import java.util.BitSet;
import java.util.EnumSet;
import org.springframework.stereotype.Component;

/**
 * @author Matthew Tamayo-Rios &lt;matthew@kryptnostic.com&gt;
 */
@Component
public class PermissionMergerStreamSerializer implements SelfRegisteringStreamSerializer<PermissionMerger> {
    private static final Permission[] P = Permission.values();

    @Override public Class<? extends PermissionMerger> getClazz() {
        return PermissionMerger.class;
    }

    @Override public void write(
            ObjectDataOutput out, PermissionMerger object ) throws IOException {
        serialize( out, object.getBackingCollection() );
        AceValueStreamSerializer.serialize( out, object.getSecurableObjectType() );
    }

    @Override public PermissionMerger read( ObjectDataInput in ) throws IOException {
        EnumSet<Permission> ps = deserialize( in );
        SecurableObjectType securableObjectType = AceValueStreamSerializer.deserialize( in );
        return new PermissionMerger( ps, securableObjectType );
    }

    @Override public int getTypeId() {
        return StreamSerializerTypeIds.PERMISSION_MERGER.ordinal();
    }

    @Override public void destroy() {

    }

    public static EnumSet<Permission> deserialize( ObjectDataInput in ) throws IOException {
        BitSet bs = BitSet.valueOf( in.readLongArray() );

        EnumSet<Permission> ps = EnumSet.noneOf( Permission.class );
        for ( int i = 0; i < P.length; ++i ) {
            if ( bs.get( i ) ) {
                ps.add( P[ i ] );
            }
        }
        return ps;
    }

    public static void serialize( ObjectDataOutput out, Iterable<Permission> object ) throws IOException {
        BitSet bs = new BitSet( P.length );
        for ( Permission p : object ) {
            bs.set( p.ordinal() );
        }
        out.writeLongArray( bs.toLongArray() );
        //TODO: Move this method to class where it's not as hidden

    }
}
