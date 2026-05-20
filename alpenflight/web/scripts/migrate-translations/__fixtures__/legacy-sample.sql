-- Synthetic fixture for migrate-translations unit specs.
-- Mirrors the legacy `LanguageTranslations` INSERT shape; tiny + curated
-- so the specs assert behavior, not legacy-content trivia.

USE [FLSTest]
GO

INSERT INTO [dbo].[LanguageTranslations] ([LanguageTranslationId], [TranslationKey], [TranslationValue], [LanguageId], [CreatedOn])
     VALUES (NEWID(), 'AIRCRAFT_MODEL', 'Modell', 1, SYSDATETIME())

INSERT INTO [dbo].[LanguageTranslations] ([LanguageTranslationId], [TranslationKey], [TranslationValue], [LanguageId], [CreatedOn])
     VALUES (NEWID(), 'MASTERDATA', 'Stammdaten', 1, SYSDATETIME())

INSERT INTO [dbo].[LanguageTranslations] ([LanguageTranslationId], [TranslationKey], [TranslationValue], [LanguageId], [CreatedOn])
     VALUES (NEWID(), 'ACCOUNTING_RULE_FILTER_ACTIVE', 'Aktiv', 1, SYSDATETIME())

INSERT INTO [dbo].[LanguageTranslations] ([LanguageTranslationId], [TranslationKey], [TranslationValue], [LanguageId], [CreatedOn])
     VALUES (NEWID(), 'WITH_HTML', '<b>fett</b>', 1, SYSDATETIME())

INSERT INTO [dbo].[LanguageTranslations] ([LanguageTranslationId], [TranslationKey], [TranslationValue], [LanguageId], [CreatedOn])
     VALUES (NEWID(), 'WITH_ENTITY', 'Caf&eacute; &amp; Tee', 1, SYSDATETIME())

INSERT INTO [dbo].[LanguageTranslations] ([LanguageTranslationId], [TranslationKey], [TranslationValue], [LanguageId], [CreatedOn])
     VALUES (NEWID(), 'ORPHAN_KEY', 'kein Konsument', 1, SYSDATETIME())

INSERT INTO [dbo].[LanguageTranslations] ([LanguageTranslationId], [TranslationKey], [TranslationValue], [LanguageId], [CreatedOn])
     VALUES (NEWID(), 'WITH_APOSTROPHE', 'L''avion', 1, SYSDATETIME())

INSERT INTO [dbo].[LanguageTranslations] ([LanguageTranslationId], [TranslationKey], [TranslationValue], [LanguageId], [CreatedOn])
     VALUES (NEWID(), 'UNKNOWN_LANG', 'should be skipped', 9, SYSDATETIME())
