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

package com.dataloom.mapstores;

import java.util.Collection;
import java.util.UUID;

import org.junit.Assert;
import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.dataloom.authorization.HzAuthzTest;
import com.dataloom.authorization.securable.AbstractSecurableObject;
import com.kryptnostic.rhizome.mapstores.TestableSelfRegisteringMapStore;

public class MapstoresTest extends HzAuthzTest {
    private static final Logger logger = LoggerFactory
            .getLogger( MapstoresTest.class );
    @SuppressWarnings( "rawtypes" )
    private static final Collection<TestableSelfRegisteringMapStore> mapstores;

    static {
        mapstores = testServer.getContext().getBeansOfType( TestableSelfRegisteringMapStore.class ).values();
    }

    @Test
    public void testMapstore() {
        mapstores.stream().forEach( MapstoresTest::test );
    }

    @SuppressWarnings( { "rawtypes", "unchecked" } )
    private static void test( TestableSelfRegisteringMapStore ms ) {
        Object expected = ms.generateTestValue();
        Object key = ms.generateTestKey();
        if ( AbstractSecurableObject.class.isAssignableFrom( expected.getClass() )
                && UUID.class.equals( key.getClass() ) ) {
            key = ( (AbstractSecurableObject) expected ).getId();
        }
        Object actual = null;
        try {
            ms.store( key, expected );
            actual = ms.load( key );
            if ( !expected.equals( actual ) ) {
                logger.error( "Incorrect r/w to mapstore {} for key {}. expected({}) != actual({})",
                        ms.getMapName(),
                        key,
                        expected,
                        actual );
            }
        } catch ( Exception e ) {
            logger.error( "Unable to r/w to mapstore {} value: ({},{})", ms.getMapName(), key, expected, e );
            throw e;
        }
        Assert.assertEquals( expected, actual );
    }
}
