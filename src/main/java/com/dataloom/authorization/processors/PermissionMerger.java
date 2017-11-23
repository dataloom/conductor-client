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

package com.dataloom.authorization.processors;

import static com.google.common.base.Preconditions.checkNotNull;

import com.dataloom.authorization.AceKey;
import com.dataloom.authorization.Permission;
import com.dataloom.authorization.securable.SecurableObjectType;
import com.kryptnostic.rhizome.hazelcast.processors.AbstractMerger;
import com.openlattice.authorization.AceValue;
import java.util.EnumSet;

public class PermissionMerger extends AbstractMerger<AceKey, AceValue, Permission> {
    private static final long serialVersionUID = -3504613417625318717L;
    private final SecurableObjectType securableObjectType;

    public  PermissionMerger(
            Iterable<Permission> objects,
            SecurableObjectType securableObjectType ) {
        super( objects );
        this.securableObjectType = checkNotNull( securableObjectType );
    }

    @Override
    protected AceValue newEmptyCollection() {
        return new AceValue( EnumSet.noneOf( Permission.class ), securableObjectType );
    }

    public SecurableObjectType getSecurableObjectType() {
        return securableObjectType;
    }
}
