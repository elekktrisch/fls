package ch.alpenflight.accounting.application;

import ch.alpenflight.accounting.application.DeliveryCreationTestDtos.DeliveryCreationTestDetail;
import ch.alpenflight.accounting.application.DeliveryCreationTestDtos.DeliveryCreationTestListItem;
import ch.alpenflight.accounting.application.DeliveryCreationTestDtos.DeliveryCreationTestWriteRequest;
import ch.alpenflight.accounting.domain.DeliveryCreationTest;
import ch.alpenflight.accounting.domain.DeliveryCreationTestRepository;
import ch.alpenflight.accounting.domain.IgnoreFlags;
import ch.alpenflight.audit.domain.AuditAction;
import ch.alpenflight.audit.domain.AuditTrail;
import ch.alpenflight.audit.domain.AuditedTarget;
import ch.alpenflight.platform.tenancy.ClubTenantIdentifierResolver;
import java.time.Clock;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Transactional service for the {@link DeliveryCreationTest} aggregate (J-9, the
 * rules-engine regression harness). Tenant scoping (ADR 0008) is structural via
 * Hibernate's {@code @TenantId} discriminator on
 * {@code DeliveryCreationTest.operatingClubId}; role-within-tenant gates live on
 * the controller as {@code @PreAuthorize}.
 *
 * <p>The service <em>orchestrates</em>; it does not re-implement domain rules
 * (ADR 0022 §2 — testName non-blank + flightId present are re-validated by the
 * aggregate's {@code create}/{@code update}). What lives here is wire-shape
 * translation:
 *
 * <ul>
 *   <li><b>Optional-boolean coercion</b>: the write request carries
 *       {@code @Nullable Boolean} for every flag the SPA may omit; the service
 *       coerces each to its legacy default ({@code active} → true, the rest →
 *       false) and assembles the {@link IgnoreFlags} VO before the aggregate sees
 *       primitives.</li>
 *   <li><b>Tenant stamp</b>: {@link DeliveryCreationTest#create} needs the
 *       operating club as an explicit param (its child items denormalize it), so
 *       create resolves the current tenant; Hibernate re-confirms it on INSERT.</li>
 *   <li><b>Cross-tenant 404</b>: every read/mutate loads via
 *       {@code findActiveById}, which the {@code @TenantId} filter scopes to the
 *       caller's club — a cross-tenant id is invisible →
 *       {@link DeliveryCreationTestNotFoundException}.</li>
 * </ul>
 *
 * <p>The dry-run / run-test endpoints + diff are T-15; they extend this
 * service/controller. The detail DTO already round-trips the captured
 * {@code expectedDelivery} + the {@code lastTest*} run-state read-only.
 *
 * <p>Mutations emit {@link AuditAction#CREATE} / {@link AuditAction#UPDATE} /
 * {@link AuditAction#DELETE} via {@link AuditTrail} (every rules-config change
 * drives every subsequent invoice). The two jsonb delivery payloads are redacted
 * in the snapshot ({@code @AuditRedact} on the entity fields).
 */
@Service
@Transactional
public class DeliveryCreationTestsService {

    private static final String AUDIT_ENTITY_TYPE = "DeliveryCreationTest";

    private final DeliveryCreationTestRepository tests;
    private final ClubTenantIdentifierResolver tenantResolver;
    private final Clock clock;
    private final AuditTrail auditTrail;

    public DeliveryCreationTestsService(DeliveryCreationTestRepository tests,
                                        ClubTenantIdentifierResolver tenantResolver,
                                        Clock clock,
                                        AuditTrail auditTrail) {
        this.tests = tests;
        this.tenantResolver = tenantResolver;
        this.clock = clock;
        this.auditTrail = auditTrail;
    }

    @Transactional(readOnly = true)
    public List<DeliveryCreationTestListItem> listTests() {
        return tests.findAllActiveOrderedByName().stream()
                .map(DeliveryCreationTestsService::toListItem)
                .toList();
    }

    @Transactional(readOnly = true)
    public DeliveryCreationTestDetail getDetail(UUID id) {
        return toDetail(loadOrThrow(id));
    }

    public DeliveryCreationTestDetail create(DeliveryCreationTestWriteRequest req) {
        DeliveryCreationTest test = DeliveryCreationTest.create(
                resolveTenantOrThrow(),
                req.flightId(),
                req.testName(),
                req.description(),
                orDefault(req.active(), true),
                orDefault(req.mustNotCreateDeliveryForFlight(), false),
                ignoreFlagsOf(req));
        DeliveryCreationTestDetail created = toDetail(persist(test));
        auditTrail.record(AuditAction.CREATE,
                AuditedTarget.created(AUDIT_ENTITY_TYPE, created.id(), created));
        return created;
    }

    public DeliveryCreationTestDetail update(UUID id, DeliveryCreationTestWriteRequest req) {
        DeliveryCreationTest test = loadOrThrow(id);
        DeliveryCreationTestDetail before = toDetail(test);
        test.update(
                req.flightId(),
                req.testName(),
                req.description(),
                orDefault(req.active(), true),
                orDefault(req.mustNotCreateDeliveryForFlight(), false),
                ignoreFlagsOf(req));
        DeliveryCreationTestDetail after = toDetail(persist(test));
        auditTrail.record(AuditAction.UPDATE,
                AuditedTarget.updated(AUDIT_ENTITY_TYPE, id, before, after));
        return after;
    }

    public void delete(UUID id, @Nullable UUID userId) {
        DeliveryCreationTest test = loadOrThrow(id);
        DeliveryCreationTestDetail before = toDetail(test);
        test.softDelete(userId, clock);
        tests.save(test);
        auditTrail.record(AuditAction.DELETE,
                AuditedTarget.deleted(AUDIT_ENTITY_TYPE, id, before));
    }

    // -- loading / persistence --------------------------------------------------

    private DeliveryCreationTest loadOrThrow(UUID id) {
        return tests.findActiveById(id)
                .orElseThrow(() -> new DeliveryCreationTestNotFoundException(id));
    }

    private DeliveryCreationTest persist(DeliveryCreationTest test) {
        DeliveryCreationTest saved = tests.save(test);
        // Flush so the partial-UNIQUE (ux_dct_club_flight_partial) race surfaces
        // synchronously here rather than at tx commit.
        tests.flush();
        return saved;
    }

    private UUID resolveTenantOrThrow() {
        UUID tenant = tenantResolver.resolveCurrentTenantIdentifier();
        if (ClubTenantIdentifierResolver.NO_TENANT.equals(tenant)) {
            throw new IllegalStateException(
                    "DeliveryCreationTest.create requires a tenant context; unscoped caller cannot create a harness");
        }
        return tenant;
    }

    // -- request → VO -----------------------------------------------------------

    private static IgnoreFlags ignoreFlagsOf(DeliveryCreationTestWriteRequest req) {
        return new IgnoreFlags(
                orDefault(req.ignoreRecipientName(), false),
                orDefault(req.ignoreRecipientAddress(), false),
                orDefault(req.ignoreRecipientPersonId(), false),
                orDefault(req.ignoreRecipientClubMemberNumber(), false),
                orDefault(req.ignoreDeliveryInformation(), false),
                orDefault(req.ignoreAdditionalInformation(), false),
                orDefault(req.ignoreItemPositioning(), false),
                orDefault(req.ignoreItemText(), false),
                orDefault(req.ignoreItemAdditionalInformation(), false));
    }

    // -- aggregate → DTO --------------------------------------------------------

    private static DeliveryCreationTestDetail toDetail(DeliveryCreationTest test) {
        IgnoreFlags flags = test.getIgnoreFlags();
        return new DeliveryCreationTestDetail(
                requireId(test),
                requireFlightId(test),
                test.getTestName(),
                test.getDescription(),
                test.isActive(),
                test.isMustNotCreateDeliveryForFlight(),
                flags.recipientName(),
                flags.recipientAddress(),
                flags.recipientPersonId(),
                flags.recipientClubMemberNumber(),
                flags.deliveryInformation(),
                flags.additionalInformation(),
                flags.itemPositioning(),
                flags.itemText(),
                flags.itemAdditionalInformation(),
                test.getExpectedDelivery(),
                test.getExpectedMatchedFilterIds(),
                test.getLastTestSuccessful(),
                test.getLastTestResultMessage(),
                test.getLastTestRunOn(),
                test.getLastTestCreatedDelivery(),
                test.getLastTestMatchedFilterIds());
    }

    private static DeliveryCreationTestListItem toListItem(DeliveryCreationTest test) {
        return new DeliveryCreationTestListItem(
                requireId(test),
                test.getTestName(),
                requireFlightId(test),
                test.isActive(),
                test.getLastTestSuccessful(),
                test.getLastTestRunOn());
    }

    private static UUID requireId(DeliveryCreationTest test) {
        return Objects.requireNonNull(test.getId(),
                "DeliveryCreationTest.id must be non-null after persist");
    }

    private static UUID requireFlightId(DeliveryCreationTest test) {
        return Objects.requireNonNull(test.getFlightId(),
                "DeliveryCreationTest.flightId must be non-null");
    }

    /** Coerce an optional wire boolean to its default when the field was omitted (null). */
    private static boolean orDefault(@Nullable Boolean value, boolean fallback) {
        return value == null ? fallback : value;
    }
}
