package org.pac4j.oidc.exceptions;

public class OidcIssuerMismatchException extends OidcException {

    public OidcIssuerMismatchException(String message) {
        super(message);
    }

    public OidcIssuerMismatchException(Throwable t) {
        super(t);
    }

    public OidcIssuerMismatchException(String message, Throwable t) {
        super(message, t);
    }
}
