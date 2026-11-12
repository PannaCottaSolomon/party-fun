package auth.service;

import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import auth.cognito.CognitoGateway;
import auth.cognito.CognitoUser;
import auth.config.FeatureFlags;
import auth.dto.UserDto;
import auth.entity.UserAccount;
import auth.exception.ConflictException;
import auth.exception.EmailAlreadyExistsException;
import auth.exception.ForbiddenException;
import auth.exception.UserNotFoundException;
import auth.repository.UserAccountRepository;

/**
 * Profile onboarding, reads and updates (formerly user-service). Users sign up in Cognito through the frontend;
 * {@link #onboard} then gives them their role group and profile row.
 */
@Service
public class UserService {

    /** Roles a user may choose for themselves. ADMIN is intentionally excluded (fail-closed). */
    private static final Set<UserAccount.Role> SELF_SELECTABLE_ROLES =
            EnumSet.of(UserAccount.Role.PARTICIPANT, UserAccount.Role.ORGANISER);

    private final UserAccountRepository userAccountRepository;
    private final CognitoGateway cognito;
    private final UserEventPublisher eventPublisher;
    private final FeatureFlags features;

    public UserService(UserAccountRepository userAccountRepository,
                       CognitoGateway cognito,
                       UserEventPublisher eventPublisher,
                       FeatureFlags features) {
        this.userAccountRepository = userAccountRepository;
        this.cognito = cognito;
        this.eventPublisher = eventPublisher;
        this.features = features;
    }

    /**
     * First-time setup after a Cognito sign-up from the frontend: checks the chosen role, creates the profile row
     * (id = the Cognito sub, email = Cognito's verified email) and adds the user to the role's Cognito group. The
     * row is written first and the group last, so a Cognito failure rolls the row back and the call can simply be
     * retried. The caller's current token has no group yet: the frontend refreshes it afterwards.
     *
     * @param id              the account id: the token's sub
     * @param cognitoUsername the token's Cognito username, for the Cognito admin calls
     * @throws IllegalArgumentException if the role is not PARTICIPANT or ORGANISER
     * @throws ConflictException if the profile already exists, the email is taken, or Cognito already has the user
     *                           in another role's group
     * @throws ForbiddenException if the account has no email, or (with email verification on) has not verified it
     */
    @Transactional
    public UserDto.Response onboard(UUID id, String cognitoUsername, UserDto.OnboardRequest request) {
        UserAccount.Role role = request.getRole();
        if (!SELF_SELECTABLE_ROLES.contains(role)) {
            throw new IllegalArgumentException("role must be one of " + SELF_SELECTABLE_ROLES);
        }
        if (userAccountRepository.existsById(id)) {
            throw new ConflictException("Your profile already exists");
        }

        CognitoUser cognitoUser = cognito.lookup(cognitoUsername);
        if (cognitoUser.email() == null) {
            throw new ForbiddenException("This account has no email address");
        }
        if (features.emailVerification() && !cognitoUser.emailVerified()) {
            throw new ForbiddenException("Confirm your email address before creating your profile");
        }
        String email = cognitoUser.email().trim().toLowerCase();
        if (userAccountRepository.existsByEmail(email)) {
            throw new EmailAlreadyExistsException(email);
        }
        // A user already in a role group (e.g. put there in the AWS console) keeps that role: onboarding never
        // grants a second or different one.
        if (!cognitoUser.roles().isEmpty() && !cognitoUser.roles().equals(Set.of(role))) {
            throw new ConflictException("This account already has the role " + cognitoUser.roles());
        }

        UserAccount account = new UserAccount();
        account.setId(id);
        account.setEmail(email);
        account.setFullName(request.getFullName());
        account.setRole(role);
        account.setPhoneNumber(request.getPhoneNumber());
        UserAccount saved = userAccountRepository.saveAndFlush(account);

        cognito.addUserToGroup(cognitoUsername, role);
        eventPublisher.publishUserCreated(saved);
        return UserDto.Response.fromEntity(saved);
    }

    @Transactional(readOnly = true)
    public UserDto.Response getById(UUID id) {
        return UserDto.Response.fromEntity(load(id));
    }

    @Transactional(readOnly = true)
    public List<UserDto.Response> getAll() {
        return userAccountRepository.findAll().stream()
                .map(UserDto.Response::fromEntity)
                .toList();
    }

    @Transactional
    public UserDto.Response update(UUID id, UserDto.UpdateRequest request) {
        UserAccount user = load(id);
        if (request.getFullName() != null) {
            user.setFullName(request.getFullName());
        }
        if (request.getPhoneNumber() != null) {
            user.setPhoneNumber(request.getPhoneNumber());
        }
        UserAccount saved = userAccountRepository.save(user);
        eventPublisher.publishUserUpdated(saved);
        return UserDto.Response.fromEntity(saved);
    }

    /**
     * Deletes the Cognito user first (so the account can no longer log in or refresh), then the profile. Access
     * tokens already issued stay valid until they expire (Cognito default: 1 hour).
     */
    @Transactional
    public void delete(UUID id, String cognitoUsername) {
        if (!userAccountRepository.existsById(id)) {
            throw new UserNotFoundException(id);
        }
        cognito.deleteUser(cognitoUsername);
        userAccountRepository.deleteById(id);
        eventPublisher.publishUserDeleted(id);
    }

    private UserAccount load(UUID id) {
        return userAccountRepository.findById(id).orElseThrow(() -> new UserNotFoundException(id));
    }
}
