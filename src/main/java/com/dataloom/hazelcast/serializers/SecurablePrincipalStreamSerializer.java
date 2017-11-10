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

import com.dataloom.authorization.Principal;
import com.dataloom.hazelcast.StreamSerializerTypeIds;
import com.dataloom.organization.roles.Role;
import com.google.common.base.Optional;
import com.hazelcast.nio.ObjectDataInput;
import com.hazelcast.nio.ObjectDataOutput;
import com.kryptnostic.rhizome.pods.hazelcast.SelfRegisteringStreamSerializer;
import com.openlattice.authorization.SecurablePrincipal;
import java.io.IOException;
import java.util.UUID;

/**
 * @author Matthew Tamayo-Rios &lt;matthew@openlattice.com&gt;
 */
public class SecurablePrincipalStreamSerializer implements SelfRegisteringStreamSerializer<SecurablePrincipal> {
    @Override public Class<? extends SecurablePrincipal> getClazz() {
        return SecurablePrincipal.class;
    }

    @Override public void write( ObjectDataOutput out, SecurablePrincipal object ) throws IOException {
        serialize( out, object );
    }

    @Override public SecurablePrincipal read( ObjectDataInput in ) throws IOException {
        return deserialize( in );
    }

    @Override public int getTypeId() {
        return StreamSerializerTypeIds.SECURABLE_PRINCIPAL.ordinal();
    }

    @Override public void destroy() {

    }

    public static void serialize( ObjectDataOutput out, SecurablePrincipal object ) throws IOException {
        Principal principal = object.getPrincipal();
        UUIDStreamSerializer.serialize( out, object.getId() );
        PrincipalStreamSerializer.serialize( out, principal );
        out.writeUTF( object.getTitle() );
        out.writeUTF( object.getDescription() );
        switch ( principal.getType() ) {
            case ROLE:
                //This maybe brittle, but is better than overhead of casting
                UUIDStreamSerializer.serialize( out, object.getAclKey().get( 0 ) );
                break;
            default:
                //Do nothing
        }
    }

    public static SecurablePrincipal deserialize( ObjectDataInput in ) throws IOException {
        UUID id = UUIDStreamSerializer.deserialize( in );
        Principal principal = PrincipalStreamSerializer.deserialize( in );
        String title = in.readUTF();
        String description = in.readUTF();
        switch ( principal.getType() ) {
            case ROLE:
                UUID organizationId = UUIDStreamSerializer.deserialize( in );
                return new Role( Optional.of( id ), organizationId, principal, title, Optional.of( description ) );
            default:
                return new SecurablePrincipal( Optional.of( id ), principal, title, Optional.of( description ) );
        }

    }
}
