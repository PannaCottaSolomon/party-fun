package auth.service;

import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import auth.cognito.CognitoGateway;
import auth.cognito.CognitoUser;
import auth.config.FeatureFlags;
import auth.dto.UserDto;
import auth.entity.UserAccount;
import auth.exception.ConflictException;
import auth.exception.EmailAlreadyExistsException;
import auth.exception.ForbiddenException;
import auth.repository.UserAccountRepository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.AdditionalAnswers.returnsFirstArg;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Onboarding's rules: who may choose which role, and when a profile can be created. */
class UserServiceTest {

    private final UserAccountRepository repository = mock(UserAccountRepository.class);
    private final CognitoGateway cognito = mock(CognitoGateway.class);
    private final UserEventPublisher publisher = mock(UserEventPublisher.class);
    private final UserService userService = new UserService(repository, cognito, publisher, new FeatureFlags(true));

    private final UUID sub = UUID.randomUUID();
    private static final String NAME = "pat";

    private static UserDto.OnboardRequest as(UserAccount.Role role) {
        return new UserDto.OnboardRequest("Pat", role, null);
    }

    private void cognitoHas(CognitoUser user) {
        when(cognito.lookup(NAME)).thenReturn(user);
        when(repository.saveAndFlush(any())).then(returnsFirstArg());
    }

    @Test
    void admin_cannotBeChosen_andCognitoIsNotAsked() {
        assertThatThrownBy(() -> userService.onboard(sub, NAME, as(UserAccount.Role.ADMIN)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("role must be one of [PARTICIPANT, ORGANISER]");
        verify(cognito, never()).lookup(any());
    }

    @Test
    void organiser_canBeChosen() {
        cognitoHas(new CognitoUser("olive@example.com", true, Set.of()));

        UserDto.Response created = userService.onboard(sub, NAME, as(UserAccount.Role.ORGANISER));

        assertThat(created.getRole()).isEqualTo(UserAccount.Role.ORGANISER);
        verify(cognito).addUserToGroup(NAME, UserAccount.Role.ORGANISER);
    }

    @Test
    void unverifiedEmail_isRefused() {
        cognitoHas(new CognitoUser("pat@example.com", false, Set.of()));

        assertThatThrownBy(() -> userService.onboard(sub, NAME, as(UserAccount.Role.PARTICIPANT)))
                .isInstanceOf(ForbiddenException.class)
                .hasMessage("Confirm your email address before creating your profile");
        verify(repository, never()).saveAndFlush(any());
    }

    @Test
    void emailTakenByAnotherProfile_isAConflict() {
        cognitoHas(new CognitoUser("taken@example.com", true, Set.of()));
        when(repository.existsByEmail("taken@example.com")).thenReturn(true);

        assertThatThrownBy(() -> userService.onboard(sub, NAME, as(UserAccount.Role.PARTICIPANT)))
                .isInstanceOf(EmailAlreadyExistsException.class);
        verify(cognito, never()).addUserToGroup(any(), any());
    }

    @Test
    void userAlreadyInAnotherRoleGroup_cannotSwitchOrAddARole() {
        cognitoHas(new CognitoUser("olive@example.com", true, Set.of(UserAccount.Role.ORGANISER)));

        assertThatThrownBy(() -> userService.onboard(sub, NAME, as(UserAccount.Role.PARTICIPANT)))
                .isInstanceOf(ConflictException.class);
        verify(cognito, never()).addUserToGroup(any(), any());
    }

    @Test
    void userAlreadyInTheSameGroup_justGetsItsProfile() {
        // e.g. the earlier attempt added the group but the profile save was lost.
        cognitoHas(new CognitoUser("pat@example.com", true, Set.of(UserAccount.Role.PARTICIPANT)));

        UserDto.Response created = userService.onboard(sub, NAME, as(UserAccount.Role.PARTICIPANT));

        assertThat(created.getEmail()).isEqualTo("pat@example.com");
        verify(publisher).publishUserCreated(any());
    }

    // ---- EMAIL_VERIFICATION_ENABLED=false ----

    @Test
    void withEmailVerificationOff_anUnverifiedEmailIsAccepted() {
        UserService noVerification = new UserService(repository, cognito, publisher, new FeatureFlags(false));
        cognitoHas(new CognitoUser("pat@example.com", false, Set.of()));

        UserDto.Response created = noVerification.onboard(sub, NAME, as(UserAccount.Role.PARTICIPANT));

        assertThat(created.getEmail()).isEqualTo("pat@example.com");
        verify(cognito).addUserToGroup(NAME, UserAccount.Role.PARTICIPANT);
    }

    @Test
    void withEmailVerificationOff_anAccountWithoutAnyEmailIsStillRefused() {
        UserService noVerification = new UserService(repository, cognito, publisher, new FeatureFlags(false));
        cognitoHas(new CognitoUser(null, false, Set.of()));

        assertThatThrownBy(() -> noVerification.onboard(sub, NAME, as(UserAccount.Role.PARTICIPANT)))
                .isInstanceOf(ForbiddenException.class)
                .hasMessage("This account has no email address");
    }
}
