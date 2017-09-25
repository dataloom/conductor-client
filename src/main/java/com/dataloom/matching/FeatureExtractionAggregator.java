package com.dataloom.matching;

import com.dataloom.blocking.GraphEntityPair;
import com.dataloom.blocking.LinkingEntity;
import com.dataloom.data.EntityKey;
import com.dataloom.linking.HazelcastLinkingGraphs;
import com.dataloom.linking.LinkingEdge;
import com.dataloom.linking.LinkingVertexKey;
import com.dataloom.linking.util.PersonMetric;
import com.hazelcast.aggregation.Aggregator;
import com.hazelcast.core.HazelcastInstance;
import com.hazelcast.core.HazelcastInstanceAware;
import com.hazelcast.core.ICountDownLatch;
import com.kryptnostic.conductor.rpc.ConductorElasticsearchApi;
import com.kryptnostic.rhizome.hazelcast.objects.DelegatedStringSet;
import org.apache.olingo.commons.api.edm.FullQualifiedName;

import java.util.Map;
import java.util.UUID;

public class FeatureExtractionAggregator extends Aggregator<Map.Entry<GraphEntityPair, LinkingEntity>, Double>
        implements HazelcastInstanceAware {
    private GraphEntityPair graphEntityPair;
    private LinkingEntity   linkingEntity;
    private double lightest = Double.MAX_VALUE;
    private Map<FullQualifiedName, UUID> propertyTypeIdIndexedByFqn;

    private transient HazelcastLinkingGraphs graphs = null;

    private ConductorElasticsearchApi elasticsearchApi;

    public FeatureExtractionAggregator(
            GraphEntityPair graphEntityPair,
            LinkingEntity linkingEntity,
            Map<FullQualifiedName, UUID> propertyTypeIdIndexedByFqn ) {
        this(
                graphEntityPair,
                linkingEntity,
                propertyTypeIdIndexedByFqn,
                Double.MAX_VALUE,
                null );
    }

    public FeatureExtractionAggregator(
            GraphEntityPair graphEntityPair,
            LinkingEntity linkingEntity,
            Map<FullQualifiedName, UUID> propertyTypesIndexedByFqn,
            double lightest,
            ConductorElasticsearchApi elasticsearchApi ) {
        this.graphEntityPair = graphEntityPair;
        this.linkingEntity = linkingEntity;
        this.propertyTypeIdIndexedByFqn = propertyTypesIndexedByFqn;
        this.lightest = lightest;
        this.elasticsearchApi = elasticsearchApi;
    }

    @Override
    public void setHazelcastInstance( HazelcastInstance hazelcastInstance ) {
        this.graphs = new HazelcastLinkingGraphs( hazelcastInstance );
    }

    @Override
    public void accumulate( Map.Entry<GraphEntityPair, LinkingEntity> input ) {
        UUID graphId = graphEntityPair.getGraphId();
        EntityKey ek1 = graphEntityPair.getEntityKey();
        EntityKey ek2 = input.getKey().getEntityKey();

        if ( ek1.equals( ek2 ) ) {
            graphs.getOrCreateVertex( graphId, ek1 );
        } else {
            LinkingVertexKey u = graphs.getOrCreateVertex( graphId, ek1 );
            LinkingVertexKey v = graphs.getOrCreateVertex( graphId, ek2 );
            final LinkingEdge edge = new LinkingEdge( u, v );

            Map<UUID, DelegatedStringSet> e1 = linkingEntity.getEntity();
            Map<UUID, DelegatedStringSet> e2 = input.getValue().getEntity();

            double[] dist = PersonMetric.pDistance( e1, e2, propertyTypeIdIndexedByFqn );
            double[][] features = new double[ 1 ][ 0 ];
            features[ 0 ] = dist;
            double weight = elasticsearchApi.getModelScore( features ) + 0.4;
            lightest = Math.min( lightest, weight );
            graphs.setEdgeWeight( edge, weight );
        }

    }

    @Override
    public void combine( Aggregator aggregator ) {
        if ( aggregator instanceof FeatureExtractionAggregator ) {
            FeatureExtractionAggregator other = (FeatureExtractionAggregator) aggregator;
            if ( other.lightest < lightest )
                lightest = other.lightest;
        }

    }

    @Override
    public Double aggregate() {
        return lightest;
    }

    public GraphEntityPair getGraphEntityPair() {
        return graphEntityPair;
    }

    public LinkingEntity getLinkingEntity() {
        return linkingEntity;
    }

    public Map<FullQualifiedName, UUID> getPropertyTypeIdIndexedByFqn() {
        return propertyTypeIdIndexedByFqn;
    }

    public double getLightest() {
        return lightest;
    }

    @Override public boolean equals( Object o ) {
        if ( this == o )
            return true;
        if ( o == null || getClass() != o.getClass() )
            return false;

        FeatureExtractionAggregator that = (FeatureExtractionAggregator) o;

        if ( Double.compare( that.lightest, lightest ) != 0 )
            return false;
        if ( graphEntityPair != null ? !graphEntityPair.equals( that.graphEntityPair ) : that.graphEntityPair != null )
            return false;
        if ( linkingEntity != null ? !linkingEntity.equals( that.linkingEntity ) : that.linkingEntity != null )
            return false;
        return propertyTypeIdIndexedByFqn != null ?
                propertyTypeIdIndexedByFqn.equals( that.propertyTypeIdIndexedByFqn ) :
                that.propertyTypeIdIndexedByFqn == null;
    }

    @Override public int hashCode() {
        int result;
        long temp;
        result = graphEntityPair != null ? graphEntityPair.hashCode() : 0;
        result = 31 * result + ( linkingEntity != null ? linkingEntity.hashCode() : 0 );
        temp = Double.doubleToLongBits( lightest );
        result = 31 * result + (int) ( temp ^ ( temp >>> 32 ) );
        result = 31 * result + ( propertyTypeIdIndexedByFqn != null ? propertyTypeIdIndexedByFqn.hashCode() : 0 );
        return result;
    }
}
