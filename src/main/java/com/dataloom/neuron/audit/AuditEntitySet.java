package com.dataloom.neuron.audit;

import java.util.Collection;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import org.apache.olingo.commons.api.edm.EdmPrimitiveTypeKind;
import org.apache.olingo.commons.api.edm.FullQualifiedName;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.dataloom.authorization.Principal;
import com.dataloom.authorization.PrincipalType;
import com.dataloom.data.DatasourceManager;
import com.dataloom.edm.EntitySet;
import com.dataloom.edm.type.EntityType;
import com.dataloom.edm.type.PropertyType;
import com.dataloom.neuron.signals.Signal;
import com.datastax.driver.core.utils.UUIDs;
import com.google.common.base.Optional;
import com.google.common.collect.HashMultimap;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Maps;
import com.google.common.collect.SetMultimap;
import com.google.common.collect.Sets;
import com.kryptnostic.datastore.services.EdmManager;

/*
 * temporary solution for initializing the Audit EntitySet until we can figure out a better place for initializing
 * system-wide dependencies
 */
public class AuditEntitySet {

    private static final Logger logger = LoggerFactory.getLogger( AuditEntitySet.class );

    private static final String AUDIT_ENTITY_SET_NAME = "Loom Audit Entity Set";
    private static final String LOOM_AUDIT_NAMESPACE  = "LOOM_AUDIT";

    private static final FullQualifiedName DETAILS_PT_FQN = new FullQualifiedName( LOOM_AUDIT_NAMESPACE, "DETAILS" );
    private static final FullQualifiedName TYPE_PT_FQN    = new FullQualifiedName( LOOM_AUDIT_NAMESPACE, "TYPE" );
    private static final FullQualifiedName AUDIT_ET_FQN   = new FullQualifiedName( LOOM_AUDIT_NAMESPACE, "AUDIT" );

    // TODO: where does this belong?
    public static final Principal LOOM_PRINCIPAL = new Principal( PrincipalType.USER, "Loom" );

    private static Collection<PropertyType> PROPERTIES;
    private static PropertyType             TYPE_PROPERTY_TYPE;
    private static PropertyType             DETAILS_PROPERTY_TYPE;
    private static EntityType               AUDIT_ENTITY_TYPE;
    private static EntitySet                AUDIT_ENTITY_SET;

    private final DatasourceManager dataSourceManager;
    private final EdmManager        entityDataModelManager;

    public AuditEntitySet( DatasourceManager dataSourceManager, EdmManager entityDataModelManager ) {

        this.dataSourceManager = dataSourceManager;
        this.entityDataModelManager = entityDataModelManager;

        if ( entityDataModelManager.checkEntitySetExists( AUDIT_ENTITY_SET_NAME ) ) {
            initialize();
        } else {
            createAuditEntitySet();
        }
    }

    private void initialize() {

        TYPE_PROPERTY_TYPE = entityDataModelManager.getPropertyType( TYPE_PT_FQN );
        DETAILS_PROPERTY_TYPE = entityDataModelManager.getPropertyType( DETAILS_PT_FQN );
        AUDIT_ENTITY_TYPE = entityDataModelManager.getEntityType( AUDIT_ET_FQN );
        AUDIT_ENTITY_SET = entityDataModelManager.getEntitySet( AUDIT_ENTITY_SET_NAME );

        setProperties();
    }

    private void createAuditEntitySet() {

        TYPE_PROPERTY_TYPE = new PropertyType(
                TYPE_PT_FQN,
                "Type",
                Optional.of( "The type of event being logged." ),
                ImmutableSet.of(),
                EdmPrimitiveTypeKind.String
        );

        DETAILS_PROPERTY_TYPE = new PropertyType(
                DETAILS_PT_FQN,
                "Details",
                Optional.of( "Any details about the event being logged." ),
                ImmutableSet.of(),
                EdmPrimitiveTypeKind.String
        );

        AUDIT_ENTITY_TYPE = new EntityType(
                AUDIT_ET_FQN,
                "Loom Audit",
                "The Loom Audit Entity Type.",
                ImmutableSet.of(),
                Sets.newLinkedHashSet( Sets.newHashSet( TYPE_PROPERTY_TYPE.getId() ) ),
                Sets.newLinkedHashSet( Sets.newHashSet( TYPE_PROPERTY_TYPE.getId(), DETAILS_PROPERTY_TYPE.getId() ) ),
                Optional.absent(),
                Optional.absent()
        );

        AUDIT_ENTITY_SET = new EntitySet(
                AUDIT_ENTITY_TYPE.getId(),
                AUDIT_ENTITY_SET_NAME,
                AUDIT_ENTITY_SET_NAME,
                Optional.of( AUDIT_ENTITY_SET_NAME ),
                ImmutableSet.of( "info@thedataloom.com" )
        );

        entityDataModelManager.createPropertyTypeIfNotExists( TYPE_PROPERTY_TYPE );
        entityDataModelManager.createPropertyTypeIfNotExists( DETAILS_PROPERTY_TYPE );
        entityDataModelManager.createEntityType( AUDIT_ENTITY_TYPE );
        entityDataModelManager.createEntitySet( LOOM_PRINCIPAL, AUDIT_ENTITY_SET );

        setProperties();

        UUID syncId = dataSourceManager.createNewSyncIdForEntitySet( AUDIT_ENTITY_SET.getId() );
        dataSourceManager.setCurrentSyncId( AUDIT_ENTITY_SET.getId(), syncId );
    }

    private void setProperties() {

        PROPERTIES = ImmutableList.of(
                TYPE_PROPERTY_TYPE,
                DETAILS_PROPERTY_TYPE
        );
    }

    public static UUID getId() {

        return AUDIT_ENTITY_SET.getId();
    }

    public static Map<UUID, EdmPrimitiveTypeKind> getPropertyDataTypesMap() {

        return PROPERTIES
                .stream()
                .collect(
                        Collectors.toMap( PropertyType::getId, PropertyType::getDatatype )
                );
    }

    public static Map<String, SetMultimap<UUID, Object>> prepareEntityData( Signal signal ) {

        SetMultimap<UUID, Object> propertyValuesMap = HashMultimap.create();
        propertyValuesMap.put( DETAILS_PROPERTY_TYPE.getId(), signal.getDetails().or( "" ) );
        propertyValuesMap.put( TYPE_PROPERTY_TYPE.getId(), signal.getType().name() );

        Map<String, SetMultimap<UUID, Object>> entityDataMap = Maps.newHashMap();
        entityDataMap.put( UUIDs.random().toString(), propertyValuesMap );

        return entityDataMap;
    }
}
