package ch.alpenflight.platform.security;

import java.util.UUID;

public interface PrincipalClubBindingRule {

    boolean refusesPrincipalCarryingClub(String preferredUsername, UUID clubId);
}
