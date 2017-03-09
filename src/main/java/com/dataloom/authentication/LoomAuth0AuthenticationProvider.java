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

package com.dataloom.authentication;

import org.joda.time.Instant;
import org.springframework.security.core.Authentication;

import com.auth0.authentication.AuthenticationAPIClient;
import com.auth0.spring.security.api.Auth0JWTToken;
import com.auth0.spring.security.api.Auth0TokenException;
import com.auth0.spring.security.api.Auth0UserDetails;
import com.dataloom.organizations.roles.ExpiringTokenTracker;

import digital.loom.rhizome.authentication.ConfigurableAuth0AuthenticationProvider;

public class LoomAuth0AuthenticationProvider extends ConfigurableAuth0AuthenticationProvider {
    public static final String USER_ID_ATTRIBUTE = "user_id";
    public static final String SUBJECT_ATTRIBUTE = "sub";
    public static final String ISSUE_TIME_ATTRIBUTE = "iat";
    
    private ExpiringTokenTracker tokenTracker;

    public LoomAuth0AuthenticationProvider( AuthenticationAPIClient auth0Client, ExpiringTokenTracker tokenTracker ) {
        super( auth0Client );
        this.tokenTracker = tokenTracker;
    }
    
    @Override
    public Authentication authenticate( Authentication authentication ) {
        final Auth0JWTToken jwtToken = ((Auth0JWTToken) super.authenticate( authentication) );
        Auth0UserDetails details = (Auth0UserDetails) jwtToken.getPrincipal();
        Object userIdAsObj = details.getAuth0Attribute( LoomAuth0AuthenticationProvider.SUBJECT_ATTRIBUTE );
        if ( userIdAsObj == null ) {
            userIdAsObj = details.getAuth0Attribute( LoomAuth0AuthenticationProvider.USER_ID_ATTRIBUTE );
        }
        String userId = userIdAsObj.toString();
        
        Long tokenAcceptTime = tokenTracker.getAcceptanceTime( userId );
        Long tokenIssueTime = Long.parseLong( details.getAuth0Attribute( LoomAuth0AuthenticationProvider.ISSUE_TIME_ATTRIBUTE ).toString() );
        
        if( tokenAcceptTime > tokenIssueTime ){
            jwtToken.setAuthenticated(false);
            //Token is issued before the token acceptance time - should be rejected
            throw new Auth0TokenException("JWT Token was issued before the current acceptable token issue time. The token must be retrieved from Auth0 again.");
        }
        
        //Successful login should remove user from USERS_NEEDING_NEW_TOKEN set
        tokenTracker.untrackUser( userId );
        
        return new LoomAuthentication( jwtToken );
    }
}
