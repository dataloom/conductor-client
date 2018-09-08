

/*
 * Copyright (C) 2018. OpenLattice, Inc.
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

package com.openlattice.hazelcast;

/**
 * Simplifies management of type ids within Hazelcast for serialization. Can be re-ordered safely unless doing hot
 * upgrade. <b>NOTE: Leave first entry in place</b>
 *
 *  */
public enum StreamSerializerTypeIds {
    /**
     * Move this one, break everything.
     */
    STREAM_SERIALIZER_IDS_MUST_BE_POSTIVE,
    ACE_VALUE,
    ACL_KEY,
    ACL_KEY_SET,
    AUDITABLE_SIGNAL,
    AUDIT_METRIC_INCREMENTER,
    AUTHORIZATION_AGGREGATOR,
    BYTE_BUFFER,
    RUNNABLE,
    CALLABLE,
    COMP_ACL_KEY,
    DATA_KEY,
    EDGE_COUNT_ENTRY_PROCESSOR,
    EMPLOYEE,
    ENTITY_KEY,
    ENTITY_DATA_KEY,
    ENTITY_DATA_VALUE,
    ENTITY_SET,
    ENTITY_TYPE,
    EOF,
    COMPLEX_TYPE,
    ENUM_TYPE,
    LINKING_EDGE,
    LINKING_VERTEX,
    LINKING_ENTITY_KEY,
    LINKING_VERTEX_KEY,
    MERGER_AGGREGATOR,
    NEIGHBORHOOD_STREAM_SERIALIZER,
    PROPERTY_TYPE,
    PERMISSION_MERGER,
    PERMISSION_REMOVER,
    PRINCIPAL_MERGER,
    PRINCIPAL_REMOVER,
    FULL_QUALIFIED_NAME,
    EDM_PRIMITIVE_TYPE_KIND,
    UUID,
    ACE_KEY,
    PRINCIPAL,
    STRING_SET,
    PERMISSION_SET,
    ORGANIZATION_PRINCIPAL,
    OFFSET_DATETIME,
    ACLROOT_PRINCIPAL_PAIR,
    PERMISSIONS_REQUEST_DETAILS,
    PRINCIPAL_REQUESTID_PAIR,
    ACLROOT_REQUEST_DETAILS_PAIR,
    UUID_SET,
    PRINCIPAL_SET,
    TICKET_KEY,
    REQUEST_STATUS,
    REQUEST,
    STATUS,
    EDM_PRIMITIVE_TYPE_KIND_GETTER,
    ADD_SCHEMAS_TO_TYPE,
    REMOVE_SCHEMAS_FROM_TYPE,
    SCHEMA_MERGER,
    SCHEMA_REMOVER,
    ADD_PROPERTY_TYPES_TO_ENTITY_TYPE_PROCESSOR,
    REMOVE_PROPERTY_TYPES_FROM_ENTITY_TYPE_PROCESSOR,
    EMAIL_DOMAINS_MERGER,
    EMAIL_DOMAINS_REMOVER,
    UPDATE_REQUEST_STATUS_PROCESSOR,
    UPDATE_PROPERTY_TYPE_METADATA_PROCESSOR,
    UPDATE_ENTITY_TYPE_METADATA_PROCESSOR,
    UPDATE_ENTITY_SET_METADATA_PROCESSOR,
    ENTITY_KEY_SET,
    ENTITY,
    SEARCH_RESULT,
    ENTITY_KEY_ID_SEARCH_RESULT,
    CONDUCTOR_ELASTICSEARCH_CALL,
    ASSOCIATION_TYPE,
    ROLE,
    ROLE_KEY,
    ROLE_TITLE_UPDATER,
    ROLE_DESCRIPTION_UPDATER,
    SECURABLE_OBJECT_TYPE_UPDATE,
    EDGE_KEY,
    LOOM_VERTEX,
    LOOM_EDGE,
    SIGNAL,
    SIGNAL_TYPE,
    ADD_DST_ENTITY_TYPES_TO_ASSOCIATION_TYPE_PROCESSOR,
    ADD_SRC_ENTITY_TYPES_TO_ASSOCIATION_TYPE_PROCESSOR,
    REMOVE_DST_ENTITY_TYPES_FROM_ASSOCIATION_TYPE_PROCESSOR,
    REMOVE_SRC_ENTITY_TYPES_FROM_ASSOCIATION_TYPE_PROCESSOR,
    SET_MULTIMAP,
    ENTITY_BYTES,
    ENTITY_SET_AGGREGATOR,
    ENTITY_BYTES_MERGER,
    ENTITY_KEY_AGGREGATOR,
    GRAPH_COUNT,
    INCREMENTABLE_WEIGHT_ID,
    ENTITIES,
    ENTITIES_AGGREGATOR,
    ENTITY_AGGREGATOR,
    HAZELCAST_STREAM_ENTITY_SET_AGGREGATOR,
    NESTED_PRINCIPAL_MERGER,
    ORGANIZATION_MEMBER_MERGER,
    ORGANIZATION_MEMBER_REMOVER,
    ORGANIZATION_MEMBER_ROLE_MERGER,
    ORGANIZATION_MEMBER_ROLE_REMOVER,
    ENTITY_SET_PROPERTY_KEY,
    ENTITY_SET_PROPERTY_METADATA,
    UPDATE_ENTITY_SET_PROPERTY_METADATA_PROCESSOR,
    WEIGHTED_LINKING_EDGE,
    WEIGHTED_LINKING_EDGES,
    LIGHEST_EDGE_AGGREGATOR,
    LOADING_AGGREGATOR,
    BLOCKING_AGGREGATOR,
    LINKING_ENTITY,
    ENTITY_KEY_ARRAY,
    GRAPH_ENTITY_PAIR,
    FEATURE_EXTRACTION_AGGREGATOR,
    INITIALIZER_AGGREGATOR,
    MERGE_VERTEX_AGGREGATOR,
    MERGE_EDGE_AGGREGATOR,
    SECURABLE_PRINCIPAL,
    WEIGHTED_LINKING_VERTEX_KEY,
    WEIGHTED_LINKING_VERTEX_KEY_SET,
    WEIGHTED_LINKING_VERTEX_KEY_MERGER,
    WEIGHTED_LINKING_VERTEX_KEY_VALUE_REMOVER,
    APP,
    APP_TYPE,
    APP_CONFIG_KEY,
    APP_TYPE_SETTING,
    ORGANIZATION_APP_MERGER,
    ORGANIZATION_APP_REMOVER,
    ADD_APP_TYPES_TO_APP_PROCESSOR,
    REMOVE_APP_TYPES_FROM_APP_PROCESSOR,
    UPDATE_APP_CONFIG_ENTITY_SET_ID_PROCESSOR,
    UPDATE_APP_CONFIG_PERMISSIONS_PROCESSOR,
    UPDATE_APP_METADATA_PROCESSOR,
    UPDATE_APP_TYPE_METADATA_PROCESSOR,
    ORGANIZATION,
    ORGANIZATION_CREATED_EVENT,
    AUTH0_USER_BASIC,
    NEIGHBOR_ENTITY_SET_AGGREGATOR,
    NEIGHBOR_TRIPLET_SET,
    ADD_PRIMARY_KEYS_TO_ENTITY_TYPE_PROCESSOR,
    REMOVE_PRIMARY_KEYS_FROM_ENTITY_TYPE_PROCESSOR,
    RANGE,
    AUTH0_SYNC_TASK
}
