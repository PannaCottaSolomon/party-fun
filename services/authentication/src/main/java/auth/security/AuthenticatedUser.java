package auth.security;

import java.util.Set;
import java.util.UUID;

import auth.entity.UserAccount;

/**
 * The caller of a protected request, taken from a verified Cognito access token: its {@code sub} (the account id),
 * its Cognito {@code username} (what Cognito's admin calls need) and the roles of its {@code cognito:groups}. Set as a
 * request attribute by {@link JwtAuthInterceptor}; controllers read it with
 * {@code @RequestAttribute(AuthenticatedUser.ATTRIBUTE)}.
 */
public record AuthenticatedUser(UUID id, String username, Set<UserAccount.Role> roles) {

    public static final String ATTRIBUTE = "auth.authenticatedUser";

    public AuthenticatedUser {
        roles = Set.copyOf(roles);
    }

    public boolean isAdmin() {
        return roles.contains(UserAccount.Role.ADMIN);
    }

    public boolean isOwnerOf(UUID accountId) {
        return id.equals(accountId);
    }
}
