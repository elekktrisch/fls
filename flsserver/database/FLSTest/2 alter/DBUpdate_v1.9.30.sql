-- DBUpdate_v1.9.30: performance indexes for the hot-path filter columns.
--
-- Background: most tables only have PK + UNIQUE indexes (covered by
-- UNIQUE constraints). Foreign-key columns and common-filter columns
-- (OwnerId, ClubId, IsActive, ProcessStateId) have NO supporting index,
-- so every paged-search / workflow scan that filters by those columns
-- hits a full table scan. Cheap to fix.
--
-- Conservative scope: only the columns we've seen surface as the hot
-- filter in the running e2e suite (FlightService, AccountingRuleService,
-- AircraftReservationService, ArticleService, DeliveryService).
--
-- All indexes are IF NOT EXISTS so the script is idempotent.

USE [FLSTest]
GO

PRINT 'DBUpdate_v1.9.30: performance indexes'

-- Flights: every list/page/workflow filters by OwnerId; locking +
-- delivery jobs also gate on ProcessStateId.
IF NOT EXISTS (SELECT 1 FROM sys.indexes WHERE name = 'IX_Flights_OwnerId' AND object_id = OBJECT_ID('dbo.Flights'))
BEGIN
    CREATE NONCLUSTERED INDEX [IX_Flights_OwnerId] ON [dbo].[Flights]([OwnerId] ASC)
    PRINT '  + IX_Flights_OwnerId'
END
GO

IF NOT EXISTS (SELECT 1 FROM sys.indexes WHERE name = 'IX_Flights_ProcessStateId' AND object_id = OBJECT_ID('dbo.Flights'))
BEGIN
    CREATE NONCLUSTERED INDEX [IX_Flights_ProcessStateId] ON [dbo].[Flights]([ProcessStateId] ASC)
    PRINT '  + IX_Flights_ProcessStateId'
END
GO

-- Flights -> FlightType is the main join in DeliveryService (filters by
-- flight.FlightType.ClubId == clubId).
IF NOT EXISTS (SELECT 1 FROM sys.indexes WHERE name = 'IX_Flights_FlightTypeId' AND object_id = OBJECT_ID('dbo.Flights'))
BEGIN
    CREATE NONCLUSTERED INDEX [IX_Flights_FlightTypeId] ON [dbo].[Flights]([FlightTypeId] ASC)
    PRINT '  + IX_Flights_FlightTypeId'
END
GO

-- Flights -> Aircraft is joined for every list view.
IF NOT EXISTS (SELECT 1 FROM sys.indexes WHERE name = 'IX_Flights_AircraftId' AND object_id = OBJECT_ID('dbo.Flights'))
BEGIN
    CREATE NONCLUSTERED INDEX [IX_Flights_AircraftId] ON [dbo].[Flights]([AircraftId] ASC)
    PRINT '  + IX_Flights_AircraftId'
END
GO

-- TowFlightId self-join (glider flight -> tow flight).
IF NOT EXISTS (SELECT 1 FROM sys.indexes WHERE name = 'IX_Flights_TowFlightId' AND object_id = OBJECT_ID('dbo.Flights'))
BEGIN
    CREATE NONCLUSTERED INDEX [IX_Flights_TowFlightId] ON [dbo].[Flights]([TowFlightId] ASC)
    PRINT '  + IX_Flights_TowFlightId'
END
GO

-- Aircraft -> AircraftOwnerClubId for /aircrafts list filter.
IF NOT EXISTS (SELECT 1 FROM sys.indexes WHERE name = 'IX_Aircrafts_AircraftOwnerClubId' AND object_id = OBJECT_ID('dbo.Aircrafts'))
BEGIN
    CREATE NONCLUSTERED INDEX [IX_Aircrafts_AircraftOwnerClubId] ON [dbo].[Aircrafts]([AircraftOwnerClubId] ASC)
    PRINT '  + IX_Aircrafts_AircraftOwnerClubId'
END
GO

-- AccountingRuleFilters: rules engine fetches by ClubId + IsActive.
IF NOT EXISTS (SELECT 1 FROM sys.indexes WHERE name = 'IX_AccountingRuleFilters_ClubId_IsActive' AND object_id = OBJECT_ID('dbo.AccountingRuleFilters'))
BEGIN
    CREATE NONCLUSTERED INDEX [IX_AccountingRuleFilters_ClubId_IsActive] ON [dbo].[AccountingRuleFilters]([ClubId] ASC, [IsActive] ASC)
    PRINT '  + IX_AccountingRuleFilters_ClubId_IsActive'
END
GO

-- AircraftReservations: list/page filters by ClubId + Start.
IF NOT EXISTS (SELECT 1 FROM sys.indexes WHERE name = 'IX_AircraftReservations_ClubId' AND object_id = OBJECT_ID('dbo.AircraftReservations'))
BEGIN
    CREATE NONCLUSTERED INDEX [IX_AircraftReservations_ClubId] ON [dbo].[AircraftReservations]([ClubId] ASC)
    PRINT '  + IX_AircraftReservations_ClubId'
END
GO

IF NOT EXISTS (SELECT 1 FROM sys.indexes WHERE name = 'IX_AircraftReservations_AircraftId' AND object_id = OBJECT_ID('dbo.AircraftReservations'))
BEGIN
    CREATE NONCLUSTERED INDEX [IX_AircraftReservations_AircraftId] ON [dbo].[AircraftReservations]([AircraftId] ASC)
    PRINT '  + IX_AircraftReservations_AircraftId'
END
GO

-- Articles: ArticleService lists by ClubId.
IF NOT EXISTS (SELECT 1 FROM sys.indexes WHERE name = 'IX_Articles_ClubId' AND object_id = OBJECT_ID('dbo.Articles'))
BEGIN
    CREATE NONCLUSTERED INDEX [IX_Articles_ClubId] ON [dbo].[Articles]([ClubId] ASC)
    PRINT '  + IX_Articles_ClubId'
END
GO

-- Deliveries: delivery list / lookups by FlightId and by ClubId.
IF NOT EXISTS (SELECT 1 FROM sys.indexes WHERE name = 'IX_Deliveries_FlightId' AND object_id = OBJECT_ID('dbo.Deliveries'))
BEGIN
    CREATE NONCLUSTERED INDEX [IX_Deliveries_FlightId] ON [dbo].[Deliveries]([FlightId] ASC)
    PRINT '  + IX_Deliveries_FlightId'
END
GO

IF NOT EXISTS (SELECT 1 FROM sys.indexes WHERE name = 'IX_Deliveries_ClubId' AND object_id = OBJECT_ID('dbo.Deliveries'))
BEGIN
    CREATE NONCLUSTERED INDEX [IX_Deliveries_ClubId] ON [dbo].[Deliveries]([ClubId] ASC)
    PRINT '  + IX_Deliveries_ClubId'
END
GO

-- FlightCrew: every flight-detail GET pulls crew by FlightId.
IF NOT EXISTS (SELECT 1 FROM sys.indexes WHERE name = 'IX_FlightCrew_FlightId' AND object_id = OBJECT_ID('dbo.FlightCrew'))
BEGIN
    CREATE NONCLUSTERED INDEX [IX_FlightCrew_FlightId] ON [dbo].[FlightCrew]([FlightId] ASC)
    PRINT '  + IX_FlightCrew_FlightId'
END
GO

IF NOT EXISTS (SELECT 1 FROM sys.indexes WHERE name = 'IX_FlightCrew_PersonId' AND object_id = OBJECT_ID('dbo.FlightCrew'))
BEGIN
    CREATE NONCLUSTERED INDEX [IX_FlightCrew_PersonId] ON [dbo].[FlightCrew]([PersonId] ASC)
    PRINT '  + IX_FlightCrew_PersonId'
END
GO

-- FlightTypes: every form load filters by ClubId (already a unique
-- constraint with FlightCode, but we want plain ClubId-only lookups too).
-- The existing UNIQUE_FlightTypes_ClubId_FlightTypeCode supports
-- ClubId-only seeks via the leftmost-column rule, so skip.

-- Locations: LocationService.GetLocationListItems iterates by ClubId.
IF NOT EXISTS (SELECT 1 FROM sys.indexes WHERE name = 'IX_Locations_ClubId' AND object_id = OBJECT_ID('dbo.Locations'))
BEGIN
    CREATE NONCLUSTERED INDEX [IX_Locations_ClubId] ON [dbo].[Locations]([ClubId] ASC)
    PRINT '  + IX_Locations_ClubId'
END
GO

-- Persons: paged person list scans by ClubId via PersonClub join.
-- PersonClub already has UNIQUE on (PersonId, ClubId, MemberNumber) which
-- supports PersonId-leading seeks; add ClubId-leading too for the reverse direction.
IF NOT EXISTS (SELECT 1 FROM sys.indexes WHERE name = 'IX_PersonClub_ClubId' AND object_id = OBJECT_ID('dbo.PersonClub'))
BEGIN
    CREATE NONCLUSTERED INDEX [IX_PersonClub_ClubId] ON [dbo].[PersonClub]([ClubId] ASC)
    PRINT '  + IX_PersonClub_ClubId'
END
GO

-- AuditLogs: TrackerEnabledDbContext stores RecordId (string GUID) +
-- TypeFullName per row; #19's audit lookup filters on both.
IF NOT EXISTS (SELECT 1 FROM sys.indexes WHERE name = 'IX_AuditLogs_RecordId' AND object_id = OBJECT_ID('dbo.AuditLogs'))
BEGIN
    CREATE NONCLUSTERED INDEX [IX_AuditLogs_RecordId] ON [dbo].[AuditLogs]([RecordId] ASC)
    PRINT '  + IX_AuditLogs_RecordId'
END
GO

PRINT 'DBUpdate_v1.9.30: done'
GO
