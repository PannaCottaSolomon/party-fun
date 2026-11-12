package auth.cognito;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimValidator;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;

import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.cognitoidentityprovider.CognitoIdentityProviderClient;

/**
 * The Cognito user pool client and the access-token decoder.
 *
 * <p>AWS credentials for the admin calls (confirm sign-up, groups, delete) come from the SDK's default chain:
 * {@code AWS_ACCESS_KEY_ID} / {@code AWS_SECRET_ACCESS_KEY} (/ {@code AWS_SESSION_TOKEN}) locally, the task or
 * pod role in AWS. Nothing is fetched at startup: the JWKS is downloaded on the first token check.
 */
@Configuration(proxyBeanMethods = false)
public class CognitoConfig {

    private static final Logger log = LoggerFactory.getLogger(CognitoConfig.class);

    @Bean(destroyMethod = "close")
    public CognitoIdentityProviderClient cognitoIdentityProviderClient(CognitoProperties properties) {
        return CognitoIdentityProviderClient.builder()
                .region(Region.of(properties.region()))
                .build();
    }

    @Bean
    public JwtDecoder cognitoJwtDecoder(CognitoProperties properties) {
        if (!properties.isConfigured()) {
            log.warn("COGNITO_USER_POOL_ID / COGNITO_CLIENT_ID are not set: every access token will be rejected "
                    + "and /auth/login, POST /user/me fail until they are configured");
        }
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withJwkSetUri(properties.jwksUri()).build();
        decoder.setJwtValidator(accessTokenValidator(properties.issuer(), properties.clientId()));
        return decoder;
    }

    /**
     * What makes a token acceptable, besides its RS256 signature: not expired, issued by this user pool, an
     * <em>access</em> token (an ID token has {@code token_use=id}) and issued to this app client.
     */
    public static OAuth2TokenValidator<Jwt> accessTokenValidator(String issuer, String clientId) {
        return new DelegatingOAuth2TokenValidator<>(
                JwtValidators.createDefaultWithIssuer(issuer),
                new JwtClaimValidator<String>("token_use", "access"::equals),
                new JwtClaimValidator<String>("client_id", clientId::equals));
    }
}
