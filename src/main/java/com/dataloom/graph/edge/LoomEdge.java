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

package com.dataloom.graph.edge;

import java.util.UUID;

public class LoomEdge {
    private EdgeKey key;

    private UUID srcType;
    private UUID srcSetId;
    private UUID dstSetId;
    private UUID edgeSetId;

    private UUID srcSyncId;
    private UUID dstSyncId;

    public LoomEdge(
            EdgeKey key,
            UUID srcType,
            UUID srcSetId,
            UUID srcSyncId,
            UUID dstSetId,
            UUID dstSyncId,
            UUID edgeSetId ) {
        this.key = key;
        this.srcSyncId = srcSyncId;
        this.srcType = srcType;
        this.srcSetId = srcSetId;
        this.dstSetId = dstSetId;
        this.dstSyncId = dstSyncId;
        this.edgeSetId = edgeSetId;
    }

    public EdgeKey getKey() {
        return key;
    }

    public UUID getSrcTypeId() {
        return srcType;
    }

    public UUID getSrcSetId() {
        return srcSetId;
    }

    public UUID getSrcSyncId() {
        return srcSyncId;
    }

    public UUID getDstSetId() {
        return dstSetId;
    }

    public UUID getDstSyncId() {
        return dstSyncId;
    }

    public UUID getEdgeSetId() {
        return edgeSetId;
    }

    public UUID getSrcEntityKeyId() {
        return key.getSrcEntityKeyId();
    }

    public UUID getDstEntityKeyId() {
        return key.getDstEntityKeyId();
    }

    public UUID getEdgeEntityKeyId() {
        return key.getEdgeEntityKeyId();
    }

    public UUID getDstTypeId() {
        return key.getDstTypeId();
    }

    public UUID getEdgeTypeId() {
        return key.getEdgeTypeId();
    }

    @Override public String toString() {
        return "LoomEdge{" +
                "key=" + key +
                ", srcType=" + srcType +
                ", srcSetId=" + srcSetId +
                ", dstSetId=" + dstSetId +
                ", edgeSetId=" + edgeSetId +
                ", srcSyncId=" + srcSyncId +
                ", dstSyncId=" + dstSyncId +
                '}';
    }

    @Override public boolean equals( Object o ) {
        if ( this == o ) { return true; }
        if ( !( o instanceof LoomEdge ) ) { return false; }

        LoomEdge loomEdge = (LoomEdge) o;

        if ( !key.equals( loomEdge.key ) ) { return false; }
        if ( !srcType.equals( loomEdge.srcType ) ) { return false; }
        if ( !srcSetId.equals( loomEdge.srcSetId ) ) { return false; }
        if ( !dstSetId.equals( loomEdge.dstSetId ) ) { return false; }
        if ( !edgeSetId.equals( loomEdge.edgeSetId ) ) { return false; }
        if ( !srcSyncId.equals( loomEdge.srcSyncId ) ) { return false; }
        return dstSyncId.equals( loomEdge.dstSyncId );
    }

    @Override public int hashCode() {
        int result = key.hashCode();
        result = 31 * result + srcType.hashCode();
        result = 31 * result + srcSetId.hashCode();
        result = 31 * result + dstSetId.hashCode();
        result = 31 * result + edgeSetId.hashCode();
        result = 31 * result + srcSyncId.hashCode();
        result = 31 * result + dstSyncId.hashCode();
        return result;
    }
}