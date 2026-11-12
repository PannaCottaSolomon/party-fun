package auth.cognito;

import java.util.Arrays;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import auth.entity.UserAccount;
import auth.exception.ConflictException;
import auth.exception.IdentityProviderException;
import auth.exception.InvalidCredentialsException;
import auth.exception.InvalidTokenException;
import software.amazon.awssdk.awscore.exception.AwsErrorDetails;
import software.amazon.awssdk.awscore.exception.AwsServiceException;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.services.cognitoidentityprovider.CognitoIdentityProviderClient;
import software.amazon.awssdk.services.cognitoidentityprovider.model.AdminAddUserToGroupRequest;
import software.amazon.awssdk.services.cognitoidentityprovider.model.AdminConfirmSignUpRequest;
import software.amazon.awssdk.services.cognitoidentityprovider.model.AdminCreateUserRequest;
import software.amazon.awssdk.services.cognitoidentityprovider.model.AdminCreateUserResponse;
import software.amazon.awssdk.services.cognitoidentityprovider.model.AdminDeleteUserRequest;
import software.amazon.awssdk.services.cognitoidentityprovider.model.AdminGetUserRequest;
import software.amazon.awssdk.services.cognitoidentityprovider.model.AdminGetUserResponse;
import software.amazon.awssdk.services.cognitoidentityprovider.model.AdminListGroupsForUserRequest;
import software.amazon.awssdk.services.cognitoidentityprovider.model.AdminListGroupsForUserResponse;
import software.amazon.awssdk.services.cognitoidentityprovider.model.AdminSetUserPasswordRequest;
import software.amazon.awssdk.services.cognitoidentityprovider.model.AdminUpdateUserAttributesRequest;
import software.amazon.awssdk.services.cognitoidentityprovider.model.AliasExistsException;
import software.amazon.awssdk.services.cognitoidentityprovider.model.AttributeType;
import software.amazon.awssdk.services.cognitoidentityprovider.model.AuthFlowType;
import software.amazon.awssdk.services.cognitoidentityprovider.model.AuthenticationResultType;
import software.amazon.awssdk.services.cognitoidentityprovider.model.ChallengeNameType;
import software.amazon.awssdk.services.cognitoidentityprovider.model.CreateGroupRequest;
import software.amazon.awssdk.services.cognitoidentityprovider.model.GroupType;
import software.amazon.awssdk.services.cognitoidentityprovider.model.InitiateAuthRequest;
import software.amazon.awssdk.services.cognitoidentityprovider.model.InitiateAuthResponse;
import software.amazon.awssdk.services.cognitoidentityprovider.model.InvalidPasswordException;
import software.amazon.awssdk.services.cognitoidentityprovider.model.ListGroupsRequest;
import software.amazon.awssdk.services.cognitoidentityprovider.model.ListGroupsResponse;
import software.amazon.awssdk.services.cognitoidentityprovider.model.ListUsersRequest;
import software.amazon.awssdk.services.cognitoidentityprovider.model.ListUsersResponse;
import software.amazon.awssdk.services.cognitoidentityprovider.model.NotAuthorizedException;
import software.amazon.awssdk.services.cognitoidentityprovider.model.ResourceNotFoundException;
import software.amazon.awssdk.services.cognitoidentityprovider.model.RevokeTokenRequest;
import software.amazon.awssdk.services.cognitoidentityprovider.model.UnauthorizedException;
import software.amazon.awssdk.services.cognitoidentityprovider.model.UserNotFoundException;
import software.amazon.awssdk.services.cognitoidentityprovider.model.UserType;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** The Cognito calls and error mapping, against a mocked SDK client. */
class CognitoGatewayTest {

    private static final String POOL = "ap-southeast-1_testpool";
    private static final String CLIENT = "test-client";

    private final CognitoIdentityProviderClient client = mock(CognitoIdentityProviderClient.class);
    private final CognitoGateway gateway =
            new CognitoGateway(client, new CognitoProperties("ap-southeast-1", POOL, CLIENT));
    private final UUID sub = UUID.randomUUID();

    /** By default the pool has no role groups yet, so the lower-case names are used. */
    @BeforeEach
    void emptyPool() {
        when(client.listGroups(any(ListGroupsRequest.class))).thenReturn(ListGroupsResponse.builder().build());
    }

    /** Cognito errors carry their reason in the error details, as the real service does. */
    private static <T extends AwsServiceException.Builder> T withMessage(T builder, String message) {
        builder.message(message).awsErrorDetails(AwsErrorDetails.builder().errorMessage(message).build());
        return builder;
    }

    private static AttributeType subAttribute(UUID sub) {
        return AttributeType.builder().name("sub").value(sub.toString()).build();
    }

    private static InitiateAuthResponse authResult(String refreshToken) {
        return InitiateAuthResponse.builder().authenticationResult(AuthenticationResultType.builder()
                .accessToken("access").idToken("id").refreshToken(refreshToken).expiresIn(3600).build()).build();
    }

    // ---- onboarding: look up the signed-in user, add its group ----

    private static AttributeType attr(String name, String value) {
        return AttributeType.builder().name(name).value(value).build();
    }

    @Test
    void lookup_readsEmailVerificationAndRoleGroups_byUsername() {
        when(client.adminGetUser(any(AdminGetUserRequest.class))).thenReturn(AdminGetUserResponse.builder()
                .userAttributes(subAttribute(sub), attr("email", "pat@example.com"), attr("email_verified", "true"))
                .build());
        when(client.adminListGroupsForUser(any(AdminListGroupsForUserRequest.class)))
                .thenReturn(AdminListGroupsForUserResponse.builder().groups(
                        GroupType.builder().groupName("organiser").build(),
                        GroupType.builder().groupName("beta-testers").build()).build());

        CognitoUser user = gateway.lookup("pat");

        assertThat(user).isEqualTo(new CognitoUser("pat@example.com", true, Set.of(UserAccount.Role.ORGANISER)));
        verify(client).adminGetUser(AdminGetUserRequest.builder().userPoolId(POOL).username("pat").build());
        verify(client).adminListGroupsForUser(AdminListGroupsForUserRequest.builder()
                .userPoolId(POOL).username("pat").build());
    }

    @Test
    void lookup_unverifiedEmail_isReported() {
        when(client.adminGetUser(any(AdminGetUserRequest.class))).thenReturn(AdminGetUserResponse.builder()
                .userAttributes(attr("email", "pat@example.com"), attr("email_verified", "false")).build());
        when(client.adminListGroupsForUser(any(AdminListGroupsForUserRequest.class)))
                .thenReturn(AdminListGroupsForUserResponse.builder().build());

        assertThat(gateway.lookup("pat").emailVerified()).isFalse();
    }

    // ---- confirming a sign-up without the code (email verification switched off) ----

    private static UserType poolUser(String username, String email, boolean verified) {
        return UserType.builder().username(username)
                .attributes(attr("email", email), attr("email_verified", String.valueOf(verified))).build();
    }

    /** "newbie" signed up with new@x.sg; {@code alsoInPool} are the other users with that email. */
    private void signedUp(UserType... alsoInPool) {
        when(client.adminGetUser(any(AdminGetUserRequest.class))).thenReturn(AdminGetUserResponse.builder()
                .username("newbie").userAttributes(attr("email", "new@x.sg"), attr("email_verified", "false")).build());
        UserType[] all = Arrays.copyOf(alsoInPool, alsoInPool.length + 1);
        all[alsoInPool.length] = poolUser("newbie", "new@x.sg", false);
        when(client.listUsers(any(ListUsersRequest.class))).thenReturn(ListUsersResponse.builder().users(all).build());
    }

    @Test
    void confirmWithoutCode_confirmsTheAccount_andMarksItsEmailVerified() {
        signedUp();

        gateway.confirmWithoutCode("newbie");

        verify(client).listUsers(ListUsersRequest.builder().userPoolId(POOL).filter("email = \"new@x.sg\"").build());

        verify(client).adminConfirmSignUp(AdminConfirmSignUpRequest.builder()
                .userPoolId(POOL).username("newbie").build());
        verify(client).adminUpdateUserAttributes(AdminUpdateUserAttributesRequest.builder()
                .userPoolId(POOL).username("newbie").userAttributes(attr("email_verified", "true")).build());
    }

    @Test
    void confirmWithoutCode_alreadyConfirmed_stillMarksTheEmailVerified() {
        signedUp();
        when(client.adminConfirmSignUp(any(AdminConfirmSignUpRequest.class))).thenThrow(withMessage(
                NotAuthorizedException.builder(), "User cannot be confirmed. Current status is CONFIRMED").build());

        gateway.confirmWithoutCode("newbie");

        verify(client).adminUpdateUserAttributes(any(AdminUpdateUserAttributesRequest.class));
    }

    @Test
    void confirmWithoutCode_unknownUser_isNotFound() {
        when(client.adminGetUser(any(AdminGetUserRequest.class)))
                .thenThrow(withMessage(UserNotFoundException.builder(), "User does not exist.").build());

        assertThatThrownBy(() -> gateway.confirmWithoutCode("ghost"))
                .isInstanceOf(auth.exception.UserNotFoundException.class);
        verify(client, never()).adminConfirmSignUp(any(AdminConfirmSignUpRequest.class));
        verify(client, never()).adminUpdateUserAttributes(any(AdminUpdateUserAttributesRequest.class));
    }

    @Test
    void confirmWithoutCode_emailVerifiedOnAnotherAccount_isRefused_andNeitherAccountIsTouched() {
        // What happened in the team pool: "organiser1" lost its verified email to a new sign-up with the same email.
        signedUp(poolUser("organiser1", "new@x.sg", true));

        assertThatThrownBy(() -> gateway.confirmWithoutCode("newbie"))
                .isInstanceOf(ConflictException.class)
                .hasMessage("Another account already uses this email address");
        verify(client, never()).adminConfirmSignUp(any(AdminConfirmSignUpRequest.class));
        verify(client, never()).adminUpdateUserAttributes(any(AdminUpdateUserAttributesRequest.class));
    }

    @Test
    void confirmWithoutCode_sameEmailUnverifiedOnAnotherAccount_isAllowed() {
        signedUp(poolUser("someone", "new@x.sg", false));

        gateway.confirmWithoutCode("newbie");

        verify(client).adminConfirmSignUp(any(AdminConfirmSignUpRequest.class));
    }

    @Test
    void confirmWithoutCode_cognitoStillRefusesTheAlias_isAConflict() {
        signedUp();
        when(client.adminUpdateUserAttributes(any(AdminUpdateUserAttributesRequest.class)))
                .thenThrow(withMessage(AliasExistsException.builder(), "An account with the email already exists.")
                        .build());

        assertThatThrownBy(() -> gateway.confirmWithoutCode("newbie"))
                .isInstanceOf(ConflictException.class)
                .hasMessage("Another account already uses this email address");
    }

    @Test
    void lookup_deletedUser_isAnInvalidToken() {
        when(client.adminGetUser(any(AdminGetUserRequest.class)))
                .thenThrow(withMessage(UserNotFoundException.builder(), "User does not exist.").build());

        assertThatThrownBy(() -> gateway.lookup("pat"))
                .isInstanceOf(InvalidTokenException.class)
                .hasMessage("This account no longer exists");
    }

    @Test
    void addToGroup_addressesTheUserByUsername() {
        gateway.addUserToGroup("pat", UserAccount.Role.PARTICIPANT);

        verify(client).adminAddUserToGroup(AdminAddUserToGroupRequest.builder()
                .userPoolId(POOL).username("pat").groupName("participant").build());
    }

    @Test
    void addToGroup_failure_isServiceUnavailable() {
        when(client.adminAddUserToGroup(any(AdminAddUserToGroupRequest.class)))
                .thenThrow(withMessage(ResourceNotFoundException.builder(), "Group not found").build());

        assertThatThrownBy(() -> gateway.addUserToGroup("pat", UserAccount.Role.PARTICIPANT))
                .isInstanceOf(IdentityProviderException.class);
    }

    // ---- login / refresh / logout ----

    @Test
    void login_usesUserPasswordAuth_andReturnsTheTokens() {
        when(client.initiateAuth(any(InitiateAuthRequest.class))).thenReturn(authResult("refresh"));

        CognitoTokens tokens = gateway.login("pat@example.com", "Password123!");

        assertThat(tokens).isEqualTo(new CognitoTokens("access", "id", "refresh", 3600));
        ArgumentCaptor<InitiateAuthRequest> request = ArgumentCaptor.forClass(InitiateAuthRequest.class);
        verify(client).initiateAuth(request.capture());
        assertThat(request.getValue().authFlow()).isEqualTo(AuthFlowType.USER_PASSWORD_AUTH);
        assertThat(request.getValue().clientId()).isEqualTo(CLIENT);
        assertThat(request.getValue().authParameters())
                .containsEntry("USERNAME", "pat@example.com")
                .containsEntry("PASSWORD", "Password123!");
    }

    @Test
    void login_wrongPasswordOrUnknownUser_isUnauthorized() {
        when(client.initiateAuth(any(InitiateAuthRequest.class)))
                .thenThrow(withMessage(NotAuthorizedException.builder(), "Incorrect username or password.").build())
                .thenThrow(withMessage(UserNotFoundException.builder(), "User does not exist.").build());

        assertThatThrownBy(() -> gateway.login("pat@example.com", "wrong"))
                .isInstanceOf(InvalidCredentialsException.class).hasMessage("Invalid email or password");
        assertThatThrownBy(() -> gateway.login("ghost@example.com", "x"))
                .isInstanceOf(InvalidCredentialsException.class).hasMessage("Invalid email or password");
    }

    @Test
    void login_challenge_isRefused_withItsName() {
        when(client.initiateAuth(any(InitiateAuthRequest.class))).thenReturn(InitiateAuthResponse.builder()
                .challengeName(ChallengeNameType.NEW_PASSWORD_REQUIRED).session("s").build());

        assertThatThrownBy(() -> gateway.login("pat@example.com", "Temp123!"))
                .isInstanceOf(InvalidCredentialsException.class)
                .hasMessageContaining("NEW_PASSWORD_REQUIRED");
    }

    @Test
    void login_cognitoUnreachable_isServiceUnavailable() {
        when(client.initiateAuth(any(InitiateAuthRequest.class)))
                .thenThrow(SdkClientException.create("Unable to execute HTTP request"));

        assertThatThrownBy(() -> gateway.login("pat@example.com", "Password123!"))
                .isInstanceOf(IdentityProviderException.class);
    }

    @Test
    void refresh_keepsThePresentedRefreshToken_whenCognitoReturnsNone() {
        when(client.initiateAuth(any(InitiateAuthRequest.class))).thenReturn(authResult(null));

        CognitoTokens tokens = gateway.refresh("my-refresh");

        assertThat(tokens.refreshToken()).isEqualTo("my-refresh");
        ArgumentCaptor<InitiateAuthRequest> request = ArgumentCaptor.forClass(InitiateAuthRequest.class);
        verify(client).initiateAuth(request.capture());
        assertThat(request.getValue().authFlow()).isEqualTo(AuthFlowType.REFRESH_TOKEN_AUTH);
        assertThat(request.getValue().authParameters()).containsEntry("REFRESH_TOKEN", "my-refresh");
    }

    @Test
    void refresh_revokedToken_isUnauthorized() {
        when(client.initiateAuth(any(InitiateAuthRequest.class)))
                .thenThrow(withMessage(NotAuthorizedException.builder(), "Refresh Token has been revoked").build());

        assertThatThrownBy(() -> gateway.refresh("revoked")).isInstanceOf(InvalidTokenException.class);
    }

    @Test
    void revoke_sendsTheToken_andAnUnknownTokenIsANoOp() {
        gateway.revoke("my-refresh");
        verify(client).revokeToken(RevokeTokenRequest.builder().clientId(CLIENT).token("my-refresh").build());

        when(client.revokeToken(any(RevokeTokenRequest.class)))
                .thenThrow(withMessage(UnauthorizedException.builder(), "Invalid token").build());
        gateway.revoke("never-issued"); // does not throw
    }

    @Test
    void deleteUser_byUsername_andAlreadyGoneIsFine() {
        when(client.adminDeleteUser(any(AdminDeleteUserRequest.class)))
                .thenThrow(withMessage(UserNotFoundException.builder(), "User does not exist.").build());

        gateway.deleteUser("pat"); // does not throw

        verify(client).adminDeleteUser(AdminDeleteUserRequest.builder()
                .userPoolId(POOL).username("pat").build());
    }

    // ---- bootstrap helpers ----

    private void poolHasGroups(String... names) {
        when(client.listGroups(any(ListGroupsRequest.class))).thenReturn(ListGroupsResponse.builder()
                .groups(Arrays.stream(names).map(n -> GroupType.builder().groupName(n).build()).toList())
                .build());
    }

    @Test
    void ensureGroups_createsOnlyTheMissingGroups_inLowerCase() {
        poolHasGroups("participant", "Organiser", "beta-testers");

        gateway.ensureGroups();

        ArgumentCaptor<CreateGroupRequest> created = ArgumentCaptor.forClass(CreateGroupRequest.class);
        verify(client).createGroup(created.capture());
        assertThat(created.getValue().groupName()).isEqualTo("admin");
        assertThat(created.getValue().userPoolId()).isEqualTo(POOL);
    }

    @Test
    void groupsThatDifferOnlyInCase_areReused_notDuplicated() {
        // The team's pool names its groups Participant / Organiser / Admin.
        poolHasGroups("Participant", "Organiser", "Admin");

        gateway.ensureGroups();
        gateway.addUserToGroup("pat", UserAccount.Role.PARTICIPANT);

        verify(client, never()).createGroup(any(CreateGroupRequest.class));
        verify(client).adminAddUserToGroup(AdminAddUserToGroupRequest.builder()
                .userPoolId(POOL).username("pat").groupName("Participant").build());
    }

    @Test
    void groupNamesAreReadFromThePool_evenWithoutEnsureGroups() {
        poolHasGroups("Organiser");

        gateway.addUserToGroup("pat", UserAccount.Role.ORGANISER);

        verify(client).adminAddUserToGroup(AdminAddUserToGroupRequest.builder()
                .userPoolId(POOL).username("pat").groupName("Organiser").build());
    }

    @Test
    void ensureGroups_readsEveryPageOfGroups() {
        when(client.listGroups(any(ListGroupsRequest.class)))
                .thenReturn(ListGroupsResponse.builder()
                        .groups(GroupType.builder().groupName("Participant").build()).nextToken("page-2").build())
                .thenReturn(ListGroupsResponse.builder()
                        .groups(GroupType.builder().groupName("Organiser").build(),
                                GroupType.builder().groupName("Admin").build()).build());

        gateway.ensureGroups();

        verify(client, times(2)).listGroups(any(ListGroupsRequest.class));
        verify(client, never()).createGroup(any(CreateGroupRequest.class));
    }

    @Test
    void ensureUser_existingUser_isReused_andItsPasswordIsNotTouched() {
        when(client.adminGetUser(any(AdminGetUserRequest.class)))
                .thenReturn(AdminGetUserResponse.builder().userAttributes(subAttribute(sub)).build());

        assertThat(gateway.ensureUser("admin@example.com", "admin@example.com", "Example-Pass1!", UserAccount.Role.ADMIN)).isEqualTo(sub);

        verify(client, never()).adminCreateUser(any(AdminCreateUserRequest.class));
        verify(client, never()).adminSetUserPassword(any(AdminSetUserPasswordRequest.class));
        verify(client).adminAddUserToGroup(AdminAddUserToGroupRequest.builder()
                .userPoolId(POOL).username("admin@example.com").groupName("admin").build());
    }

    @Test
    void ensureUser_newUser_getsAPermanentPassword_aVerifiedEmail_andNoInvitationEmail() {
        when(client.adminGetUser(any(AdminGetUserRequest.class)))
                .thenThrow(withMessage(UserNotFoundException.builder(), "User does not exist.").build());
        when(client.adminCreateUser(any(AdminCreateUserRequest.class))).thenReturn(AdminCreateUserResponse.builder()
                .user(UserType.builder().attributes(subAttribute(sub)).build()).build());

        assertThat(gateway.ensureUser("admin", "admin@example.com", "Example-Pass1!", UserAccount.Role.ADMIN)).isEqualTo(sub);

        ArgumentCaptor<AdminCreateUserRequest> created = ArgumentCaptor.forClass(AdminCreateUserRequest.class);
        verify(client).adminCreateUser(created.capture());
        assertThat(created.getValue().messageActionAsString()).isEqualTo("SUPPRESS");
        assertThat(created.getValue().username()).isEqualTo("admin");
        assertThat(created.getValue().userAttributes()).contains(attr("email", "admin@example.com"),
                attr("email_verified", "true"));
        verify(client).adminSetUserPassword(AdminSetUserPasswordRequest.builder()
                .userPoolId(POOL).username("admin").password("Example-Pass1!").permanent(true).build());
    }

    @Test
    void ensureUser_passwordRejected_removesTheNewUser() {
        when(client.adminGetUser(any(AdminGetUserRequest.class)))
                .thenThrow(withMessage(UserNotFoundException.builder(), "User does not exist.").build());
        when(client.adminCreateUser(any(AdminCreateUserRequest.class))).thenReturn(AdminCreateUserResponse.builder()
                .user(UserType.builder().attributes(subAttribute(sub)).build()).build());
        when(client.adminSetUserPassword(any(AdminSetUserPasswordRequest.class)))
                .thenThrow(withMessage(InvalidPasswordException.builder(),
                        "Password did not conform with policy: Password must have symbol characters").build());

        assertThatThrownBy(() -> gateway.ensureUser("admin@example.com", "admin@example.com", "admin123",
                UserAccount.Role.ADMIN))
                .isInstanceOf(IdentityProviderException.class)
                .hasRootCauseInstanceOf(InvalidPasswordException.class);
        verify(client).adminDeleteUser(AdminDeleteUserRequest.builder()
                .userPoolId(POOL).username("admin@example.com").build());
    }

    @Test
    void notConfigured_failsWithoutCallingAws() {
        CognitoGateway unconfigured = new CognitoGateway(client, new CognitoProperties("ap-southeast-1", "", ""));

        assertThat(unconfigured.isConfigured()).isFalse();
        assertThatThrownBy(() -> unconfigured.login("pat@example.com", "Password123!"))
                .isInstanceOf(IdentityProviderException.class)
                .hasMessageContaining("COGNITO_USER_POOL_ID");
        verifyNoInteractions(client);
    }
}
