USE [FLSTest]
GO

PRINT '=== DETERMINISTIC TEST FIXTURE: _test-fixture.sql ==='

-- ---------------------------------------------------------------------------
-- All timestamps in this script are derived from a single anchor so that
-- time-gated flight states (Valid -> Locked after >=2 days,
-- Locked -> DeliveryPrepared after >=3 more days) remain reachable from a
-- fresh seed. Pick an anchor well in the past relative to the historical
-- flight we insert so the flight ages naturally regardless of wall-clock.
--
-- See SERVER.md sec. 2 for the state-machine time gates.
-- ---------------------------------------------------------------------------
DECLARE @anchor datetime2 = '2026-01-01T00:00:00'

-- ---------------------------------------------------------------------------
-- Fixed reference data already created by the earlier seed files.
-- We re-bind their GUIDs as local variables so the rest of the script reads
-- cleanly and we can sanity-check existence up front.
-- ---------------------------------------------------------------------------
DECLARE @systemClubId  uniqueidentifier = 'A1DDE2CB-6326-4BB2-897D-7CFC118E842B'
DECLARE @testClubId    uniqueidentifier = '0FA7B76F-47BA-4138-8F96-671400FD7C83'
DECLARE @otherClubId   uniqueidentifier = 'F1500002-0000-0000-0000-000000000001'

DECLARE @systemAdminRoleId    uniqueidentifier = '56352545-2454-3453-2343-C74244653451'
DECLARE @clubAdminRoleId      uniqueidentifier = '92750A21-9BCD-FFFF-2343-23B44724019B'
DECLARE @flightOperatorRoleId uniqueidentifier = '187A8729-92BC-2932-AC83-15F14724019B'

DECLARE @insertUserId uniqueidentifier = '13731EE2-C1D8-455C-8AD1-C39399893FFF'  -- legacy 's' bootstrap user
DECLARE @testClubAdminId uniqueidentifier
SELECT @testClubAdminId = UserId FROM Users WHERE Username = 'testclubadmin'

DECLARE @switzerlandId uniqueidentifier = '77CC3BE6-95DB-11E0-B104-E7F04724019B'

DECLARE @lszk uniqueidentifier
SELECT @lszk = LocationId FROM Locations WHERE IcaoCode = 'LSZK'

-- Sanity-check that the prerequisites the earlier seed files were supposed to
-- create are in fact there; bail loud and early if not.
IF NOT EXISTS (SELECT 1 FROM Clubs WHERE ClubId = @testClubId)
    THROW 51000, 'Test-Club (TestClub) is missing - run "4 or 5 Insert Test Data.sql" first', 1
IF @testClubAdminId IS NULL
    THROW 51001, 'User testclubadmin is missing - run "4 or 5 Insert Test Data.sql" first', 1
IF @lszk IS NULL
    THROW 51002, 'LSZK location is missing - run "3 Insert Static Data.sql" first', 1

DECLARE @ownershipClub int = 2          -- OwnershipType=Club
DECLARE @recordState   int = 1

-- ---------------------------------------------------------------------------
-- 1. Second club ("othertestclub") for multi-tenancy isolation tests.
-- ---------------------------------------------------------------------------
PRINT 'Fixture: othertestclub'
IF NOT EXISTS (SELECT 1 FROM Clubs WHERE ClubId = @otherClubId)
BEGIN
    INSERT INTO Clubs (
        ClubID, ClubName, ClubKey, Address, Zip, City, CountryId,
        Phone, FaxNumber, Email, WebPage, Contact,
        CreatedOn, CreatedByUserId, RecordState,
        OwnerId, OwnershipType, ClubStateId,
        SendDeliveryMailExportTo, SendPlanningDayInfoMailTo
    ) VALUES (
        @otherClubId, 'Other-Test-Club', 'OtherClub',
        'Flugplatz Other', '6666', 'Bern', @switzerlandId,
        '044 111 22 33', '044 111 22 34', 'info@other.ch', 'www.other.ch', 'Sekretariat Other',
        DATEADD(MINUTE, 1, @anchor), @insertUserId, @recordState,
        @systemClubId, @ownershipClub, 1,
        'other@glider-fls.ch', 'other@glider-fls.ch'
    )
END

-- ---------------------------------------------------------------------------
-- 2. Admin user for the second club.
-- Reuse the same PasswordHash + a fixed SecurityStamp as testclubadmin so the
-- "password is the letter s" convention from "4 or 5 Insert Test Data.sql"
-- carries over.
-- ---------------------------------------------------------------------------
DECLARE @otherAdminUserId uniqueidentifier = 'F1500002-0000-0000-0000-0000000000A1'
DECLARE @otherAdminPersonId uniqueidentifier = 'F1500002-0000-0000-0000-0000000000B1'

PRINT 'Fixture: othertestadmin user + person'
IF NOT EXISTS (SELECT 1 FROM Persons WHERE PersonId = @otherAdminPersonId)
BEGIN
    INSERT INTO Persons (
        PersonId, Lastname, Firstname, CountryId,
        HasMotorPilotLicence, HasTowPilotLicence, HasGliderInstructorLicence,
        HasGliderPilotLicence, HasGliderTraineeLicence,
        CreatedOn, CreatedByUserId, RecordState, OwnerId, OwnershipType, IsFastEntryRecord
    ) VALUES (
        @otherAdminPersonId, 'Otheradmin', 'Other', @switzerlandId,
        0, 0, 0, 0, 0,
        DATEADD(MINUTE, 2, @anchor), @insertUserId, @recordState, @otherClubId, @ownershipClub, 0
    )
END

IF NOT EXISTS (SELECT 1 FROM PersonClub WHERE PersonId = @otherAdminPersonId AND ClubId = @otherClubId)
BEGIN
    INSERT INTO PersonClub (
        PersonId, ClubId, MemberNumber,
        IsMotorPilot, IsTowPilot, IsGliderInstructor, IsGliderPilot, IsGliderTrainee,
        CreatedOn, CreatedByUserId, RecordState, OwnerId, OwnershipType,
        IsPassenger, IsWinchOperator
    ) VALUES (
        @otherAdminPersonId, @otherClubId, '900001',
        0, 0, 0, 0, 0,
        DATEADD(MINUTE, 2, @anchor), @insertUserId, @recordState, @otherClubId, @ownershipClub,
        0, 0
    )
END

IF NOT EXISTS (SELECT 1 FROM Users WHERE UserId = @otherAdminUserId)
BEGIN
    INSERT INTO Users (
        UserId, ClubId, Username, FriendlyName,
        PasswordHash, EmailConfirmed, SecurityStamp,
        PersonId, NotificationEmail, AccountState,
        CreatedOn, CreatedByUserId, RecordState, OwnerId, OwnershipType
    ) VALUES (
        @otherAdminUserId, @otherClubId, 'othertestadmin', 'Other Test Admin',
        'AG3i8UWZYzlQMoA7jS58oJCWKJUhe+MR6nInBRAHfFc2YtoL+eiOuTYZd46urgf+ZA==',
        1, 'F1500002-0000-0000-0000-0000000000C1',
        @otherAdminPersonId, 'other@glider-fls.ch', 1,
        DATEADD(MINUTE, 2, @anchor), @insertUserId, @recordState, @otherClubId, @ownershipClub
    )
END

IF NOT EXISTS (SELECT 1 FROM UserRoles WHERE UserId = @otherAdminUserId AND RoleId = @clubAdminRoleId)
BEGIN
    INSERT INTO UserRoles (
        UserId, RoleId, CreatedOn, CreatedByUserId,
        OwnerId, OwnershipType, RecordState, IsDeleted
    ) VALUES (
        @otherAdminUserId, @clubAdminRoleId,
        DATEADD(MINUTE, 2, @anchor), @insertUserId,
        @otherClubId, @ownershipClub, @recordState, 0
    )
END

-- ---------------------------------------------------------------------------
-- 3. PersonCategory rows for the test club (table is empty in stock seed).
--    Deterministic DELETE+INSERT to keep results stable across reruns.
-- ---------------------------------------------------------------------------
PRINT 'Fixture: PersonCategories'
DELETE FROM PersonCategories WHERE ClubId IN (@testClubId, @otherClubId)

INSERT INTO PersonCategories (
    PersonCategoryId, ClubId, CategoryName, Remarks, ParentPersonCategoryId,
    CreatedOn, CreatedByUserId, RecordState, OwnerId, OwnershipType, IsDeleted
) VALUES
    ('F1500003-0000-0000-0000-000000000001', @testClubId, 'Vorstand',  'Vorstandsmitglieder', NULL,
        DATEADD(MINUTE, 3, @anchor), @insertUserId, @recordState, @testClubId, @ownershipClub, 0),
    ('F1500003-0000-0000-0000-000000000002', @testClubId, 'Fluglehrer','Aktive Fluglehrer',  NULL,
        DATEADD(MINUTE, 3, @anchor), @insertUserId, @recordState, @testClubId, @ownershipClub, 0),
    ('F1500003-0000-0000-0000-000000000003', @testClubId, 'Gaeste',    'Externe Gaeste',     NULL,
        DATEADD(MINUTE, 3, @anchor), @insertUserId, @recordState, @testClubId, @ownershipClub, 0)

-- ---------------------------------------------------------------------------
-- 4. AccountingRuleFilters for the test club. Covers Recipient(10),
--    FlightTime(30), and LandingTax(60) -- three of the rule-type values
--    documented in FLS.Data.WebApi/Accounting/RuleFilters/AccountingRuleFilterType.cs.
-- ---------------------------------------------------------------------------
PRINT 'Fixture: AccountingRuleFilters (testclub)'
DELETE FROM AccountingRuleFilters WHERE ClubId = @testClubId AND AccountingRuleFilterId IN (
    'F1500004-0000-0000-0000-000000000001',
    'F1500004-0000-0000-0000-000000000002',
    'F1500004-0000-0000-0000-000000000003'
)

-- 4a. Recipient rule (type 10) -- routes invoices to the flight's pilot.
INSERT INTO AccountingRuleFilters (
    AccountingRuleFilterId, ClubId, AccountingRuleFilterTypeId,
    RuleFilterName, Description, IsActive, SortIndicator,
    ArticleTarget, RecipientTarget,
    IsRuleForGliderFlights, IsRuleForTowingFlights, IsRuleForMotorFlights,
    UseRuleForAllAircraftsExceptListed, MatchedAircraftImmatriculations,
    UseRuleForAllFlightTypesExceptListed, MatchedFlightTypeCodes,
    ExtendMatchingFlightTypeCodesToGliderAndTowFlight,
    UseRuleForAllStartLocationsExceptListed, MatchedStartLocations,
    UseRuleForAllLdgLocationsExceptListed,   MatchedLdgLocations,
    UseRuleForAllClubMemberNumbersExceptListed, MatchedClubMemberNumbers,
    UseRuleForAllFlightCrewTypesExceptListed,   MatchedFlightCrewTypes,
    UseRuleForAllStartTypesExceptListed,        MatchedStartTypes,
    UseRuleForAllAircraftsOnHomebaseExceptListed,
    UseRuleForAllMemberStatesExceptListed,
    UseRuleForAllPersonCategoriesExceptListed,
    StopRuleEngineWhenRuleApplied,
    CreatedOn, CreatedByUserId, RecordState,
    OwnerId, OwnershipType, IsDeleted
) VALUES (
    'F1500004-0000-0000-0000-000000000001', @testClubId, 10,
    'Recipient: Flight Pilot', 'Routes invoice to the pilot of the flight (test fixture)', 1, 10,
    NULL, N'{"RecipientType":"FlightCrew","MatchedFlightCrewTypes":[1]}',
    1, 1, 1,
    1, N'[]',
    1, N'[]',
    0,
    1, N'[]',
    1, N'[]',
    1, N'[]',
    1, N'[]',
    1, N'[]',
    1, 1, 1,
    0,
    DATEADD(MINUTE, 4, @anchor), @insertUserId, @recordState,
    @testClubId, @ownershipClub, 0
)

-- 4b. FlightTime rule (type 30) -- chunked per-minute billing for gliders.
INSERT INTO AccountingRuleFilters (
    AccountingRuleFilterId, ClubId, AccountingRuleFilterTypeId,
    RuleFilterName, Description, IsActive, SortIndicator,
    ArticleTarget, RecipientTarget,
    IsRuleForGliderFlights, IsRuleForTowingFlights, IsRuleForMotorFlights,
    UseRuleForAllAircraftsExceptListed, MatchedAircraftImmatriculations,
    UseRuleForAllFlightTypesExceptListed, MatchedFlightTypeCodes,
    ExtendMatchingFlightTypeCodesToGliderAndTowFlight,
    UseRuleForAllStartLocationsExceptListed, MatchedStartLocations,
    UseRuleForAllLdgLocationsExceptListed,   MatchedLdgLocations,
    UseRuleForAllClubMemberNumbersExceptListed, MatchedClubMemberNumbers,
    UseRuleForAllFlightCrewTypesExceptListed,   MatchedFlightCrewTypes,
    UseRuleForAllStartTypesExceptListed,        MatchedStartTypes,
    UseRuleForAllAircraftsOnHomebaseExceptListed,
    UseRuleForAllMemberStatesExceptListed,
    UseRuleForAllPersonCategoriesExceptListed,
    StopRuleEngineWhenRuleApplied,
    MinFlightTimeInSecondsMatchingValue, MaxFlightTimeInSecondsMatchingValue,
    AccountingUnitTypeId,
    CreatedOn, CreatedByUserId, RecordState,
    OwnerId, OwnershipType, IsDeleted
) VALUES (
    'F1500004-0000-0000-0000-000000000002', @testClubId, 30,
    'FlightTime: Glider per minute',
    'Billed per glider minute (test fixture)', 1, 20,
    N'{"ArticleNumber":"5001","DeliveryLineText":"Glider flight minutes"}', NULL,
    1, 0, 0,
    1, N'[]',
    1, N'[]',
    0,
    1, N'[]',
    1, N'[]',
    1, N'[]',
    1, N'[]',
    1, N'[]',
    1, 1, 1,
    0,
    0, 2147483647,
    10,   -- AccountingUnitType "Min"
    DATEADD(MINUTE, 5, @anchor), @insertUserId, @recordState,
    @testClubId, @ownershipClub, 0
)

-- 4c. LandingTax rule (type 60) -- per-landing tax at LSZK.
INSERT INTO AccountingRuleFilters (
    AccountingRuleFilterId, ClubId, AccountingRuleFilterTypeId,
    RuleFilterName, Description, IsActive, SortIndicator,
    ArticleTarget, RecipientTarget,
    IsRuleForGliderFlights, IsRuleForTowingFlights, IsRuleForMotorFlights,
    UseRuleForAllAircraftsExceptListed, MatchedAircraftImmatriculations,
    UseRuleForAllFlightTypesExceptListed, MatchedFlightTypeCodes,
    ExtendMatchingFlightTypeCodesToGliderAndTowFlight,
    UseRuleForAllStartLocationsExceptListed, MatchedStartLocations,
    UseRuleForAllLdgLocationsExceptListed,   MatchedLdgLocations,
    UseRuleForAllClubMemberNumbersExceptListed, MatchedClubMemberNumbers,
    UseRuleForAllFlightCrewTypesExceptListed,   MatchedFlightCrewTypes,
    UseRuleForAllStartTypesExceptListed,        MatchedStartTypes,
    UseRuleForAllAircraftsOnHomebaseExceptListed,
    UseRuleForAllMemberStatesExceptListed,
    UseRuleForAllPersonCategoriesExceptListed,
    StopRuleEngineWhenRuleApplied,
    AccountingUnitTypeId,
    CreatedOn, CreatedByUserId, RecordState,
    OwnerId, OwnershipType, IsDeleted
) VALUES (
    'F1500004-0000-0000-0000-000000000003', @testClubId, 60,
    'LandingTax: LSZK', 'Per-landing tax at LSZK (test fixture)', 1, 30,
    N'{"ArticleNumber":"6001","DeliveryLineText":"Landegebuehr LSZK"}', NULL,
    1, 1, 1,
    1, N'[]',
    1, N'[]',
    0,
    1, N'[]',
    0, N'["LSZK"]',
    1, N'[]',
    1, N'[]',
    1, N'[]',
    1, 1, 1,
    0,
    30,   -- AccountingUnitType "Ldgs"
    DATEADD(MINUTE, 6, @anchor), @insertUserId, @recordState,
    @testClubId, @ownershipClub, 0
)

-- ---------------------------------------------------------------------------
-- 5. Historical flight dated @anchor - 30 days. Aged enough to be eligible
--    for Locked -> DeliveryPrepared via the DailyFlightValidationJob +
--    DeliveryCreationJob workflows (which require >=2 days and >=3 more days
--    respectively -- see SERVER.md sec. 2).
--    ProcessStateId 30 = Valid; AirStateId 20 = Landed.
-- ---------------------------------------------------------------------------
PRINT 'Fixture: Historical flight (testclub, anchor - 30 days)'
DECLARE @historicalFlightId uniqueidentifier = 'F1500005-0000-0000-0000-000000000001'
DECLARE @historicalStart datetime2 = DATEADD(DAY, -30, @anchor)
DECLARE @historicalLdg   datetime2 = DATEADD(MINUTE, 47, @historicalStart)

DECLARE @gliderAircraftId uniqueidentifier
SELECT TOP 1 @gliderAircraftId = AircraftId FROM Aircrafts
 WHERE AircraftOwnerClubId = @testClubId AND Immatriculation = 'HB-3407'

DECLARE @gliderFlightTypeId uniqueidentifier
SELECT TOP 1 @gliderFlightTypeId = FlightTypeId FROM FlightTypes
 WHERE ClubId = @testClubId AND FlightCode = '63'

DECLARE @gliderPilotPersonId uniqueidentifier
SELECT TOP 1 @gliderPilotPersonId = p.PersonId
  FROM Persons p
  INNER JOIN PersonClub pc ON pc.PersonId = p.PersonId
 WHERE pc.ClubId = @testClubId AND p.HasGliderPilotLicence = 1

DELETE FROM FlightCrew WHERE FlightId = @historicalFlightId
DELETE FROM Flights    WHERE FlightId = @historicalFlightId

IF @gliderAircraftId IS NOT NULL AND @gliderFlightTypeId IS NOT NULL AND @gliderPilotPersonId IS NOT NULL
BEGIN
    INSERT INTO Flights (
        FlightId, AircraftId,
        StartDateTime, LdgDateTime,
        StartLocationId, LdgLocationId,
        FlightTypeId, StartType, TowFlightId, NrOfLdgs,
        AirStateId, ProcessStateId, FlightAircraftType,
        Comment, IsSoloFlight,
        NoStartTimeInformation, NoLdgTimeInformation,
        CreatedOn, CreatedByUserId, RecordState,
        OwnerId, OwnershipType, IsDeleted, FlightDate
    ) VALUES (
        @historicalFlightId, @gliderAircraftId,
        @historicalStart, @historicalLdg,
        @lszk, @lszk,
        @gliderFlightTypeId, 3 /*Self-launch*/, NULL, 1,
        20 /*Landed*/, 30 /*Valid*/, 1 /*GliderFlight*/,
        'Historical fixture flight (anchor - 30d)', 0,
        0, 0,
        DATEADD(MINUTE, 7, @anchor), @insertUserId, @recordState,
        @testClubId, @ownershipClub, 0, CAST(@historicalStart AS DATE)
    )

    INSERT INTO FlightCrew (
        FlightCrewId, FlightId, PersonId, FlightCrewType,
        CreatedOn, CreatedByUserId, RecordState,
        OwnerId, OwnershipType
    ) VALUES (
        'F1500005-0000-0000-0000-0000000000A1',
        @historicalFlightId, @gliderPilotPersonId, 1 /*Pilot*/,
        DATEADD(MINUTE, 7, @anchor), @insertUserId, @recordState,
        @testClubId, @ownershipClub
    )
END

-- ---------------------------------------------------------------------------
-- 6. SystemData: point email at the Mailpit sidecar that task #16 will add.
--    Singleton row; we DELETE + INSERT to keep it deterministic.
-- ---------------------------------------------------------------------------
-- Notes (combined finding from email-tests and public-flows agents):
-- 1. SmtpServer is 'localhost' (not the docker alias 'mailpit') because the
--    FLS.Server.Console Mono host runs on the dev host, not inside the
--    docker network. Mailpit's container publishes 1025 on the host.
-- 2. UseSmtpAuthentication=1 with empty username/password forces the code
--    path SmtpClient.UseDefaultCredentials = false. Mono throws
--    NotImplementedException on the UseDefaultCredentials=true branch
--    (FLS.Server.Service/Email/EmailSendService.cs:63). Mailpit's
--    MP_SMTP_AUTH_ACCEPT_ANY=1 accepts empty credentials.
DELETE FROM SystemData
INSERT INTO SystemData (
    SystemId, BaseURL,
    ReportSenderEmailAddress, SystemSenderEmailAddress,
    SmtpUsername, SmtpPassword, SmtpServer, SmtpPort,
    MaxUserLoginAttempts, Testmode, DebugMode,
    SendToBccRecipients, BccRecipientEmailAddresses,
    UseSmtpAuthentication, UseSSLforSmtpConnection
) VALUES (
    'F1500006-0000-0000-0000-000000000001', N'http://localhost:25567',
    N'test@glider-fls.ch', N'test@glider-fls.ch',
    N'', N'', N'localhost', 1025,
    10, 0, 1,
    0, N'test@glider-fls.ch',
    1, 0
)

-- ---------------------------------------------------------------------------
-- 7. Email templates not present in the base seed (DBUpdate_v1.9.29 alter
--    adds them but is tolerant-skipped in seed.sh, so we re-add them here).
--    Tests #3/#4 (passenger flight registration) need these to exist.
-- ---------------------------------------------------------------------------
PRINT 'Fixture: passenger flight email templates'
IF NOT EXISTS (SELECT 1 FROM EmailTemplates WHERE EmailTemplateKeyName = 'PassengerFlightRegistrationEmailForPassenger' AND IsSystemTemplate = 1)
BEGIN
    INSERT INTO EmailTemplates (
        EmailTemplateId, ClubId, EmailTemplateName, EmailTemplateKeyName,
        Description, FromAddress, ReplyToAddresses, Subject,
        IsSystemTemplate, CreatedOn, CreatedByUserId,
        RecordState, OwnerId, OwnershipType, IsDeleted,
        HtmlBody, IsCustomizable, LanguageId
    ) VALUES (
        'F1500007-0000-0000-0000-000000000001', NULL,
        N'Passenger flight registration confirmation email for passenger',
        N'PassengerFlightRegistrationEmailForPassenger',
        N'Sends a registration confirmation email to the passenger.',
        N'fls@glider-fls.ch', N'noreply@glider-fls.ch',
        N'Bestätigung für Passagierflug-Registrierung',
        1, SYSUTCDATETIME(), @insertUserId,
        1, @systemClubId, @ownershipClub, 0,
        N'<html><body>Hallo $PassengerFlightRegistrationModel.RecipientName</body></html>',
        2, 0
    )
END

IF NOT EXISTS (SELECT 1 FROM EmailTemplates WHERE EmailTemplateKeyName = 'NewPassengerFlightRegistrationEmail' AND IsSystemTemplate = 1)
BEGIN
    INSERT INTO EmailTemplates (
        EmailTemplateId, ClubId, EmailTemplateName, EmailTemplateKeyName,
        Description, FromAddress, ReplyToAddresses, Subject,
        IsSystemTemplate, CreatedOn, CreatedByUserId,
        RecordState, OwnerId, OwnershipType, IsDeleted,
        HtmlBody, IsCustomizable, LanguageId
    ) VALUES (
        'F1500007-0000-0000-0000-000000000002', NULL,
        N'New passenger flight registration',
        N'NewPassengerFlightRegistrationEmail',
        N'Sends a new-passenger-flight notification email to the club operator.',
        N'fls@glider-fls.ch', N'noreply@glider-fls.ch',
        N'Neue Passagierflug-Registrierung',
        1, SYSUTCDATETIME(), @insertUserId,
        1, @systemClubId, @ownershipClub, 0,
        N'<html><body>Neue Passagier-Registrierung: $PassengerFlightRegistrationModel.Lastname $PassengerFlightRegistrationModel.Firstname</body></html>',
        2, 0
    )
END

-- ---------------------------------------------------------------------------
-- 8. Club organizer email recipients on the test club. The trial flight /
--    passenger flight registration services send a "new registration" email
--    to these addresses if set (RegistrationService.cs:243, :388). Empty in
--    the base seed; tests #2 and #4 assert against the value set here.
-- ---------------------------------------------------------------------------
PRINT 'Fixture: testclub organizer recipients'
UPDATE Clubs
   SET SendTrialFlightRegistrationOperatorEmailTo     = N'trial-organizer@e2e.fls.local',
       SendPassengerFlightRegistrationOperatorEmailTo = N'passenger-organizer@e2e.fls.local'
 WHERE ClubId = @testClubId

PRINT '=== DETERMINISTIC TEST FIXTURE: complete ==='
GO
