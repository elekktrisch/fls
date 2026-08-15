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
import ch.alpenflight.platform.id.FlightId;
import ch.alpenflight.platform.tenancy.ClubTenantIdentifierResolver;
import java.time.Clock;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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
                req.flightId().value(),
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
                req.flightId().value(),
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

    @Transactional(readOnly = true)
    public ExampleDeliveryResult exampleDeliveryForFlight(UUID flightId) {
        RuleBasedDeliveryDetails computed = engine.computeForFlight(flightId);
        return new ExampleDeliveryResult(
                DeliveryDetailsSnapshot.of(computed),
                computed.matchedFilterIdsInOrder());
    }

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


    private DeliveryCreationTest loadOrThrow(UUID id) {
        return tests.findActiveById(id)
                .orElseThrow(() -> new DeliveryCreationTestNotFoundException(id));
    }

    private DeliveryCreationTest persist(DeliveryCreationTest test) {
        DeliveryCreationTest saved = tests.save(test);
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


    private static DeliveryCreationTestDetail toDetail(DeliveryCreationTest test) {
        IgnoreFlags flags = test.getIgnoreFlags();
        return new DeliveryCreationTestDetail(
                requireId(test),
                FlightId.of(requireFlightId(test)),
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
                FlightId.of(requireFlightId(test)),
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

    private static boolean orDefault(@Nullable Boolean value, boolean fallback) {
        return value == null ? fallback : value;
    }
}
