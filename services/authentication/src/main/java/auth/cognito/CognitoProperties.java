package auth.cognito;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Which Cognito user pool and app client this service uses. The same {@code AWS_REGION} and
 * {@code COGNITO_USER_POOL_ID} as the events service, so both accept the same tokens.
 *
 * <p>The app client must have <b>no client secret</b> and allow {@code ALLOW_USER_PASSWORD_AUTH} and
 * {@code ALLOW_REFRESH_TOKEN_AUTH} (see the README).
 */
@Component
public class CognitoProperties {

    private final String region;
    private final String userPoolId;
    private final String clientId;

    public CognitoProperties(@Value("${auth.cognito.region}") String region,
                             @Value("${auth.cognito.user-pool-id:}") String userPoolId,
                             @Value("${auth.cognito.client-id:}") String clientId) {
        this.region = region.trim();
        this.userPoolId = userPoolId == null ? "" : userPoolId.trim();
        this.clientId = clientId == null ? "" : clientId.trim();
    }

    public String region() { return region; }
    public String userPoolId() { return userPoolId; }
    public String clientId() { return clientId; }

    public boolean isConfigured() {
        return !userPoolId.isEmpty() && !clientId.isEmpty();
    }

    /** The {@code iss} claim of this pool's tokens. */
    public String issuer() {
        return "https://cognito-idp." + region + ".amazonaws.com/" + userPoolId;
    }

    public String jwksUri() {
        return issuer() + "/.well-known/jwks.json";
    }
}
