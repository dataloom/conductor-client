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

package com.dataloom.hazelcast.serializers;

import java.io.Serializable;

import org.apache.olingo.commons.api.edm.FullQualifiedName;

import com.dataloom.hazelcast.serializers.FullQualifiedNameStreamSerializer;
import com.kryptnostic.rhizome.hazelcast.serializers.AbstractStreamSerializerTest;

public class FullQualifiedNameStreamSerializerTest extends AbstractStreamSerializerTest<FullQualifiedNameStreamSerializer, FullQualifiedName>
implements Serializable {
	private static final long serialVersionUID = 6956722858352314361L;

	@Override
	protected FullQualifiedName createInput() {
		return new FullQualifiedName( "foo", "bar" );
	}

	@Override
	protected FullQualifiedNameStreamSerializer createSerializer() {
		return new FullQualifiedNameStreamSerializer();
	}
	
}
