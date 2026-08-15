package ch.alpenflight.users.application;

import ch.alpenflight.users.domain.Role;
import java.util.Set;

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
