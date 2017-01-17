package com.dataloom.authorization;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

@ResponseStatus(
    value = HttpStatus.FORBIDDEN )
public class ForbiddenException extends RuntimeException {
    private static final long serialVersionUID = 5043278569494339266L;

    public static String      message          = "The object is inaccessible.";

    public ForbiddenException() {
        super( message );
    }

    public ForbiddenException( String message ) {
        super( message );
    }

}
