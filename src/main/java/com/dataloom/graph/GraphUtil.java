package com.dataloom.graph;

import com.dataloom.data.EntityKey;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * @author Matthew Tamayo-Rios &lt;matthew@kryptnostic.com&gt;
 */
public final class GraphUtil {
    private static final Logger logger = LoggerFactory.getLogger( GraphUtil.class );

    private GraphUtil() {
    }

    public static EntityKey min( EntityKey a, EntityKey b ) {
        return a.compareTo( b ) < 0 ? a : b;
    }

    public static EntityKey max( EntityKey a, EntityKey b ) {
        return a.compareTo( b ) > 0 ? a : b;
    }

    public static SimpleEdge simpleEdge( EntityKey a, EntityKey b ) {
        return new SimpleEdge( a, b );
    }

    public static DirectedEdge edge( EntityKey a, EntityKey b ) {
        return new DirectedEdge( a, b );
    }
}
