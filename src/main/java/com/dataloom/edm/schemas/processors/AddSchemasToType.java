package com.dataloom.edm.schemas.processors;

import java.util.Collection;
import java.util.Map.Entry;
import java.util.Set;
import java.util.UUID;

import org.apache.olingo.commons.api.edm.FullQualifiedName;

import com.dataloom.edm.internal.EntityType;
import com.dataloom.edm.internal.PropertyType;
import com.dataloom.edm.internal.AbstractSchemaAssociatedSecurableType;
import com.hazelcast.util.Preconditions;
import com.kryptnostic.rhizome.hazelcast.processors.AbstractRhizomeEntryProcessor;

/**
 * @author Matthew Tamayo-Rios &lt;matthew@kryptnostic.com&gt;
 *
 * @param <T> The Edm category type to be modified. Either {@link PropertyType} or {@link EntityType}
 */
public class AddSchemasToType extends AbstractRhizomeEntryProcessor<UUID, AbstractSchemaAssociatedSecurableType, Void> {
    private static final long                   serialVersionUID = 7905367675743576380L;
    private final Collection<FullQualifiedName> schemas;

    public AddSchemasToType( Collection<FullQualifiedName> schemas ) {
        this.schemas = Preconditions.checkNotNull( schemas );
    }

    @Override
    public Void process( Entry<UUID, AbstractSchemaAssociatedSecurableType> entry ) {
        AbstractSchemaAssociatedSecurableType propertyType = entry.getValue();
        if ( propertyType != null ) {
            Set<FullQualifiedName> schemas = entry.getValue().getSchemas();
            schemas.addAll( schemas );
        }
        return null;
    }

    public Collection<FullQualifiedName> getSchemas() {
        return schemas;
    }

}
