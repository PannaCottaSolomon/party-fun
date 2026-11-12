package auth.controller;

import java.util.List;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import auth.dto.UserDto;
import auth.exception.ForbiddenException;
import auth.security.AuthenticatedUser;
import auth.service.UserService;
import jakarta.validation.Valid;

/**
 * Profile endpoints (formerly user-service, same paths). Accounts are created by signing up in Cognito through the
 * frontend; the profile is then created with {@code POST /user/me}. There is no {@code POST /user}.
 *
 * <p>Every endpoint needs a valid access token ({@code JwtAuthInterceptor}). Access rules, least privilege first:
 * <ul>
 *   <li>{@code POST} / {@code GET /user/me} — the caller's own profile; works before the caller has a role.</li>
 *   <li>{@code GET /user/{id}} — the account owner, or ADMIN (read-only, to find who to contact about a
 *       failed refund).</li>
 *   <li>{@code GET /user} — ADMIN only: it lists every account's email.</li>
 *   <li>{@code PUT} / {@code DELETE /user/{id}} — the account owner only. ADMIN's job is refund recovery, not
 *       account management, so it cannot edit or delete accounts.</li>
 * </ul>
 * A caller with a valid token but the wrong identity or role gets 403.
 */
@RestController
@RequestMapping("/user")
public class UserController {

    private final UserService userService;

    public UserController(UserService userService) {
        this.userService = userService;
    }

    /**
     * One-time profile setup after signing up in Cognito through the frontend. Allowed for a token with no role
     * group yet. 201 with the profile; the frontend then refreshes its token to get the new group.
     */
    @PostMapping("/me")
    public ResponseEntity<UserDto.Response> onboard(@Valid @RequestBody UserDto.OnboardRequest request,
            @RequestAttribute(AuthenticatedUser.ATTRIBUTE) AuthenticatedUser caller) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(userService.onboard(caller.id(), caller.username(), request));
    }

    /** The caller's own profile; 404 means "not onboarded yet" (call {@code POST /user/me}). */
    @GetMapping("/me")
    public ResponseEntity<UserDto.Response> me(
            @RequestAttribute(AuthenticatedUser.ATTRIBUTE) AuthenticatedUser caller) {
        return ResponseEntity.ok(userService.getById(caller.id()));
    }

    @GetMapping("/{id}")
    public ResponseEntity<UserDto.Response> getById(@PathVariable UUID id,
            @RequestAttribute(AuthenticatedUser.ATTRIBUTE) AuthenticatedUser caller) {
        if (!caller.isOwnerOf(id) && !caller.isAdmin()) {
            throw new ForbiddenException("You can only view your own profile");
        }
        return ResponseEntity.ok(userService.getById(id));
    }

    @GetMapping
    public ResponseEntity<List<UserDto.Response>> getAll(
            @RequestAttribute(AuthenticatedUser.ATTRIBUTE) AuthenticatedUser caller) {
        if (!caller.isAdmin()) {
            throw new ForbiddenException("Listing all users requires the ADMIN role");
        }
        return ResponseEntity.ok(userService.getAll());
    }

    @PutMapping("/{id}")
    public ResponseEntity<UserDto.Response> update(@PathVariable UUID id,
            @RequestBody UserDto.UpdateRequest request,
            @RequestAttribute(AuthenticatedUser.ATTRIBUTE) AuthenticatedUser caller) {
        requireOwner(caller, id, "You can only update your own profile");
        return ResponseEntity.ok(userService.update(id, request));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable UUID id,
            @RequestAttribute(AuthenticatedUser.ATTRIBUTE) AuthenticatedUser caller) {
        requireOwner(caller, id, "You can only delete your own account");
        userService.delete(id, caller.username());
        return ResponseEntity.noContent().build();
    }

    private static void requireOwner(AuthenticatedUser caller, UUID accountId, String message) {
        if (!caller.isOwnerOf(accountId)) {
            throw new ForbiddenException(message);
        }
    }
}
