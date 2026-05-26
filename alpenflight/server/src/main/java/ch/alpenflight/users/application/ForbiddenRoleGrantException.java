package ch.alpenflight.users.application;

import ch.alpenflight.users.domain.Role;
import java.util.Set;

/**
 * Thrown when a caller tries to grant or revoke a role the
 * {@link RoleAssignmentPolicy} forbids. Mapped to 403 by the controller
 * advice; the audit row is emitted before the throw.
 */
public class ForbiddenRoleGrantException extends RuntimeException {

    private final Set<Role> rejectedRoles;

    public ForbiddenRoleGrantException(Set<Role> rejectedRoles) {
        super("Role grant rejected: " + rejectedRoles);
        this.rejectedRoles = Set.copyOf(rejectedRoles);
    }

    public Set<Role> rejectedRoles() {
        return rejectedRoles;
    }
}
