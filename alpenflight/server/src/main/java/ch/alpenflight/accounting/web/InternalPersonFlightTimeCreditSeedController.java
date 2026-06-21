package ch.alpenflight.accounting.web;

import ch.alpenflight.accounting.application.PersonFlightTimeCreditSeedService;
import ch.alpenflight.accounting.application.PersonFlightTimeCreditSeedService.CreditView;
import ch.alpenflight.accounting.application.PersonFlightTimeCreditSeedService.GrantCommand;
import io.swagger.v3.oas.annotations.Hidden;
import jakarta.validation.constraints.NotNull;
import java.net.URI;
import java.time.Instant;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * Seed affordance for the flight-time-credit e2e: there is no production
 * credit-CRUD surface, so the clean-seed parity spec grants a
 * {@code PersonFlightTimeCredit} (its single {@code IsCurrent} transaction)
 * through this endpoint, matched to a freshly-minted flight immatriculation that
 * no static SQL seed could reference. Mirrors {@code InternalProvisioningController}:
 * {@code @Profile({"dev","test"})} keeps the bean out of production (the real-idp
 * e2e backend boots {@code dev}); {@code @Hidden} keeps it out of the OpenAPI
 * snapshot; the {@code /internal/} prefix lets a future gateway deny it wholesale.
 * CLUB_ADMINISTRATOR-gated; the audited mutation lives in the application service.
 */
@RestController
@RequestMapping(path = "/api/v1/internal/person-flight-time-credits")
@Profile({"dev", "test"})
@Hidden
class InternalPersonFlightTimeCreditSeedController {

    private final PersonFlightTimeCreditSeedService service;

    InternalPersonFlightTimeCreditSeedController(PersonFlightTimeCreditSeedService service) {
        this.service = service;
    }

    @PostMapping
    @PreAuthorize("hasRole('CLUB_ADMINISTRATOR')")
    ResponseEntity<CreditView> grant(@RequestBody GrantRequest req) {
        CreditView created = service.grant(new GrantCommand(
                req.personId(),
                Boolean.TRUE.equals(req.noFlightTimeLimit()),
                Boolean.TRUE.equals(req.useRuleForAllAircraftsExceptListed()),
                req.matchedAircraftImmatriculations(),
                req.discountInPercent() == null ? 0 : req.discountInPercent(),
                req.validUntil(),
                req.currentFlightTimeBalanceInSeconds()));
        return ResponseEntity
                .created(URI.create("/api/v1/internal/person-flight-time-credits/" + created.id()))
                .body(created);
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasRole('CLUB_ADMINISTRATOR')")
    CreditView read(@PathVariable UUID id) {
        return service.find(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "no credit: " + id));
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasRole('CLUB_ADMINISTRATOR')")
    ResponseEntity<Void> remove(@PathVariable UUID id) {
        service.remove(id);
        return ResponseEntity.noContent().build();
    }

    record GrantRequest(
            @NotNull UUID personId,
            @Nullable Boolean noFlightTimeLimit,
            @Nullable Boolean useRuleForAllAircraftsExceptListed,
            @Nullable String matchedAircraftImmatriculations,
            @Nullable Integer discountInPercent,
            @Nullable Instant validUntil,
            @Nullable Long currentFlightTimeBalanceInSeconds) {}
}
