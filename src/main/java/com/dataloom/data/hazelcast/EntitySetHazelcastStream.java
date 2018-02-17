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

package com.dataloom.data.hazelcast;

import com.openlattice.data.EntityKey;
import com.dataloom.data.HazelcastStream;
import com.dataloom.data.aggregators.EntityAggregator;
import com.dataloom.data.aggregators.EntitySetAggregator;
import com.dataloom.hazelcast.HazelcastMap;
import com.google.common.util.concurrent.ListeningExecutorService;
import com.hazelcast.core.HazelcastInstance;
import com.hazelcast.core.IMap;
import com.hazelcast.query.Predicate;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * @author Matthew Tamayo-Rios &lt;matthew@openlattice.com&gt;
 */
public class EntitySetHazelcastStream extends HazelcastStream<EntityAggregator> {
    private static final Logger logger = LoggerFactory.getLogger( EntitySetHazelcastStream.class );
    private final IMap<EntityKey, UUID> ids;
    private final UUID                  entitySetId;
    private final UUID                  syncId;

    public EntitySetHazelcastStream(
            ListeningExecutorService executor,
            HazelcastInstance hazelcastInstance, UUID entitySetId, UUID syncId ) {
        super( executor, hazelcastInstance );
        this.ids = hazelcastInstance.getMap( HazelcastMap.IDS.name() );
        this.entitySetId = entitySetId;
        this.syncId = syncId;
    }

    @Override
    protected long buffer( UUID streamId ) {
        Predicate p = EntitySets.filterByEntitySetIdAndSyncId( entitySetId, syncId );
        Long count = ids.aggregate( new EntitySetAggregator( streamId ), p );
        if ( count == null ) {
            logger.info( "Count for stream id {} was 0", streamId );
            return 0;
        }
        return count;
    }
}
