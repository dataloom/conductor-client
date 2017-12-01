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

import com.dataloom.authorization.Permission;
import com.kryptnostic.rhizome.hazelcast.serializers.AbstractStreamSerializerTest;
import com.openlattice.authorization.AuthorizationAggregator;
import java.util.EnumMap;

/**
 * @author Matthew Tamayo-Rios &lt;matthew@openlattice.com&gt;
 */
public class AuthorizationAggregatorStreamSerializerTest extends AbstractStreamSerializerTest<AuthorizationAggregatorStreamSerializer,AuthorizationAggregator> {

    @Override protected AuthorizationAggregatorStreamSerializer createSerializer() {
        return new AuthorizationAggregatorStreamSerializer();
    }

    @Override protected AuthorizationAggregator createInput() {
        EnumMap<Permission, Boolean> pmap = new EnumMap<> (Permission.class);
        pmap.put( Permission.OWNER, true );
        pmap.put( Permission.LINK, true );
        pmap.put( Permission.READ, false );
        pmap.put( Permission.WRITE, true );
        pmap.put( Permission.DISCOVER, false );
        return new AuthorizationAggregator( pmap );
    }
}
