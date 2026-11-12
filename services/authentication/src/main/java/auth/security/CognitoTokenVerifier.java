package auth.security;

import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.stereotype.Component;

import auth.entity.UserAccount;
import auth.exception.InvalidTokenException;

/**
 * Checks a Cognito access token (signature against the pool's JWKS, expiry, issuer, {@code token_use=access},
 * {@code client_id}; see {@code CognitoConfig}) and turns it into the caller. Roles come from
 * {@code cognito:groups} the same way the events service reads them; groups that are not a role are ignored.
 */
@Component
public class CognitoTokenVerifier {

    private static final String GROUPS_CLAIM = "cognito:groups";
    /** Every Cognito access token carries the user's Cognito username here. */
    private static final String USERNAME_CLAIM = "username";

    private final JwtDecoder decoder;

    public CognitoTokenVerifier(JwtDecoder decoder) {
        this.decoder = decoder;
    }

    /** @throws InvalidTokenException if the token is malformed, forged, expired or not this pool's access token */
    public AuthenticatedUser verify(String token) {
        Jwt jwt;
        try {
            jwt = decoder.decode(token);
        } catch (JwtException e) {
            throw new InvalidTokenException("Invalid or expired access token");
        }
        String subject = jwt.getSubject();
        if (subject == null) {
            throw new InvalidTokenException("Access token has no valid subject");
        }
        UUID id;
        try {
            id = UUID.fromString(subject);
        } catch (IllegalArgumentException e) {
            throw new InvalidTokenException("Access token has no valid subject");
        }
        String username = jwt.getClaimAsString(USERNAME_CLAIM);
        if (username == null || username.isBlank()) {
            throw new InvalidTokenException("Access token has no username");
        }
        return new AuthenticatedUser(id, username, roles(jwt.getClaimAsStringList(GROUPS_CLAIM)));
    }

    private static Set<UserAccount.Role> roles(List<String> groups) {
        Set<UserAccount.Role> roles = EnumSet.noneOf(UserAccount.Role.class);
        if (groups != null) {
            for (String group : groups) {
                try {
                    roles.add(UserAccount.Role.valueOf(group.toUpperCase(Locale.ROOT)));
                } catch (IllegalArgumentException ignored) {
                    // A Cognito group that is not one of our roles grants nothing.
                }
            }
        }
        return roles;
    }
}
