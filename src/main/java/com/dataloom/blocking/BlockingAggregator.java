package com.dataloom.blocking;

import com.dataloom.linking.HazelcastBlockingService;
import com.hazelcast.aggregation.Aggregator;
import com.hazelcast.core.HazelcastInstance;
import com.hazelcast.core.HazelcastInstanceAware;
import com.hazelcast.core.IAtomicLong;
import com.hazelcast.core.ICountDownLatch;
import org.apache.olingo.commons.api.edm.FullQualifiedName;

import java.util.Map;
import java.util.UUID;

public class BlockingAggregator extends Aggregator<Map.Entry<GraphEntityPair, LinkingEntity>, Boolean>
        implements HazelcastInstanceAware {
    private           HazelcastBlockingService     blockingService;
    private           UUID                         graphId;
    private           Map<UUID, UUID>              entitySetIdsToSyncIds;
    private           Map<FullQualifiedName, UUID> propertyTypesIndexedByFqn;
    private transient ICountDownLatch              countDownLatch;

    private final int MAX_FAILED_CONSEC_ATTEMPTS = 5;

    public BlockingAggregator(
            UUID graphId,
            Map<UUID, UUID> entitySetIdsToSyncIds,
            Map<FullQualifiedName, UUID> propertyTypesIndexedByFqn ) {
        this( graphId, entitySetIdsToSyncIds, propertyTypesIndexedByFqn, null );
    }

    public BlockingAggregator(
            UUID graphId,
            Map<UUID, UUID> entitySetIdsToSyncIds,
            Map<FullQualifiedName, UUID> propertyTypesIndexedByFqn,
            HazelcastBlockingService blockingService ) {
        this.graphId = graphId;
        this.entitySetIdsToSyncIds = entitySetIdsToSyncIds;
        this.propertyTypesIndexedByFqn = propertyTypesIndexedByFqn;
        this.blockingService = blockingService;
    }

    @Override public void accumulate( Map.Entry<GraphEntityPair, LinkingEntity> input ) {
        GraphEntityPair graphEntityPair = input.getKey();
        LinkingEntity linkingEntity = input.getValue();
        blockingService
                .blockAndMatch( graphEntityPair, linkingEntity, entitySetIdsToSyncIds, propertyTypesIndexedByFqn );
    }

    @Override public void combine( Aggregator aggregator ) {
    }

    @Override public Boolean aggregate() {
        int numConsecFailures = 0;
        long count = countDownLatch.getCount();
        while ( count > 0 && numConsecFailures < MAX_FAILED_CONSEC_ATTEMPTS ) {
            try {
                Thread.sleep( 5000 );
                long newCount = countDownLatch.getCount();
                if ( newCount == count ) {
                    System.err.println( "Nothing is happening." );
                    numConsecFailures++;
                } else numConsecFailures = 0;
                count = newCount;
            } catch ( InterruptedException e ) {
                System.err.println( "Error occurred while waiting for matching to finish." );
            }
        }
        if (numConsecFailures == MAX_FAILED_CONSEC_ATTEMPTS) return false;
        return true;
    }

    @Override public void setHazelcastInstance( HazelcastInstance hazelcastInstance ) {
        this.countDownLatch = hazelcastInstance.getCountDownLatch( graphId.toString() );
    }

    public UUID getGraphId() {
        return graphId;
    }

    public Map<UUID, UUID> getEntitySetIdsToSyncIds() {
        return entitySetIdsToSyncIds;
    }

    public Map<FullQualifiedName, UUID> getPropertyTypesIndexedByFqn() {
        return propertyTypesIndexedByFqn;
    }

    @Override public boolean equals( Object o ) {
        if ( this == o )
            return true;
        if ( o == null || getClass() != o.getClass() )
            return false;

        BlockingAggregator that = (BlockingAggregator) o;

        if ( !graphId.equals( that.graphId ) )
            return false;
        return entitySetIdsToSyncIds.equals( that.entitySetIdsToSyncIds );
    }

    @Override public int hashCode() {
        int result = graphId.hashCode();
        result = 31 * result + entitySetIdsToSyncIds.hashCode();
        return result;
    }
}
