package ch.alpenflight.accounting.application;

import ch.alpenflight.accounting.application.DeliveryCreationTestDtos.DeliveryCreationTestDetail;
import ch.alpenflight.accounting.application.DeliveryCreationTestDtos.DeliveryCreationTestListItem;
import ch.alpenflight.accounting.application.DeliveryCreationTestDtos.DeliveryCreationTestWriteRequest;
import ch.alpenflight.accounting.application.DeliveryCreationTestDtos.ExampleDeliveryResult;
import ch.alpenflight.accounting.application.DeliveryCreationTestDtos.RunTestResult;
import ch.alpenflight.accounting.domain.DeliveryCreationTest;
import ch.alpenflight.accounting.domain.DeliveryCreationTestRepository;
import ch.alpenflight.accounting.domain.DeliveryDetailsSnapshot;
import ch.alpenflight.accounting.domain.DeliveryDiff;
import ch.alpenflight.accounting.domain.IgnoreFlags;
import ch.alpenflight.accounting.domain.RuleBasedDeliveryDetails;
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
    private final AccountingDeliveryEngine engine;
    private final ClubTenantIdentifierResolver tenantResolver;
    private final Clock clock;
    private final AuditTrail auditTrail;

    public DeliveryCreationTestsService(DeliveryCreationTestRepository tests,
                                        AccountingDeliveryEngine engine,
                                        ClubTenantIdentifierResolver tenantResolver,
                                        Clock clock,
                                        AuditTrail auditTrail) {
        this.tests = tests;
        this.engine = engine;
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
        captureExpectedIfSupplied(test, req);
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
        captureExpectedIfSupplied(test, req);
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

    /**
     * Dry-runs the engine for a flight and returns the would-be delivery WITHOUT
     * persisting anything (legacy {@code generateExampleDelivery}) — the SPA fills
     * a new harness's expected set from this. A missing / cross-tenant flight is
     * invisible under {@code @TenantId} → {@code FlightNotFoundException} (404).
     */
    @Transactional(readOnly = true)
    public ExampleDeliveryResult exampleDeliveryForFlight(UUID flightId) {
        RuleBasedDeliveryDetails computed = engine.computeForFlight(flightId);
        return new ExampleDeliveryResult(
                DeliveryDetailsSnapshot.of(computed),
                computed.matchedFilterIdsInOrder());
    }

    /**
     * Runs the engine against the harness's stored flight, diffs the output
     * against the expected set (gated by the nine {@link IgnoreFlags} +
     * {@code mustNotCreateDeliveryForFlight}), records the run-state on the
     * aggregate and returns the result. A MUTATION → audited.
     */
    public RunTestResult runTest(UUID id) {
        DeliveryCreationTest test = loadOrThrow(id);
        DeliveryCreationTestDetail before = toDetail(test);

        RuleBasedDeliveryDetails computed = engine.computeForFlight(requireFlightId(test));
        DeliveryDetailsSnapshot created = DeliveryDetailsSnapshot.of(computed);
        List<UUID> matchedIds = computed.matchedFilterIdsInOrder();

        DeliveryDiff.Result diff = DeliveryDiff.compare(
                test.getExpectedDelivery(),
                created,
                test.isMustNotCreateDeliveryForFlight(),
                test.getIgnoreFlags());

        test.recordRun(diff.successful(), diff.message(), created, matchedIds, clock.instant());
        DeliveryCreationTest saved = persist(test);

        auditTrail.record(AuditAction.UPDATE,
                AuditedTarget.updated(AUDIT_ENTITY_TYPE, id, before, toDetail(saved)));

        return new RunTestResult(diff.successful(), diff.message(), created, matchedIds);
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

    /**
     * Persists the captured dry-run output as the harness's expected set when the
     * write request carries one. A harness saved without a prior dry-run omits the
     * payload (legal) — its expected set then stays whatever it already held, so a
     * round-trip without re-capturing never clears a previously captured set.
     */
    private static void captureExpectedIfSupplied(DeliveryCreationTest test,
                                                  DeliveryCreationTestWriteRequest req) {
        if (req.expectedDelivery() == null) {
            return;
        }
        List<UUID> matchedIds = req.expectedMatchedFilterIds() == null
                ? List.of()
                : req.expectedMatchedFilterIds();
        test.captureExpected(req.expectedDelivery(), matchedIds);
    }

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
