package ch.alpenflight.audit.web;

import static org.assertj.core.api.Assertions.assertThat;

import ch.alpenflight.audit.application.AuditEventDtos.AuditEventRow;
import ch.alpenflight.audit.application.ClientIpRetentionJob;
import ch.alpenflight.audit.domain.AuditActorKind;
import ch.alpenflight.audit.domain.MutationAuditEvent;
import ch.alpenflight.platform.scheduling.MeasuredJob;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.lang.reflect.RecordComponent;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.yaml.snakeyaml.Yaml;

class PrivacyNoticeMatchesTheShippedBehaviourTest {

    private static final String NOTICE_REPO_PATH = "docs/modernization/privacy-notice.md";

    private static final String VERBATIM_LICENCE_FIELD_HEADING =
            "### The fields that stay verbatim";

    private static final Set<String> YES_NO_FLAG_PREFIXES = Set.of("has", "receive");

    private static final Pattern DAY_COUNT = Pattern.compile("(\\d+)\\s+days");

    private static final Pattern BULLETED_FIELD_NAME = Pattern.compile("^-\\s+`([A-Za-z0-9]+)`\\s*$");

    private static final Pattern CRON_FIELD_SEPARATOR = Pattern.compile("\\s+");

    private final String notice = readNotice();

    @Test
    void every_day_count_in_the_notice_equals_the_client_ip_retention_window() {
        long windowInDays = MutationAuditEvent.CLIENT_IP_RETENTION.toDays();

        List<String> dayCounts = new ArrayList<>();
        Matcher matcher = DAY_COUNT.matcher(notice);
        while (matcher.find()) {
            dayCounts.add(matcher.group(1));
        }

        assertThat(dayCounts)
                .as("%s must state the retention window at least once", NOTICE_REPO_PATH)
                .isNotEmpty();
        assertThat(dayCounts)
                .as("%s claims a window the aggregate does not enforce — "
                        + "MutationAuditEvent.CLIENT_IP_RETENTION is %d days",
                        NOTICE_REPO_PATH, windowInDays)
                .containsOnly(String.valueOf(windowInDays));
    }

    @Test
    void the_notice_names_the_retention_job_that_the_scheduler_runs_every_day() {
        MeasuredJob job = ClientIpRetentionJob.class.getAnnotation(MeasuredJob.class);
        assertThat(job).isNotNull();

        String[] cronFields = CRON_FIELD_SEPARATOR.split(job.cronShownInConsole().trim());
        String dayOfMonthField = cronFields[3];
        String monthField = cronFields[4];
        String dayOfWeekField = cronFields[5];

        assertThat(notice)
                .as("%s must name the job that redacts a client IP", NOTICE_REPO_PATH)
                .contains(ClientIpRetentionJob.JOB_NAME);
        assertThat(List.of(dayOfMonthField, monthField, dayOfWeekField))
                .as("%s claims the job runs every day, but the cron is '%s'",
                        NOTICE_REPO_PATH, job.cronShownInConsole())
                .containsOnly("*");
    }

    @Test
    void the_notice_names_the_erasure_endpoint_that_the_controller_exposes() throws Exception {
        RequestMapping controllerMapping =
                AuditAdminController.class.getAnnotation(RequestMapping.class);
        Method erasure = AuditAdminController.class
                .getDeclaredMethod("redactAuditEventClientIp", UUID.class);
        DeleteMapping erasureMapping = erasure.getAnnotation(DeleteMapping.class);

        String erasurePath = controllerMapping.path()[0] + erasureMapping.value()[0];

        assertThat(notice)
                .as("%s must name the erasure endpoint exactly as the controller exposes it",
                        NOTICE_REPO_PATH)
                .contains("DELETE " + erasurePath);
    }

    @Test
    void the_notice_names_the_only_actor_kind_that_may_carry_a_client_ip() {
        assertThat(notice)
                .as("%s must name the actor kind that carries a client IP", NOTICE_REPO_PATH)
                .contains(AuditActorKind.ANONYMOUS_PUBLIC.name());
    }

    @Test
    void the_audit_projection_returns_no_client_ip_as_the_notice_states() {
        List<String> projectedFields = Arrays.stream(AuditEventRow.class.getRecordComponents())
                .map(RecordComponent::getName)
                .toList();

        assertThat(projectedFields)
                .as("%s states that no API endpoint returns the address", NOTICE_REPO_PATH)
                .noneMatch(field -> field.toLowerCase(Locale.ROOT).contains("clientip"));
    }

    @Test
    void the_notice_lists_every_licence_value_the_audit_trail_keeps_verbatim() throws IOException {
        Set<String> keptVerbatim = new LinkedHashSet<>();
        for (String allowed : personLicencesAllowList()) {
            if (YES_NO_FLAG_PREFIXES.stream().noneMatch(allowed::startsWith)) {
                keptVerbatim.add(allowed);
            }
        }

        assertThat(noticedVerbatimLicenceFields())
                .as("%s must list every PersonLicences field that lands verbatim in the audit "
                        + "trail — audit.redaction.entities.PersonLicences.allow decides the set",
                        NOTICE_REPO_PATH)
                .containsExactlyInAnyOrderElementsOf(keptVerbatim);
    }

    private Set<String> noticedVerbatimLicenceFields() {
        Set<String> listed = new LinkedHashSet<>();
        boolean insideSection = false;
        for (String line : notice.lines().toList()) {
            if (line.startsWith(VERBATIM_LICENCE_FIELD_HEADING)) {
                insideSection = true;
                continue;
            }
            if (!insideSection) {
                continue;
            }
            Matcher field = BULLETED_FIELD_NAME.matcher(line);
            if (field.matches()) {
                listed.add(field.group(1));
            } else if (line.startsWith("#")) {
                break;
            }
        }
        return listed;
    }

    @SuppressWarnings("unchecked")
    private static List<String> personLicencesAllowList() throws IOException {
        try (InputStream in = PrivacyNoticeMatchesTheShippedBehaviourTest.class
                .getResourceAsStream("/application.yml")) {
            assertThat(in).as("application.yml on the test classpath").isNotNull();
            Map<String, Object> root = new Yaml().load(in);
            Map<String, Object> audit = (Map<String, Object>) root.get("audit");
            Map<String, Object> redaction = (Map<String, Object>) audit.get("redaction");
            Map<String, Object> entities = (Map<String, Object>) redaction.get("entities");
            Map<String, Object> personLicences = (Map<String, Object>) entities.get("PersonLicences");
            return (List<String>) personLicences.get("allow");
        }
    }

    private static String readNotice() {
        try {
            return Files.readString(locateNotice(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new AssertionError("Cannot read " + NOTICE_REPO_PATH, e);
        }
    }

    private static Path locateNotice() {
        Path probe = Path.of("").toAbsolutePath();
        while (probe != null) {
            Path candidate = probe.resolve(NOTICE_REPO_PATH);
            if (Files.exists(candidate)) {
                return candidate;
            }
            probe = probe.getParent();
        }
        throw new AssertionError(NOTICE_REPO_PATH + " not found above "
                + Path.of("").toAbsolutePath());
    }
}
