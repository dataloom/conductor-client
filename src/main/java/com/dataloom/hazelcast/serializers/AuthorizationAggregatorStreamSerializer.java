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

import com.codahale.metrics.annotation.Timed;
import com.dataloom.authorization.Permission;
import com.dataloom.hazelcast.StreamSerializerTypeIds;
import com.hazelcast.nio.ObjectDataInput;
import com.hazelcast.nio.ObjectDataOutput;
import com.kryptnostic.rhizome.pods.hazelcast.SelfRegisteringStreamSerializer;
import com.openlattice.authorization.AuthorizationAggregator;
import java.io.IOException;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map.Entry;
import org.springframework.stereotype.Component;

/**
 * @author Matthew Tamayo-Rios &lt;matthew@openlattice.com&gt;
 */
@Component
public class AuthorizationAggregatorStreamSerializer
        implements SelfRegisteringStreamSerializer<AuthorizationAggregator> {
    @Override public Class<AuthorizationAggregator> getClazz() {
        return AuthorizationAggregator.class;
    }

    @Timed
    @Override
    public void write(
            ObjectDataOutput out, AuthorizationAggregator object ) throws IOException {
        //TODO: We can improve performance of this.
        PermissionMergerStreamSerializer
                .serialize( out, object.getPermissions().entrySet().stream().filter( e -> e.getValue() ).map(
                        Entry::getKey )::iterator );
        PermissionMergerStreamSerializer
                .serialize( out, object.getPermissions().entrySet().stream().filter( e -> !e.getValue() ).map(
                        Entry::getKey )::iterator );

    }

    @Override public AuthorizationAggregator read( ObjectDataInput in ) throws IOException {
        EnumMap<Permission, Boolean> pMap = new EnumMap<>( Permission.class );
        EnumSet<Permission> truePermissions = PermissionMergerStreamSerializer.deserialize( in );
        EnumSet<Permission> falsePermissions = PermissionMergerStreamSerializer.deserialize( in );
        for ( Permission p : truePermissions ) {
            pMap.put( p, true );
        }

        for ( Permission p : falsePermissions ) {
            pMap.put( p, false );
        }

        return new AuthorizationAggregator( pMap );
    }

    @Override public int getTypeId() {
        return StreamSerializerTypeIds.AUTHORIZATION_AGGREGATOR.ordinal();
    }

    @Override public void destroy() {

    }
}
