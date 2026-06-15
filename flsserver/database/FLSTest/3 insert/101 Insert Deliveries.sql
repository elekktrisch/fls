USE [FLSTest]
GO

-- Deterministic legacy Delivery + DeliveryItem fixture for the migrated
-- done-bar. Runs AFTER _test-fixture.sql so the historical TestClub flight
-- (F1500005-...-000000000001) and the TestClub articles (5001/6001) it seeds
-- already exist. Migrates via DeliveryMapper / DeliveryItemMapper into
-- t_delivery / t_delivery_item; the /deliveries parity spec asserts these
-- known values bit-exactly.

PRINT '=== Insert Deliveries (TestClub migrated done-bar) ==='

DECLARE @testClubId  uniqueidentifier = '0FA7B76F-47BA-4138-8F96-671400FD7C83'
DECLARE @flightId    uniqueidentifier = 'F1500005-0000-0000-0000-000000000001'
DECLARE @deliveryId  uniqueidentifier = 'F1500011-0000-0000-0000-000000000001'
DECLARE @insertUserId uniqueidentifier = '13731EE2-C1D8-455C-8AD1-C39399893FFF'
DECLARE @createdOn   datetime2 = '2026-01-01T00:10:00'

-- Resolve-and-guard the FK targets _test-fixture.sql created. Bail loud rather
-- than seed a dangling FK the migration export would 23503 on.
IF NOT EXISTS (SELECT 1 FROM Clubs WHERE ClubId = @testClubId)
    THROW 51020, 'TestClub missing - run "4 or 5 Insert Test Data.sql" first', 1
IF NOT EXISTS (SELECT 1 FROM Flights WHERE FlightId = @flightId)
    THROW 51021, 'Historical TestClub flight missing - run "_test-fixture.sql" first', 1
IF NOT EXISTS (SELECT 1 FROM Articles WHERE ClubId = @testClubId AND ArticleNumber = '5001' AND IsDeleted = 0)
    THROW 51022, 'TestClub article 5001 missing - run "_test-fixture.sql" first', 1
IF NOT EXISTS (SELECT 1 FROM Articles WHERE ClubId = @testClubId AND ArticleNumber = '6001' AND IsDeleted = 0)
    THROW 51023, 'TestClub article 6001 missing - run "_test-fixture.sql" first', 1

DELETE FROM DeliveryItems WHERE DeliveryId = @deliveryId
DELETE FROM Deliveries    WHERE DeliveryId = @deliveryId

-- RecipientPersonId NULL: the frozen recipient_* snapshot (OR Art. 957a) is the
-- record of truth, independent of a current Persons row (FK is ON DELETE SET NULL).
INSERT INTO Deliveries (
    DeliveryId, ClubId, FlightId,
    RecipientPersonId, RecipientName, RecipientLastname, RecipientFirstname,
    RecipientAddressLine1, RecipientAddressLine2,
    RecipientZipCode, RecipientCity, RecipientCountryName,
    RecipientPersonClubMemberNumber,
    DeliveryInformation, AdditionalInformation,
    DeliveryNumber, DeliveredOn, IsFurtherProcessed, BatchId,
    CreatedOn, CreatedByUserId, RecordState, OwnerId, OwnershipType, IsDeleted
) VALUES (
    @deliveryId, @testClubId, @flightId,
    NULL, N'Walter Wegmueller', N'Wegmueller', N'Walter',
    N'Flugplatzstrasse 1', NULL,
    N'3000', N'Bern', N'Schweiz',
    N'363289',
    N'Lieferschein Segelflug', NULL,
    N'2026001', @createdOn, 0, 9100001,
    @createdOn, @insertUserId, 1, @testClubId, 2, 0
)

INSERT INTO DeliveryItems (
    DeliveryItemId, DeliveryId, Position, ArticleNumber,
    ItemText, AdditionalInformation, Quantity, DiscountInPercent, UnitType,
    CreatedOn, CreatedByUserId, RecordState, OwnerId, OwnershipType, IsDeleted
) VALUES
    ('F1500011-0000-0000-0000-0000000000A1', @deliveryId, 10, N'5001',
        N'Glider flight minutes', NULL, 47.000, 0, N'Min',
        @createdOn, @insertUserId, 1, @testClubId, 2, 0),
    ('F1500011-0000-0000-0000-0000000000A2', @deliveryId, 20, N'6001',
        N'Landegebuehr LSZK', NULL, 1.000, 0, N'Ldgs',
        @createdOn, @insertUserId, 1, @testClubId, 2, 0),
    ('F1500011-0000-0000-0000-0000000000A3', @deliveryId, 30, N'5001',
        N'Glider flight surcharge', NULL, 5.000, 0, N'Min',
        @createdOn, @insertUserId, 1, @testClubId, 2, 0)

PRINT '=== Insert Deliveries: complete ==='
GO
