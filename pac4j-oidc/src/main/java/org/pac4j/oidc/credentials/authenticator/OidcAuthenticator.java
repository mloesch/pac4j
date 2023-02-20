package org.pac4j.oidc.credentials.authenticator;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.oauth2.sdk.*;
import com.nimbusds.oauth2.sdk.auth.*;
import com.nimbusds.oauth2.sdk.id.ClientID;
import com.nimbusds.oauth2.sdk.pkce.CodeVerifier;
import com.nimbusds.openid.connect.sdk.OIDCTokenResponse;
import com.nimbusds.openid.connect.sdk.OIDCTokenResponseParser;
import org.pac4j.core.context.WebContext;
import org.pac4j.core.context.session.SessionStore;
import org.pac4j.core.credentials.Credentials;
import org.pac4j.core.credentials.authenticator.Authenticator;
import org.pac4j.oidc.client.OidcClient;
import org.pac4j.oidc.config.OidcConfiguration;
import org.pac4j.oidc.credentials.OidcCredentials;
import org.pac4j.oidc.exceptions.OidcException;
import org.pac4j.oidc.exceptions.OidcTokenException;
import org.pac4j.oidc.exceptions.OidcUnsupportedClientAuthMethodException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;

import static org.pac4j.core.util.CommonHelper.assertNotNull;
import static org.pac4j.core.util.CommonHelper.isNotEmpty;

/**
 * The OpenID Connect authenticator.
 *
 * @author Jerome Leleu
 * @since 1.9.2
 */
public class OidcAuthenticator implements Authenticator {

    private static final Logger logger = LoggerFactory.getLogger(OidcAuthenticator.class);

    private static final Collection<ClientAuthenticationMethod> SUPPORTED_METHODS =
        Arrays.asList(
            ClientAuthenticationMethod.CLIENT_SECRET_POST,
            ClientAuthenticationMethod.CLIENT_SECRET_BASIC,
            ClientAuthenticationMethod.PRIVATE_KEY_JWT,
            ClientAuthenticationMethod.NONE);

    protected OidcConfiguration configuration;

    protected OidcClient client;

    private ClientAuthentication clientAuthentication;

    public OidcAuthenticator(final OidcConfiguration configuration, final OidcClient client) {
        assertNotNull("configuration", configuration);
        assertNotNull("client", client);
        this.configuration = configuration;
        this.client = client;

        final var _clientID = new ClientID(configuration.getClientId());

        if (configuration.getSecret() != null) {
            // check authentication methods
            final var metadataMethods = configuration.findProviderMetadata()
                .getTokenEndpointAuthMethods();

            final var preferredMethod = getPreferredAuthenticationMethod(configuration);

            final ClientAuthenticationMethod chosenMethod;
            if (isNotEmpty(metadataMethods)) {
                if (preferredMethod != null) {
                    if (metadataMethods.contains(preferredMethod)) {
                        chosenMethod = preferredMethod;
                    } else {
                        throw new OidcUnsupportedClientAuthMethodException(
                            "Preferred authentication method (" + preferredMethod + ") not supported "
                                + "by provider according to provider metadata (" + metadataMethods + ").");
                    }
                } else {
                    chosenMethod = firstSupportedMethod(metadataMethods);
                }
            } else {
                chosenMethod = preferredMethod != null ? preferredMethod : ClientAuthenticationMethod.getDefault();
                logger.info("Provider metadata does not provide Token endpoint authentication methods. Using: {}",
                    chosenMethod);
            }

            if (ClientAuthenticationMethod.CLIENT_SECRET_POST.equals(chosenMethod)) {
                final var _secret = new Secret(configuration.getSecret());
                clientAuthentication = new ClientSecretPost(_clientID, _secret);
            } else if (ClientAuthenticationMethod.CLIENT_SECRET_BASIC.equals(chosenMethod)) {
                final var _secret = new Secret(configuration.getSecret());
                clientAuthentication = new ClientSecretBasic(_clientID, _secret);
            } else if (ClientAuthenticationMethod.PRIVATE_KEY_JWT.equals(chosenMethod)) {
                final var privateKetJwtConfig = configuration.getPrivateKeyJWTClientAuthnMethodConfig();
                assertNotNull("privateKetJwtConfig", privateKetJwtConfig);
                final var jwsAlgo = privateKetJwtConfig.getJwsAlgorithm();
                assertNotNull("privateKetJwtConfig.getJwsAlgorithm()", jwsAlgo);
                final var privateKey = privateKetJwtConfig.getPrivateKey();
                assertNotNull("privateKetJwtConfig.getPrivateKey()", privateKey);
                final var keyID = privateKetJwtConfig.getKeyID();
                try {
                    clientAuthentication = new PrivateKeyJWT(_clientID, configuration.findProviderMetadata().getTokenEndpointURI(),
                        jwsAlgo, privateKey, keyID, null);
                } catch (final JOSEException e) {
                    throw new OidcException("Cannot instantiate private key JWT client authentication method", e);
                }
            } else {
                throw new OidcUnsupportedClientAuthMethodException("Unsupported client authentication method: " + chosenMethod);
            }
        }
    }

    /**
     * The preferred {@link ClientAuthenticationMethod} specified in the given
     * {@link OidcConfiguration}, or <code>null</code> meaning that the a
     * provider-supported method should be chosen.
     */
    private static ClientAuthenticationMethod getPreferredAuthenticationMethod(OidcConfiguration config) {
        final var configurationMethod = config.getClientAuthenticationMethod();
        if (configurationMethod == null) {
            return null;
        }

        if (!SUPPORTED_METHODS.contains(configurationMethod)) {
            throw new OidcUnsupportedClientAuthMethodException("Configured authentication method (" + configurationMethod + ") is not supported.");
        }

        return configurationMethod;
    }

    /**
     * The first {@link ClientAuthenticationMethod} from the given list of
     * methods that is supported by this implementation.
     *
     * @throws OidcUnsupportedClientAuthMethodException if none of the provider-supported methods is supported.
     */
    private static ClientAuthenticationMethod firstSupportedMethod(final List<ClientAuthenticationMethod> metadataMethods) {
        var firstSupported =
            metadataMethods.stream().filter(SUPPORTED_METHODS::contains).findFirst();
        if (firstSupported.isPresent()) {
            return firstSupported.get();
        } else {
            throw new OidcUnsupportedClientAuthMethodException("None of the Token endpoint provider metadata authentication methods are supported: " +
                metadataMethods);
        }
    }

    @Override
    public void validate(final Credentials cred, final WebContext context, final SessionStore sessionStore) {
        final var credentials = (OidcCredentials) cred;
        final var code = credentials.getCode();
        // if we have a code
        if (code != null) {
            try {
                final var computedCallbackUrl = client.computeFinalCallbackUrl(context);
                var verifier = (CodeVerifier) configuration.getValueRetriever()
                    .retrieve(client.getCodeVerifierSessionAttributeName(), client, context, sessionStore).orElse(null);
                // Token request
                final var request = createTokenRequest(new AuthorizationCodeGrant(code, new URI(computedCallbackUrl), verifier));
                executeTokenRequest(request, credentials);
            } catch (final URISyntaxException | IOException | ParseException e) {
                throw new OidcException(e);
            }
        }
    }

    public void refresh(final OidcCredentials credentials) {
        final var refreshToken = credentials.getRefreshToken();
        if (refreshToken != null) {
            try {
                final var request = createTokenRequest(new RefreshTokenGrant(refreshToken));
                executeTokenRequest(request, credentials);
            } catch (final IOException | ParseException e) {
                throw new OidcException(e);
            }
        }
    }

    protected TokenRequest createTokenRequest(final AuthorizationGrant grant) {
        if (clientAuthentication != null) {
            return new TokenRequest(configuration.findProviderMetadata().getTokenEndpointURI(),
                this.clientAuthentication, grant);
        } else {
            return new TokenRequest(configuration.findProviderMetadata().getTokenEndpointURI(),
                new ClientID(configuration.getClientId()), grant);
        }
    }

    private void executeTokenRequest(TokenRequest request, OidcCredentials credentials) throws IOException, ParseException {
        var tokenHttpRequest = request.toHTTPRequest();
        configuration.configureHttpRequest(tokenHttpRequest);

        final var httpResponse = tokenHttpRequest.send();
        logger.debug("Token response: status={}, content={}", httpResponse.getStatusCode(),
            httpResponse.getContent());

        final var response = OIDCTokenResponseParser.parse(httpResponse);
        if (response instanceof TokenErrorResponse) {
            final var errorObject = ((TokenErrorResponse) response).getErrorObject();
            throw new OidcTokenException("Bad token response, error=" + errorObject.getCode() + "," +
                " description=" + errorObject.getDescription());
        }
        logger.debug("Token response successful");
        final var tokenSuccessResponse = (OIDCTokenResponse) response;

        final var oidcTokens = tokenSuccessResponse.getOIDCTokens();
        credentials.setAccessToken(oidcTokens.getAccessToken());
        credentials.setRefreshToken(oidcTokens.getRefreshToken());
        if (oidcTokens.getIDToken() != null) {
            credentials.setIdToken(oidcTokens.getIDToken());
        }
    }

    public ClientAuthentication getClientAuthentication() {
        return clientAuthentication;
    }

    public void setClientAuthentication(final ClientAuthentication clientAuthentication) {
        this.clientAuthentication = clientAuthentication;
    }
}
