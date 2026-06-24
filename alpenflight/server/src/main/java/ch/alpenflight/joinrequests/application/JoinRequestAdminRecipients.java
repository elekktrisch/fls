package ch.alpenflight.joinrequests.application;

import ch.alpenflight.referencedata.domain.LanguageRepository;
import ch.alpenflight.users.domain.Role;
import ch.alpenflight.users.domain.UserDirectoryPort;
import ch.alpenflight.users.domain.UserDirectoryPort.UserDirectoryRow;
import ch.alpenflight.users.domain.UserRepository;
import ch.alpenflight.users.domain.UserRepository.ListRow;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Resolves the club admins who should hear about a new PENDING request — the
 * {@code admin-new-request} mail audience + the SSE targets whose pending list
 * just gained a row.
 *
 * <p>Called by the AFTER_COMMIT notification listeners inside a
 * {@code Tenants.runAs} window for the joining club, so the local-user read is
 * tenant-scoped and the live submit transaction makes no directory round-trip.
 * The CLUB_ADMINISTRATOR role lives in Keycloak, not a {@code t_user} column, so
 * the admin set is the intersection of the directory's realm-wide role holders
 * with this club's local rows: the directory call gives the admin subs, the
 * local rows give the per-recipient email + locale for the send loop (S-178 —
 * admins are mailed at {@code User.languageId}). One directory round-trip, not an
 * N+1 over the club's users.
 */
@Component
class JoinRequestAdminRecipients {

    /** A club admin to notify: SSE target {@code sub} + mail address + locale. */
    record AdminRecipient(UUID sub, String email, String locale) {}

    private final UserDirectoryPort directory;
    private final UserRepository users;
    private final LanguageRepository languages;

    JoinRequestAdminRecipients(UserDirectoryPort directory,
                               UserRepository users,
                               LanguageRepository languages) {
        this.directory = directory;
        this.users = users;
        this.languages = languages;
    }

    List<AdminRecipient> forClub(UUID clubId) {
        Set<UUID> adminSubs = new HashSet<>();
        for (UserDirectoryRow row : directory.findUsersByRoleName(Role.CLUB_ADMINISTRATOR.name())) {
            adminSubs.add(row.id());
        }
        List<AdminRecipient> recipients = new ArrayList<>();
        for (ListRow row : users.findActiveInClub(clubId)) {
            UUID sub = row.keycloakSub();
            if (sub != null && adminSubs.contains(sub)) {
                recipients.add(new AdminRecipient(sub, row.notificationEmail(), localeOf(row)));
            }
        }
        return recipients;
    }

    private String localeOf(ListRow row) {
        return languages.findById(row.languageId())
                .map(language -> language.getCode())
                .orElse("");
    }
}
