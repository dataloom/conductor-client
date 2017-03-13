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

package com.dataloom.organizations.roles.exceptions;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

@ResponseStatus( HttpStatus.UNAUTHORIZED )
public class TokenRefreshException extends RuntimeException {
    private static final long serialVersionUID = 7541262856797158909L;

    private static final String DEFAULT_MESSAGE = "JWT Token was issued before the current acceptable token issue time. The token must be retrieved from Auth0 again.";
    
    public TokenRefreshException() {
        super( DEFAULT_MESSAGE );
    }
    
    public TokenRefreshException( String message ) {
        super( message );
    }
}