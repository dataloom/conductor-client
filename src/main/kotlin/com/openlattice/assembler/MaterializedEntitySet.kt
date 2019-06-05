/*
 * Copyright (C) 2019. OpenLattice, Inc.
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
 *
 */
package com.openlattice.assembler

import com.openlattice.organization.OrganizationEntitySetFlag
import java.time.OffsetDateTime
import java.util.EnumSet

data class MaterializedEntitySet(
        val assemblyKey: EntitySetAssemblyKey,
        /**
         * Holds the user set refresh rate in milliseconds.
         * If it's null, that means, that it should NOT be refreshed automatically.
         */
        val refreshRate: Long?,
        val flags: EnumSet<OrganizationEntitySetFlag> = EnumSet.noneOf(OrganizationEntitySetFlag::class.java),
        var lastRefresh: OffsetDateTime = OffsetDateTime.now())