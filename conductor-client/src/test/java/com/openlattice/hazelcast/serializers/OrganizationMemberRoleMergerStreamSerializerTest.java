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

package com.openlattice.hazelcast.serializers;

import com.openlattice.hazelcast.serializers.OrganizationMemberRoleMergerStreamSerializer;
import com.openlattice.mapstores.TestDataFactory;
import com.openlattice.organizations.processors.NestedPrincipalMerger;
import com.google.common.collect.ImmutableSet;
import com.kryptnostic.rhizome.hazelcast.serializers.AbstractStreamSerializerTest;

public class OrganizationMemberRoleMergerStreamSerializerTest extends
        AbstractStreamSerializerTest<OrganizationMemberRoleMergerStreamSerializer, NestedPrincipalMerger> {

    @Override
    protected OrganizationMemberRoleMergerStreamSerializer createSerializer() {
        return new OrganizationMemberRoleMergerStreamSerializer();
    }

    @Override
    protected NestedPrincipalMerger createInput() {
        return new NestedPrincipalMerger( ImmutableSet.of(
                TestDataFactory.aclKey(),
                TestDataFactory.aclKey()
        ) );
    }
}
