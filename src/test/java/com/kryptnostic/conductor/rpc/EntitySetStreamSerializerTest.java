package com.kryptnostic.conductor.rpc;

import java.io.Serializable;

import org.apache.olingo.commons.api.edm.FullQualifiedName;

import com.kryptnostic.conductor.rpc.odata.EntitySet;
import com.kryptnostic.conductor.rpc.serializers.EntitySetStreamSerializer;
import com.kryptnostic.rhizome.hazelcast.serializers.BaseSerializerTest;

public class EntitySetStreamSerializerTest extends BaseSerializerTest<EntitySetStreamSerializer, EntitySet>
        implements Serializable {
    private static final long serialVersionUID = 8869472746330274551L;

    @Override
    protected EntitySet createInput() {
        EntitySet es = new EntitySet()
                .setName( "foo" )
                .setType( new FullQualifiedName( "bar", "baz" ) )
                .setTitle( "yay" );
        return es;
    }

    @Override
    protected EntitySetStreamSerializer createSerializer() {
        return new EntitySetStreamSerializer();
    }

}
