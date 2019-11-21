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

package com.openlattice.authorization.mapstores;

import static com.openlattice.postgres.PostgresColumn.USER_ID;
import static com.openlattice.postgres.PostgresTable.USERS;

import com.auth0.json.mgmt.users.User;
import com.dataloom.mappers.ObjectMappers;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.hazelcast.config.InMemoryFormat;
import com.hazelcast.config.MapConfig;
import com.hazelcast.config.MapIndexConfig;
import com.hazelcast.config.MapStoreConfig;
import com.kryptnostic.rhizome.mapstores.TestableSelfRegisteringMapStore;
import com.openlattice.auth0.Auth0TokenProvider;
import com.openlattice.authentication.Auth0Configuration;
import com.openlattice.client.RetrofitFactory;
import com.openlattice.datastore.services.Auth0ManagementApi;
import com.openlattice.directory.pojo.Auth0UserBasic;
import com.openlattice.hazelcast.HazelcastMap;
import com.openlattice.postgres.mapstores.AbstractBasePostgresMapstore;
import com.zaxxer.hikari.HikariDataSource;
import java.io.IOException;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import jodd.util.RandomString;
import org.apache.commons.lang.RandomStringUtils;
import org.apache.commons.lang3.NotImplementedException;
import org.apache.commons.lang3.RandomUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import retrofit2.Retrofit;

import java.util.Collection;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Implementation of persistence layer for users from auth0.
 *
 * TODO: It reads EXPIRATION unnecessarily since it is not part of the object stored in memory. Minor optimization
 * to not read this,but would require some work on abstract mapstores.
 * @author Matthew Tamayo-Rios &lt;matthew@openlattice.com&gt;
 */
public class UserMapstore extends AbstractBasePostgresMapstore<String, User> {
    private static final Logger       logger = LoggerFactory.getLogger( UserMapstore.class );
    private final        ObjectMapper mapper = ObjectMappers.newJsonMapper();

    public UserMapstore( final HikariDataSource hds ) {
        super( HazelcastMap.USERS.name(), USERS, hds );
    }

    @Override protected int bind( PreparedStatement ps, String key, int offset ) throws SQLException {
        ps.setInt( offset, offset++ );
        return offset;
    }

    @Override public String generateTestKey() {
        return RandomStringUtils.random(10);
    }

    @Override public User generateTestValue() {
        final var user = new User( "conn" );
        user.setAppMetadata( ImmutableMap.of("foo", ImmutableList.of("1","2","3")) );
        user.setClientId( RandomStringUtils.random(8) );
        user.setBlocked( RandomUtils.nextBoolean() );
        user.setEmail( "foobar@openlattice" );
        user.setId( RandomStringUtils.random( 8 ) );
        user.setFamilyName( "bar" );
        user.setGivenName( "foo" );
        user.setName( "Foo bar" );
        user.setVerifyEmail( RandomUtils.nextBoolean() );
        user.setEmailVerified( RandomUtils.nextBoolean() );
        user.setNickname( RandomStringUtils.random( 10 ) );
        user.setPassword( RandomStringUtils.random(8) );
        user.setPicture( RandomStringUtils.random(8) );
        user.setPhoneVerified( RandomUtils.nextBoolean() );
        user.setVerifyPhoneNumber( RandomUtils.nextBoolean() );
        user.setPhoneNumber( RandomStringUtils.random( 8 ) );
        user.setUserMetadata( ImmutableMap.of("bar", ImmutableList.of("4","5","6")) );
        return user;
    }

    @Override public MapConfig getMapConfig() {
        return new MapConfig( getMapName() )
                .setInMemoryFormat( InMemoryFormat.BINARY )
                .setMapStoreConfig( getMapStoreConfig() );
    }

    @Override public MapStoreConfig getMapStoreConfig() {
        return new MapStoreConfig()
                .setImplementation( this )
                .setEnabled( true );

    }

    @Override protected void bind( PreparedStatement ps, String key, User value ) throws SQLException {
        ps.setString( 1, key );

        //TODO: Automate the ON CONFLICT clause.
        try {
            ps.setString( 2, mapper.writeValueAsString( value ) );
            ps.setLong( 3, System.currentTimeMillis() );
        } catch ( JsonProcessingException e ) {
            throw new SQLException( "Unable to serialize to JSONB.", e );
        }
    }

    @Override protected String mapToKey( ResultSet rs ) throws SQLException {
        return rs.getString( USER_ID.getName());
    }

    @Override protected User mapToValue( ResultSet rs ) throws SQLException {
        try {
            return mapper.readValue( rs.getString( USERS.getName()), User.class );
        } catch ( IOException e ) {
            throw new SQLException( "Unable to deserialize from JSONB.", e );
        }
    }

}
