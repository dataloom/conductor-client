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

package com.openlattice.data;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.openlattice.client.serialization.SerializationConstants;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * @author Matthew Tamayo-Rios &lt;matthew@openlattice.com&gt;
 */
public class PropertyMetadata {
    private final List<Long>     versions;
    private       long           version;
    private       OffsetDateTime lastWrite;

    @JsonCreator
    public PropertyMetadata(
            @JsonProperty( SerializationConstants.VERSION ) long version,
            List<Long> versions,
            OffsetDateTime lastWrite ) {
        this.version = version;
        this.versions = versions;
        this.lastWrite = lastWrite;
    }

    public long getVersion() {
        return version;
    }

    public void setNextVersion( long version ) {
        this.version = version;
        this.versions.add( version );
    }

    public List<Long> getVersions() {
        return versions;
    }

    public OffsetDateTime getLastWrite() {
        return lastWrite;
    }

    public void setLastWrite( OffsetDateTime lastWrite ) {
        this.lastWrite = lastWrite;
    }

    public static PropertyMetadata newPropertyMetadata( OffsetDateTime lastWrite ) {
        return newPropertyMetadata( lastWrite.toInstant().toEpochMilli(), lastWrite );
    }

    public static PropertyMetadata newPropertyMetadata( long version, OffsetDateTime lastWrite ) {
        List<Long> versions = new ArrayList<>( 1 );
        versions.add( version );
        return new PropertyMetadata( version, versions, lastWrite );
    }
}
