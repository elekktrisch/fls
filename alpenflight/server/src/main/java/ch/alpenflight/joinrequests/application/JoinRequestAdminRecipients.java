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

@Component
class JoinRequestAdminRecipients {

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
