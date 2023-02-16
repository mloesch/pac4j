package org.pac4j.oidc.profile.creator;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.proc.BadJOSEException;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.JWTParser;
import com.nimbusds.oauth2.sdk.ParseException;
import com.nimbusds.oauth2.sdk.token.AccessToken;
import com.nimbusds.oauth2.sdk.token.BearerAccessToken;
import com.nimbusds.openid.connect.sdk.*;
import org.pac4j.core.context.WebContext;
import org.pac4j.core.context.session.SessionStore;
import org.pac4j.core.credentials.Credentials;
import org.pac4j.core.credentials.TokenCredentials;
import org.pac4j.core.profile.ProfileHelper;
import org.pac4j.core.profile.UserProfile;
import org.pac4j.core.profile.creator.ProfileCreator;
import org.pac4j.core.profile.definition.ProfileDefinitionAware;
import org.pac4j.core.profile.jwt.JwtClaims;
import org.pac4j.core.util.Pac4jConstants;
import org.pac4j.oidc.client.OidcClient;
import org.pac4j.oidc.config.OidcConfiguration;
import org.pac4j.oidc.credentials.OidcCredentials;
import org.pac4j.oidc.exceptions.OidcException;
import org.pac4j.oidc.profile.OidcProfile;
import org.pac4j.oidc.profile.OidcProfileDefinition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.Optional;

import static org.pac4j.core.profile.AttributeLocation.PROFILE_ATTRIBUTE;
import static org.pac4j.core.util.CommonHelper.assertNotNull;
import static org.pac4j.core.util.CommonHelper.isNotBlank;

/**
 * OpenID Connect profile creator.
 *
 * @author Jerome Leleu
 * @since 1.9.2
 */
public class OidcProfileCreator extends ProfileDefinitionAware implements ProfileCreator {

    private static final Logger logger = LoggerFactory.getLogger(OidcProfileCreator.class);

    protected OidcConfiguration configuration;

    protected OidcClient client;

    public OidcProfileCreator(final OidcConfiguration configuration, final OidcClient client) {
        this.configuration = configuration;
        this.client = client;
    }

    @Override
    protected void internalInit(final boolean forceReinit) {
        assertNotNull("configuration", configuration);

        defaultProfileDefinition(new OidcProfileDefinition());
    }

    @Override
    @SuppressWarnings("unchecked")
    public Optional<UserProfile> create(final Credentials credentials, final WebContext context, final SessionStore sessionStore) {
        init();

        OidcCredentials oidcCredentials = null;
        AccessToken accessToken = null;

        // regular OIDC flow
        if (credentials instanceof OidcCredentials) {
            oidcCredentials = (OidcCredentials) credentials;
            accessToken = oidcCredentials.getAccessToken();
        } else {
            // we assume the access token only has been passed: it can be a bearer call (HTTP client)
            final var token = ((TokenCredentials) credentials).getToken();
            accessToken = new BearerAccessToken(token);
        }

        // Create profile
        final var profile = (OidcProfile) getProfileDefinition().newProfile();
        profile.setAccessToken(accessToken);

        if (oidcCredentials != null) {
            profile.setIdTokenString(oidcCredentials.getIdToken().getParsedString());
            // Check if there is a refresh token
            final var refreshToken = oidcCredentials.getRefreshToken();
            if (refreshToken != null && !refreshToken.getValue().isEmpty()) {
                profile.setRefreshToken(refreshToken);
                logger.debug("Refresh Token successful retrieved");
            }
        }

        try {

            final Nonce nonce;
            if (configuration.isUseNonce()) {
                nonce = new Nonce((String) sessionStore.get(context, client.getNonceSessionAttributeName()).orElse(null));
            } else {
                nonce = null;
            }
            // Check ID Token
            if (oidcCredentials != null) {
                final var claimsSet = configuration.findTokenValidator().validate(oidcCredentials.getIdToken(), nonce);
                assertNotNull("claimsSet", claimsSet);
                profile.setId(ProfileHelper.sanitizeIdentifier(claimsSet.getSubject()));

                // keep the session ID if provided
                final var sid = (String) claimsSet.getClaim(Pac4jConstants.OIDC_CLAIM_SESSIONID);
                if (isNotBlank(sid)) {
                    configuration.findLogoutHandler().recordSession(context, sessionStore, sid);
                }
            }

            // User Info request
            if (configuration.findProviderMetadata().getUserInfoEndpointURI() != null && accessToken != null) {
                final var userInfoRequest = new UserInfoRequest(configuration.findProviderMetadata().getUserInfoEndpointURI(), accessToken);
                final var userInfoHttpRequest = userInfoRequest.toHTTPRequest();
                configuration.configureHttpRequest(userInfoHttpRequest);
                final var httpResponse = userInfoHttpRequest.send();
                logger.debug("User info response: status={}, content={}", httpResponse.getStatusCode(),
                    httpResponse.getContent());

                final var userInfoResponse = UserInfoResponse.parse(httpResponse);
                if (userInfoResponse instanceof UserInfoErrorResponse) {
                    logger.error("Bad User Info response, error={}",
                        ((UserInfoErrorResponse) userInfoResponse).getErrorObject());
                } else {
                    final var userInfoSuccessResponse = (UserInfoSuccessResponse) userInfoResponse;
                    final JWTClaimsSet userInfoClaimsSet;
                    if (userInfoSuccessResponse.getUserInfo() != null) {
                        userInfoClaimsSet = userInfoSuccessResponse.getUserInfo().toJWTClaimsSet();
                    } else {
                        userInfoClaimsSet = userInfoSuccessResponse.getUserInfoJWT().getJWTClaimsSet();
                    }
                    if (userInfoClaimsSet != null) {
                        getProfileDefinition().convertAndAdd(profile, userInfoClaimsSet.getClaims(), null);
                    } else {
                        logger.warn("Cannot retrieve claims from user info");
                    }
                }
            }

            // add attributes of the ID token if they don't already exist
            if (oidcCredentials != null) {
                for (final var entry : oidcCredentials.getIdToken().getJWTClaimsSet().getClaims().entrySet()) {
                    final var key = entry.getKey();
                    final var value = entry.getValue();
                    // it's not the subject and this attribute does not already exist, add it
                    if (!JwtClaims.SUBJECT.equals(key) && profile.getAttribute(key) == null) {
                        getProfileDefinition().convertAndAdd(profile, PROFILE_ATTRIBUTE, key, value);
                    }
                }
            }

            if (configuration.isIncludeAccessTokenClaimsInProfile()) {
                collectClaimsFromAccessTokenIfAny(oidcCredentials, nonce, profile);
            }

            // session expiration with token behavior
            profile.setTokenExpirationAdvance(configuration.getTokenExpirationAdvance());

            return Optional.of(profile);
        } catch (final IOException | ParseException | JOSEException | BadJOSEException | java.text.ParseException e) {
            throw new OidcException(e);
        }
    }

    private void collectClaimsFromAccessTokenIfAny(final OidcCredentials credentials,
                                                   final Nonce nonce, OidcProfile profile) {
        try {
            final AccessToken accessToken = credentials.getAccessToken();
            if (accessToken != null) {
                var accessTokenJwt = JWTParser.parse(accessToken.getValue());
                var accessTokenClaims = configuration.findTokenValidator().validate(accessTokenJwt, nonce);

                // add attributes of the access token if they don't already exist
                for (final var entry : accessTokenClaims.toJWTClaimsSet().getClaims().entrySet()) {
                    final var key = entry.getKey();
                    final var value = entry.getValue();
                    if (!JwtClaims.SUBJECT.equals(key) && profile.getAttribute(key) == null) {
                        getProfileDefinition().convertAndAdd(profile, PROFILE_ATTRIBUTE, key, value);
                    }
                }
            }
        } catch (final ParseException | java.text.ParseException | JOSEException | BadJOSEException e) {
            logger.debug(e.getMessage(), e);
        } catch (final Exception e) {
            throw new OidcException(e);
        }
    }
}
