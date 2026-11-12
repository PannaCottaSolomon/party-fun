package auth.cognito;

import java.util.Set;

import auth.entity.UserAccount;

/** What Cognito knows about a user: its email, whether it was verified, and its role groups. */
public record CognitoUser(String email, boolean emailVerified, Set<UserAccount.Role> roles) {

    public CognitoUser {
        roles = Set.copyOf(roles);
    }
}
