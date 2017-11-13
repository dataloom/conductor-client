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

package com.openlattice.postgres;

import static com.openlattice.postgres.PostgresDatatype.BOOLEAN;
import static com.openlattice.postgres.PostgresDatatype.DECIMAL;
import static com.openlattice.postgres.PostgresDatatype.TEXT;
import static com.openlattice.postgres.PostgresDatatype.TEXT_ARRAY;
import static com.openlattice.postgres.PostgresDatatype.UUID;
import static com.openlattice.postgres.PostgresDatatype.UUID_ARRAY;

import com.dataloom.edm.type.Analyzer;

import static com.openlattice.postgres.PostgresDatatype.*;

/**
 * @author Matthew Tamayo-Rios &lt;matthew@openlattice.com&gt;
 */
public final class PostgresColumn {
    public static final String ACL_KEY_FIELD               = "acl_key";
    public static final String ALLOWED_EMAIL_DOMAINS_FIELD = "allowed_email_domains";
    public static final String ANALYZER_FIELD              = "analyzer";
    public static final String APP_ID_FIELD                = "app_id";
    public static final String AUDIT_ID_FIELD              = "audit_id";
    public static final String BASE_TYPE_FIELD             = "base_type";
    public static final String BIDIRECTIONAL_FIELD         = "bidirectional";
    public static final String BLOCK_ID_FIELD              = "block_id";
    public static final String CATEGORY_FIELD              = "category";
    public static final String CONFIG_TYPE_ID_FIELD        = "config_type_id";
    public static final String CONFIG_TYPE_IDS_FIELD       = "config_type_ids";
    public static final String CONTACTS_FIELD              = "contacts";
    public static final String CURRENT_SYNC_ID_FIELD       = "current_sync_id";
    public static final String DATA_ID_FIELD               = "data_id";
    public static final String DATATYPE_FIELD              = "datatype";
    public static final String DESCRIPTION_FIELD           = "description";
    public static final String DST_FIELD                   = "dst";
    public static final String DST_LINKING_VERTEX_ID_FIELD = "dst_linking_vertex_id";
    public static final String EDGE_VALUE_FIELD            = "edge_value";
    public static final String EDM_VERSION_FIELD           = "edm_version";
    public static final String EDM_VERSION_NAME_FIELD      = "edm_version_name";
    public static final String ENTITY_KEY_IDS_FIELD        = "entity_key_ids";
    public static final String ENTITY_SET_ID_FIELD         = "entity_set_id";
    public static final String ENTITY_SET_IDS_FIELD        = "entity_set_ids";
    public static final String ENTITY_TYPE_ID_FIELD        = "entity_type_id";
    public static final String EVENT_TYPE_FIELD            = "event_type";
    public static final String FLAGS_FIELD                 = "flags";
    public static final String GRAPH_DIAMETER_FIELD        = "graph_diameter";
    public static final String GRAPH_ID_FIELD              = "graph_id";
    public static final String ID_FIELD                    = "id";
    public static final String KEY_FIELD                   = "key";
    public static final String MEMBERS_FIELD               = "members";
    public static final String NAME_FIELD                  = "name";
    public static final String NAME_SET_FIELD              = "name_set";
    public static final String NAMESPACE_FIELD             = "namespace";
    public static final String NEW_VERTEX_ID_FIELD         = "new_vertex_id";
    public static final String NULLABLE_TITLE_FIELD        = "title";
    public static final String ORGANIZATION_ID_FIELD       = "organization_id";
    public static final String PII_FIELD                   = "pii";
    public static final String PERMISSIONS_FIELD           = "permissions";
    public static final String PRINCIPAL_TYPE_FIELD        = "principal_type";
    public static final String PRINCIPAL_ID_FIELD          = "principal_id";
    public static final String PRINCIPAL_IDS_FIELD         = "principal_ids";
    public static final String PROPERTIES_FIELD            = "properties";
    public static final String PROPERTY_TYPE_ID_FIELD      = "property_type_id";
    public static final String REASON_FIELD                = "reason";
    public static final String ROLE_ID_FIELD               = "role_id";
    public static final String SCHEMAS_FIELD               = "schemas";
    public static final String SECURABLE_OBJECT_TYPE_FIELD = "securable_object_type";
    public static final String SECURABLE_OBJECTID_FIELD    = "securable_objectid";
    public static final String SHOW_FIELD                  = "show";
    public static final String SRC_FIELD                   = "src";
    public static final String SRC_LINKING_VERTEX_ID_FIELD = "src_linking_vertex_id";
    public static final String STATUS_FIELD                = "status";
    public static final String SYNC_ID_FIELD               = "sync_id";
    public static final String TIME_UUID_FIELD             = "time_uuid";
    public static final String TITLE_FIELD                 = "title";
    public static final String VERTEX_ID_FIELD             = "vertex_id";

    public static final PostgresColumnDefinition APP_ID                =
            new PostgresColumnDefinition( APP_ID_FIELD, UUID );
    public static final PostgresColumnDefinition ACL_KEY               =
            new PostgresColumnDefinition( ACL_KEY_FIELD, UUID_ARRAY );
    public static final PostgresColumnDefinition ALLOWED_EMAIL_DOMAINS =
            new PostgresColumnDefinition( ALLOWED_EMAIL_DOMAINS_FIELD, TEXT_ARRAY );
    public static final PostgresColumnDefinition ANALYZER              =
            new PostgresColumnDefinition( ANALYZER_FIELD, TEXT )
                    .withDefault( "'" + Analyzer.STANDARD.name() + "'" )
                    .notNull();
    public static final PostgresColumnDefinition AUDIT_ID              =
            new PostgresColumnDefinition( AUDIT_ID_FIELD, UUID );
    public static final PostgresColumnDefinition BASE_TYPE             =
            new PostgresColumnDefinition( BASE_TYPE_FIELD, UUID );
    public static final PostgresColumnDefinition BIDIRECTIONAL         =
            new PostgresColumnDefinition( BIDIRECTIONAL_FIELD, BOOLEAN );
    public static final PostgresColumnDefinition BLOCK_ID              =
            new PostgresColumnDefinition( BLOCK_ID_FIELD, UUID );
    public static final PostgresColumnDefinition CATEGORY              =
            new PostgresColumnDefinition( CATEGORY_FIELD, TEXT ).notNull();
    public static final PostgresColumnDefinition CONFIG_TYPE_ID        =
            new PostgresColumnDefinition( CONFIG_TYPE_ID_FIELD, UUID );
    public static final PostgresColumnDefinition CONFIG_TYPE_IDS       =
            new PostgresColumnDefinition( CONFIG_TYPE_IDS_FIELD, UUID_ARRAY );
    public static final PostgresColumnDefinition CONTACTS              =
            new PostgresColumnDefinition( CONTACTS_FIELD, TEXT_ARRAY );
    public static final PostgresColumnDefinition CURRENT_SYNC_ID       =
            new PostgresColumnDefinition( CURRENT_SYNC_ID_FIELD, UUID );
    public static final PostgresColumnDefinition DATA_ID               =
            new PostgresColumnDefinition( DATA_ID_FIELD, UUID );
    public static final PostgresColumnDefinition DATATYPE              =
            new PostgresColumnDefinition( DATATYPE_FIELD, TEXT ).notNull();
    public static final PostgresColumnDefinition DESCRIPTION           =
            new PostgresColumnDefinition( DESCRIPTION_FIELD, TEXT );
    public static final PostgresColumnDefinition DST                   =
            new PostgresColumnDefinition( DST_FIELD, UUID_ARRAY );
    public static final PostgresColumnDefinition DST_LINKING_VERTEX_ID =
            new PostgresColumnDefinition( DST_LINKING_VERTEX_ID_FIELD, UUID );
    public static final PostgresColumnDefinition EDGE_VALUE            =
            new PostgresColumnDefinition( EDGE_VALUE_FIELD, DECIMAL );
    public static final PostgresColumnDefinition EDM_VERSION           =
            new PostgresColumnDefinition( EDM_VERSION_FIELD, UUID ).notNull();
    public static final PostgresColumnDefinition EDM_VERSION_NAME      =
            new PostgresColumnDefinition( EDM_VERSION_NAME_FIELD, TEXT ).notNull();
    public static final PostgresColumnDefinition ENTITY_KEY_IDS        =
            new PostgresColumnDefinition( ENTITY_KEY_IDS_FIELD, UUID_ARRAY );
    public static final PostgresColumnDefinition ENTITY_SET_ID         =
            new PostgresColumnDefinition( ENTITY_SET_ID_FIELD, UUID ).notNull();
    public static final PostgresColumnDefinition ENTITY_SET_IDS        =
            new PostgresColumnDefinition( ENTITY_SET_IDS_FIELD, UUID_ARRAY ).notNull();
    public static final PostgresColumnDefinition ENTITY_TYPE_ID        =
            new PostgresColumnDefinition( ENTITY_TYPE_ID_FIELD, UUID ).notNull();
    public static final PostgresColumnDefinition EVENT_TYPE            =
            new PostgresColumnDefinition( EVENT_TYPE_FIELD, TEXT );
    public static final PostgresColumnDefinition FLAGS                 =
            new PostgresColumnDefinition( FLAGS_FIELD, BOOLEAN ).notNull();
    public static final PostgresColumnDefinition GRAPH_DIAMETER        =
            new PostgresColumnDefinition( GRAPH_DIAMETER_FIELD, DECIMAL );
    public static final PostgresColumnDefinition GRAPH_ID              =
            new PostgresColumnDefinition( GRAPH_ID_FIELD, UUID );
    public static final PostgresColumnDefinition ID                    =
            new PostgresColumnDefinition( ID_FIELD, UUID ).primaryKey();
    public static final PostgresColumnDefinition ID_VALUE              =
            new PostgresColumnDefinition( ID_FIELD, UUID );
    public static final PostgresColumnDefinition KEY                   =
            new PostgresColumnDefinition( KEY_FIELD, UUID_ARRAY ).notNull();
    public static final PostgresColumnDefinition MEMBERS               =
            new PostgresColumnDefinition( MEMBERS_FIELD, TEXT_ARRAY );
    public static final PostgresColumnDefinition NAME                  =
            new PostgresColumnDefinition( NAME_FIELD, TEXT ).notNull();
    public static final PostgresColumnDefinition NAME_SET              =
            new PostgresColumnDefinition( NAME_SET_FIELD, TEXT_ARRAY ).notNull();
    public static final PostgresColumnDefinition NAMESPACE             =
            new PostgresColumnDefinition( NAMESPACE_FIELD, TEXT ).notNull();
    public static final PostgresColumnDefinition NEW_VERTEX_ID         =
            new PostgresColumnDefinition( NEW_VERTEX_ID_FIELD, UUID ).notNull();
    public static final PostgresColumnDefinition NULLABLE_TITLE        =
            new PostgresColumnDefinition( NULLABLE_TITLE_FIELD, TEXT );
    public static final PostgresColumnDefinition ORGANIZATION_ID       =
            new PostgresColumnDefinition( ORGANIZATION_ID_FIELD, UUID ).notNull();
    public static final PostgresColumnDefinition PII                   =
            new PostgresColumnDefinition( PII_FIELD, BOOLEAN )
                    .withDefault( false )
                    .notNull();
    public static final PostgresColumnDefinition PERMISSIONS           =
            new PostgresColumnDefinition( PERMISSIONS_FIELD, TEXT_ARRAY );
    public static final PostgresColumnDefinition PRINCIPAL_TYPE        =
            new PostgresColumnDefinition( PRINCIPAL_TYPE_FIELD, TEXT );
    public static final PostgresColumnDefinition PRINCIPAL_ID          =
            new PostgresColumnDefinition( PRINCIPAL_ID_FIELD, TEXT );
    public static final PostgresColumnDefinition PRINCIPAL_IDS         =
            new PostgresColumnDefinition( PRINCIPAL_IDS_FIELD, TEXT_ARRAY );
    public static final PostgresColumnDefinition PROPERTIES            =
            new PostgresColumnDefinition( PROPERTIES_FIELD, UUID_ARRAY ).notNull();
    public static final PostgresColumnDefinition PROPERTY_TYPE_ID      =
            new PostgresColumnDefinition( PROPERTY_TYPE_ID_FIELD, UUID ).notNull();
    public static final PostgresColumnDefinition REASON                =
            new PostgresColumnDefinition( REASON_FIELD, TEXT );
    public static final PostgresColumnDefinition ROLE_ID               =
            new PostgresColumnDefinition( ROLE_ID_FIELD, UUID ).notNull();
    public static final PostgresColumnDefinition SCHEMAS               =
            new PostgresColumnDefinition( SCHEMAS_FIELD, TEXT_ARRAY ).notNull();
    public static final PostgresColumnDefinition SECURABLE_OBJECT_TYPE =
            new PostgresColumnDefinition( SECURABLE_OBJECT_TYPE_FIELD, TEXT ).notNull();
    public static final PostgresColumnDefinition SECURABLE_OBJECTID    =
            new PostgresColumnDefinition( SECURABLE_OBJECTID_FIELD, UUID ).notNull();
    public static final PostgresColumnDefinition SHOW                  =
            new PostgresColumnDefinition( SHOW_FIELD, BOOLEAN ).notNull();
    public static final PostgresColumnDefinition SRC                   =
            new PostgresColumnDefinition( SRC_FIELD, UUID_ARRAY );
    public static final PostgresColumnDefinition SRC_LINKING_VERTEX_ID =
            new PostgresColumnDefinition( SRC_LINKING_VERTEX_ID_FIELD, UUID );
    public static final PostgresColumnDefinition STATUS                =
            new PostgresColumnDefinition( STATUS_FIELD, TEXT ).notNull();
    public static final PostgresColumnDefinition SYNC_ID               =
            new PostgresColumnDefinition( SYNC_ID_FIELD, UUID ).notNull();
    public static final PostgresColumnDefinition TIME_UUID             =
            new PostgresColumnDefinition( TIME_UUID_FIELD, UUID );
    public static final PostgresColumnDefinition TITLE                 =
            new PostgresColumnDefinition( TITLE_FIELD, TEXT ).notNull();
    public static final PostgresColumnDefinition VERTEX_ID             =
            new PostgresColumnDefinition( VERTEX_ID_FIELD, UUID );

    private PostgresColumn() {
    }

}
