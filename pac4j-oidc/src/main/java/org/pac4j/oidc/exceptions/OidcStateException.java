package org.pac4j.oidc.exceptions;

public class OidcStateException extends OidcException {

    public OidcStateException(String message) {
        super(message);
    }

    public OidcStateException(Throwable t) {
        super(t);
    }

    public OidcStateException(String message, Throwable t) {
        super(message, t);
    }
}
