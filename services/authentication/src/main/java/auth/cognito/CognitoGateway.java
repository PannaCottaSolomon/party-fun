package auth.cognito;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import auth.entity.UserAccount;
import auth.exception.ConflictException;
import auth.exception.IdentityProviderException;
import auth.exception.InvalidCredentialsException;
import auth.exception.InvalidTokenException;
import software.amazon.awssdk.awscore.exception.AwsServiceException;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.services.cognitoidentityprovider.CognitoIdentityProviderClient;
import software.amazon.awssdk.services.cognitoidentityprovider.model.AdminAddUserToGroupRequest;
import software.amazon.awssdk.services.cognitoidentityprovider.model.AdminConfirmSignUpRequest;
import software.amazon.awssdk.services.cognitoidentityprovider.model.AdminCreateUserRequest;
import software.amazon.awssdk.services.cognitoidentityprovider.model.AdminDeleteUserRequest;
import software.amazon.awssdk.services.cognitoidentityprovider.model.AdminGetUserRequest;
import software.amazon.awssdk.services.cognitoidentityprovider.model.AdminListGroupsForUserRequest;
import software.amazon.awssdk.services.cognitoidentityprovider.model.AdminSetUserPasswordRequest;
import software.amazon.awssdk.services.cognitoidentityprovider.model.AdminUpdateUserAttributesRequest;
import software.amazon.awssdk.services.cognitoidentityprovider.model.AliasExistsException;
import software.amazon.awssdk.services.cognitoidentityprovider.model.AttributeType;
import software.amazon.awssdk.services.cognitoidentityprovider.model.AuthFlowType;
import software.amazon.awssdk.services.cognitoidentityprovider.model.AuthenticationResultType;
import software.amazon.awssdk.services.cognitoidentityprovider.model.CreateGroupRequest;
import software.amazon.awssdk.services.cognitoidentityprovider.model.GroupType;
import software.amazon.awssdk.services.cognitoidentityprovider.model.InitiateAuthRequest;
import software.amazon.awssdk.services.cognitoidentityprovider.model.InitiateAuthResponse;
import software.amazon.awssdk.services.cognitoidentityprovider.model.InvalidParameterException;
import software.amazon.awssdk.services.cognitoidentityprovider.model.ListGroupsRequest;
import software.amazon.awssdk.services.cognitoidentityprovider.model.ListGroupsResponse;
import software.amazon.awssdk.services.cognitoidentityprovider.model.ListUsersRequest;
import software.amazon.awssdk.services.cognitoidentityprovider.model.MessageActionType;
import software.amazon.awssdk.services.cognitoidentityprovider.model.NotAuthorizedException;
import software.amazon.awssdk.services.cognitoidentityprovider.model.PasswordResetRequiredException;
import software.amazon.awssdk.services.cognitoidentityprovider.model.RevokeTokenRequest;
import software.amazon.awssdk.services.cognitoidentityprovider.model.UnauthorizedException;
import software.amazon.awssdk.services.cognitoidentityprovider.model.UnsupportedTokenTypeException;
import software.amazon.awssdk.services.cognitoidentityprovider.model.UserNotConfirmedException;
import software.amazon.awssdk.services.cognitoidentityprovider.model.UserNotFoundException;

/**
 * Every call this service makes to the Cognito user pool, with Cognito's errors translated into this service's
 * exceptions (401 / 503). Admin calls address a user by its Cognito <em>username</em> (the access token's
 * {@code username} claim), never by its {@code sub}: a pool that signs in with usernames answers "User does not exist"
 * to a {@code sub}.
 *
 * <p>Never logs passwords or tokens.
 */
@Component
public class CognitoGateway {

    private static final Logger log = LoggerFactory.getLogger(CognitoGateway.class);

    private final CognitoIdentityProviderClient cognito;
    private final CognitoProperties properties;

    /** Role → the group's actual name in the pool, read once from Cognito (null until then). */
    private volatile Map<UserAccount.Role, String> groupNames;

    public CognitoGateway(CognitoIdentityProviderClient cognito, CognitoProperties properties) {
        this.cognito = cognito;
        this.properties = properties;
    }

    public boolean isConfigured() {
        return properties.isConfigured();
    }

    /**
     * The Cognito user behind a verified token: its email, whether the email was verified (the frontend's sign-up
     * confirms it with the emailed code) and which role groups it is in now. Read from Cognito, not from the token,
     * so a group added since the token was issued is seen.
     *
     * @param username the Cognito username, from the verified access token
     * @throws InvalidTokenException if the user no longer exists in Cognito
     */
    public CognitoUser lookup(String username) {
        requireConfigured();
        List<AttributeType> attributes;
        try {
            attributes = cognito.adminGetUser(AdminGetUserRequest.builder()
                    .userPoolId(properties.userPoolId())
                    .username(username)
                    .build()).userAttributes();
        } catch (UserNotFoundException e) {
            throw new InvalidTokenException("This account no longer exists");
        } catch (SdkException e) {
            throw failure("user lookup", e);
        }
        Set<UserAccount.Role> roles = EnumSet.noneOf(UserAccount.Role.class);
        try {
            cognito.adminListGroupsForUser(AdminListGroupsForUserRequest.builder()
                    .userPoolId(properties.userPoolId())
                    .username(username)
                    .build()).groups().forEach(group -> {
                        for (UserAccount.Role role : UserAccount.Role.values()) {
                            if (role.groupName().equalsIgnoreCase(group.groupName())) {
                                roles.add(role);
                            }
                        }
                    });
        } catch (SdkException e) {
            throw failure("group lookup", e);
        }
        return new CognitoUser(attribute(attributes, "email"), "true".equals(attribute(attributes, "email_verified")),
                roles);
    }

    /**
     * Puts the user (by Cognito username) into the role's group. Adding a group the user is already in is a no-op in
     * Cognito.
     */
    public void addUserToGroup(String username, UserAccount.Role role) {
        requireConfigured();
        try {
            addToGroup(username, role);
        } catch (SdkException e) {
            throw failure("group membership", e);
        }
    }

    /**
     * Confirms a new, unconfirmed account and marks its email verified, without the emailed code. Only used while
     * email verification is switched off. An account that is already confirmed is just marked verified.
     *
     * <p>Checked first: if another account already has this email verified, nothing is changed. (Cognito would
     * otherwise move the verified email to the new account and take it away from the existing one.)
     *
     * @throws auth.exception.UserNotFoundException if there is no such user (404)
     * @throws ConflictException         if another account already has this email verified (409)
     */
    public void confirmWithoutCode(String username) {
        requireConfigured();
        String email;
        try {
            email = attribute(cognito.adminGetUser(AdminGetUserRequest.builder()
                    .userPoolId(properties.userPoolId())
                    .username(username)
                    .build()).userAttributes(), "email");
        } catch (UserNotFoundException e) {
            throw new auth.exception.UserNotFoundException("No account with username " + username);
        } catch (SdkException e) {
            throw failure("user lookup", e);
        }
        if (email != null && verifiedElsewhere(email, username)) {
            throw new ConflictException("Another account already uses this email address");
        }
        try {
            cognito.adminConfirmSignUp(AdminConfirmSignUpRequest.builder()
                    .userPoolId(properties.userPoolId())
                    .username(username)
                    .build());
        } catch (UserNotFoundException e) {
            throw new auth.exception.UserNotFoundException("No account with username " + username);
        } catch (NotAuthorizedException e) {
            // "User cannot be confirmed. Current status is CONFIRMED": nothing to do but the email flag below.
            log.info("Cognito user {} was already confirmed", username);
        } catch (SdkException e) {
            throw failure("sign-up confirmation", e);
        }
        try {
            cognito.adminUpdateUserAttributes(AdminUpdateUserAttributesRequest.builder()
                    .userPoolId(properties.userPoolId())
                    .username(username)
                    .userAttributes(newAttribute("email_verified", "true"))
                    .build());
        } catch (AliasExistsException e) {
            throw new ConflictException("Another account already uses this email address");
        } catch (SdkException e) {
            throw failure("email verification", e);
        }
    }

    /** Whether a user other than {@code username} has {@code email} as its verified email. */
    private boolean verifiedElsewhere(String email, String username) {
        String quoted = email.replace("\\", "\\\\").replace("\"", "\\\"");
        try {
            return cognito.listUsers(ListUsersRequest.builder()
                            .userPoolId(properties.userPoolId())
                            .filter("email = \"" + quoted + "\"")
                            .build())
                    .users().stream()
                    .filter(user -> !user.username().equalsIgnoreCase(username))
                    .anyMatch(user -> "true".equals(attribute(user.attributes(), "email_verified")));
        } catch (SdkException e) {
            throw failure("email check", e);
        }
    }

    /** Email + password login. A wrong password and an unknown email look the same to the caller (401). */
    public CognitoTokens login(String email, String password) {
        requireConfigured();
        InitiateAuthResponse response;
        try {
            response = cognito.initiateAuth(InitiateAuthRequest.builder()
                    .clientId(properties.clientId())
                    .authFlow(AuthFlowType.USER_PASSWORD_AUTH)
                    .authParameters(Map.of("USERNAME", email, "PASSWORD", password))
                    .build());
        } catch (NotAuthorizedException | UserNotFoundException e) {
            // Also what Cognito answers if the app client does not allow USER_PASSWORD_AUTH: log its reason.
            log.info("Cognito refused a login: {}", cognitoMessage(e));
            throw new InvalidCredentialsException();
        } catch (UserNotConfirmedException e) {
            throw new InvalidCredentialsException("This account is not confirmed yet");
        } catch (PasswordResetRequiredException e) {
            throw new InvalidCredentialsException("This account must reset its password first");
        } catch (SdkException e) {
            throw failure("login", e);
        }
        if (response.authenticationResult() == null) {
            // e.g. NEW_PASSWORD_REQUIRED for a user created in the AWS console with a temporary password.
            throw new InvalidCredentialsException("Login needs the Cognito challenge "
                    + response.challengeNameAsString() + ", which this service does not support");
        }
        return tokens(response.authenticationResult(), null);
    }

    /**
     * New access and ID tokens for a refresh token. Unless refresh-token rotation is enabled on the app client,
     * Cognito returns no new refresh token: the presented one stays valid and is returned as-is.
     */
    public CognitoTokens refresh(String refreshToken) {
        requireConfigured();
        try {
            InitiateAuthResponse response = cognito.initiateAuth(InitiateAuthRequest.builder()
                    .clientId(properties.clientId())
                    .authFlow(AuthFlowType.REFRESH_TOKEN_AUTH)
                    .authParameters(Map.of("REFRESH_TOKEN", refreshToken))
                    .build());
            return tokens(response.authenticationResult(), refreshToken);
        } catch (NotAuthorizedException | UserNotFoundException e) {
            throw new InvalidTokenException("Refresh token is invalid, expired or revoked");
        } catch (SdkException e) {
            throw failure("token refresh", e);
        }
    }

    /**
     * Revokes a refresh token and the access tokens issued from it. Idempotent: an unknown or already revoked
     * token is a no-op, not an error.
     */
    public void revoke(String refreshToken) {
        requireConfigured();
        try {
            cognito.revokeToken(RevokeTokenRequest.builder()
                    .clientId(properties.clientId())
                    .token(refreshToken)
                    .build());
        } catch (UnauthorizedException | InvalidParameterException | UnsupportedTokenTypeException e) {
            log.debug("Logout with a refresh token Cognito does not accept: {}", cognitoMessage(e));
        } catch (SdkException e) {
            throw failure("logout", e);
        }
    }

    /**
     * Deletes the Cognito user (by Cognito username). Already gone is fine. Its existing access tokens stay valid until
     * they expire.
     */
    public void deleteUser(String username) {
        requireConfigured();
        try {
            cognito.adminDeleteUser(deleteRequest(username));
        } catch (UserNotFoundException e) {
            log.info("Cognito user {} was already deleted", username);
        } catch (SdkException e) {
            throw failure("account deletion", e);
        }
    }

    /**
     * Creates any missing role group. A group counts as present whatever its case ({@code Participant} or
     * {@code participant}), like every service's role check; only a missing one is created, in lower case.
     */
    public void ensureGroups() {
        requireConfigured();
        Map<UserAccount.Role, String> names;
        try {
            names = existingGroups();
        } catch (SdkException e) {
            throw failure("group lookup", e);
        }
        for (UserAccount.Role role : UserAccount.Role.values()) {
            if (!names.containsKey(role)) {
                createGroup(role);
                names.put(role, role.groupName());
            }
        }
        groupNames = Map.copyOf(names);
    }

    /**
     * Makes sure a confirmed user with this username exists in the role's group and returns its {@code sub}. A new
     * user gets {@code password} as its permanent password and a verified {@code email}; an existing user's
     * password is left alone. Used to seed the ADMIN account.
     *
     * <p>{@code username} is the email for a pool that signs in with email; for a pool that signs in with usernames
     * (and email as an alias), Cognito refuses an email-shaped username, so a plain one such as {@code admin} is
     * configured instead.
     */
    public UUID ensureUser(String username, String email, String password, UserAccount.Role role) {
        requireConfigured();
        String sub;
        try {
            sub = attribute(cognito.adminGetUser(AdminGetUserRequest.builder()
                    .userPoolId(properties.userPoolId())
                    .username(username)
                    .build()).userAttributes(), "sub");
        } catch (UserNotFoundException e) {
            sub = createWithPassword(username, email, password);
        } catch (SdkException e) {
            throw failure("user lookup", e);
        }
        if (sub == null) {
            throw new IllegalStateException("Cognito user " + username + " has no sub attribute");
        }
        try {
            addToGroup(username, role);
        } catch (SdkException e) {
            throw failure("group membership", e);
        }
        return UUID.fromString(sub);
    }

    private void createGroup(UserAccount.Role role) {
        try {
            cognito.createGroup(CreateGroupRequest.builder()
                    .userPoolId(properties.userPoolId())
                    .groupName(role.groupName())
                    .description("party.fun " + role.name() + " role")
                    .build());
            log.info("Created Cognito group {}", role.groupName());
        } catch (SdkException e) {
            throw failure("group creation", e);
        }
    }

    private String createWithPassword(String username, String email, String password) {
        String sub;
        try {
            sub = attribute(cognito.adminCreateUser(AdminCreateUserRequest.builder()
                    .userPoolId(properties.userPoolId())
                    .username(username)
                    .userAttributes(newAttribute("email", email), newAttribute("email_verified", "true"))
                    .messageAction(MessageActionType.SUPPRESS)
                    .build()).user().attributes(), "sub");
        } catch (SdkException e) {
            throw failure("user creation", e);
        }
        if (sub == null) {
            throw new IllegalStateException("Cognito user " + username + " has no sub attribute");
        }
        try {
            cognito.adminSetUserPassword(AdminSetUserPasswordRequest.builder()
                    .userPoolId(properties.userPoolId())
                    .username(username)
                    .password(password)
                    .permanent(true)
                    .build());
        } catch (SdkException e) {
            // Without a permanent password the user is stuck in FORCE_CHANGE_PASSWORD: remove it so the next
            // start tries again (e.g. after the password was changed to meet the pool's policy).
            deleteQuietly(username);
            throw failure("setting the password", e);
        }
        return sub;
    }

    private void addToGroup(String username, UserAccount.Role role) {
        cognito.adminAddUserToGroup(AdminAddUserToGroupRequest.builder()
                .userPoolId(properties.userPoolId())
                .username(username)
                .groupName(groupName(role))
                .build());
    }

    /** The pool's actual name for the role's group (e.g. {@code Participant}); the lower-case name if none exists. */
    private String groupName(UserAccount.Role role) {
        Map<UserAccount.Role, String> names = groupNames;
        if (names == null || !names.containsKey(role)) {
            names = Map.copyOf(existingGroups());
            groupNames = names;
        }
        return names.getOrDefault(role, role.groupName());
    }

    /** The pool's role groups, matched to roles ignoring case, keyed by role. */
    private Map<UserAccount.Role, String> existingGroups() {
        Map<UserAccount.Role, String> names = new EnumMap<>(UserAccount.Role.class);
        String nextToken = null;
        do {
            ListGroupsResponse page = cognito.listGroups(ListGroupsRequest.builder()
                    .userPoolId(properties.userPoolId())
                    .limit(60)
                    .nextToken(nextToken)
                    .build());
            for (GroupType group : page.groups()) {
                for (UserAccount.Role role : UserAccount.Role.values()) {
                    if (role.groupName().equalsIgnoreCase(group.groupName())) {
                        names.putIfAbsent(role, group.groupName());
                    }
                }
            }
            nextToken = page.nextToken();
        } while (nextToken != null && !nextToken.isEmpty());
        return names;
    }

    private void deleteQuietly(String username) {
        try {
            cognito.adminDeleteUser(deleteRequest(username));
        } catch (SdkException e) {
            log.error("Could not remove the half-created Cognito user {}; delete it in the AWS console", username, e);
        }
    }

    private AdminDeleteUserRequest deleteRequest(String username) {
        return AdminDeleteUserRequest.builder()
                .userPoolId(properties.userPoolId())
                .username(username)
                .build();
    }

    private void requireConfigured() {
        if (!properties.isConfigured()) {
            throw new IdentityProviderException(
                    "Cognito is not configured: set COGNITO_USER_POOL_ID and COGNITO_CLIENT_ID", null);
        }
    }

    private static IdentityProviderException failure(String action, SdkException e) {
        log.error("Cognito {} failed: {}", action, cognitoMessage(e));
        return new IdentityProviderException("Cognito " + action + " failed", e);
    }

    private static CognitoTokens tokens(AuthenticationResultType result, String presentedRefreshToken) {
        String refreshToken = result.refreshToken() != null ? result.refreshToken() : presentedRefreshToken;
        long expiresIn = result.expiresIn() == null ? 0 : result.expiresIn();
        return new CognitoTokens(result.accessToken(), result.idToken(), refreshToken, expiresIn);
    }

    /** The value of one user attribute (e.g. {@code sub}, {@code email}), or null when the user has none. */
    private static String attribute(List<AttributeType> attributes, String name) {
        return attributes.stream()
                .filter(a -> name.equals(a.name()))
                .map(AttributeType::value)
                .findFirst()
                .orElse(null);
    }

    private static AttributeType newAttribute(String name, String value) {
        return AttributeType.builder().name(name).value(value).build();
    }

    /** Cognito's own explanation (e.g. which password rule failed), without the SDK's request-id noise. */
    private static String cognitoMessage(SdkException e) {
        if (e instanceof AwsServiceException service && service.awsErrorDetails() != null
                && service.awsErrorDetails().errorMessage() != null) {
            return service.awsErrorDetails().errorMessage();
        }
        return e.getMessage();
    }
}
