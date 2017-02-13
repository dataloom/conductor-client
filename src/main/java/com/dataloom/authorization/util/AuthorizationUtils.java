/*
 * Copyright (C) 2017. Kryptnostic, Inc (dba Loom)
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
 * You can contact the owner of the copyright at support@thedataloom.com
 */

package com.dataloom.authorization.util;

import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Preconditions.checkState;

import java.util.EnumSet;
import java.util.List;
import java.util.UUID;

import com.auth0.jwt.internal.org.apache.commons.lang3.StringUtils;
import com.dataloom.authorization.AceKey;
import com.dataloom.authorization.Permission;
import com.dataloom.authorization.Principal;
import com.dataloom.authorization.PrincipalType;
import com.dataloom.authorization.securable.SecurableObjectType;
import com.datastax.driver.core.Row;
import com.kryptnostic.conductor.codecs.EnumSetTypeCodec;
import com.kryptnostic.datastore.cassandra.CommonColumns;

public final class AuthorizationUtils {
    private AuthorizationUtils() {}

    public static Principal getPrincipalFromRow( Row row ) {
        final PrincipalType principalType = row.get( CommonColumns.PRINCIPAL_TYPE.cql(), PrincipalType.class );
        final String principalId = row.getString( CommonColumns.PRINCIPAL_ID.cql() );
        checkState( StringUtils.isNotBlank( principalId ), "Principal id cannot be null." );
        checkNotNull( principalType, "Encountered null principal type" );
        return new Principal(
                principalType,
                principalId );
    }

    public static AceKey aceKey( Row row ) {
        Principal principal = getPrincipalFromRow( row );
        return new AceKey( aclKey( row ), principal );
    }

    public static List<UUID> aclKey( Row row ) {
        return row.getList( CommonColumns.ACL_KEYS.cql(), UUID.class );
    }

    public static EnumSet<Permission> permissions( Row row ) {
        return row.get( CommonColumns.PERMISSIONS.cql(), EnumSetTypeCodec.getTypeTokenForEnumSetPermission() );
    }

    public static SecurableObjectType securableObjectType( Row row ) {
        return row.get( CommonColumns.SECURABLE_OBJECT_TYPE.cql(), SecurableObjectType.class );
    }

    public static UUID getLastAclKeySafely( List<UUID> aclKeys ) {
        return aclKeys.isEmpty() ? null : aclKeys.get( aclKeys.size() - 1 );
    }

}
