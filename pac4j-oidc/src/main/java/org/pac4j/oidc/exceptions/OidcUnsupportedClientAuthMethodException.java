package org.pac4j.oidc.exceptions;

public class OidcUnsupportedClientAuthMethodException extends
    OidcException {

    public OidcUnsupportedClientAuthMethodException(String message) {
        super(message);
    }

    public OidcUnsupportedClientAuthMethodException(Throwable t) {
        super(t);
    }

    public OidcUnsupportedClientAuthMethodException(String message, Throwable t) {
        super(message, t);
    }
}
