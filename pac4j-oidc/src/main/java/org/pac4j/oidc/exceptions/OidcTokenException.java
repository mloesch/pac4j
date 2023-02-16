package org.pac4j.oidc.exceptions;

public class OidcTokenException extends OidcException {

    public OidcTokenException(String message) {
        super(message);
    }

    public OidcTokenException(Throwable t) {
        super(t);
    }

    public OidcTokenException(String message, Throwable t) {
        super(message, t);
    }
}
