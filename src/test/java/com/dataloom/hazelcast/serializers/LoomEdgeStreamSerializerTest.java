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

import com.dataloom.graph.edge.EdgeKey;
import com.dataloom.graph.edge.LoomEdge;
import com.kryptnostic.rhizome.hazelcast.serializers.AbstractStreamSerializerTest;
import java.util.UUID;

/**
 * @author Matthew Tamayo-Rios &lt;matthew@openlattice.com&gt;
 */
public class LoomEdgeStreamSerializerTest extends AbstractStreamSerializerTest<LoomEdgeStreamSerializer, LoomEdge> {

    @Override protected LoomEdgeStreamSerializer createSerializer() {
        return new LoomEdgeStreamSerializer();
    }

    @Override protected LoomEdge createInput() {
        EdgeKey key = new EdgeKey( new UUID( 0, 0 ),
                new UUID( 0, 1 ),
                new UUID( 0, 2 ),
                new UUID( 0, 3 ),
                new UUID( 0, 4 ) );
        return new LoomEdge( key,
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID() );
    }
}
