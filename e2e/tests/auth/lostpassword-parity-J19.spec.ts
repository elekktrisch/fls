import { existsSync, statSync } from "node:fs";
import { test, expect, gotoRoute, screenshot } from "../../fixtures";

const SEEDED_USER_WHOSE_CONFIRMED_NOTIFICATION_ADDRESS_ACCEPTS_A_RESET_REQUEST =
  "testclubadmin";

const CONFIRM_LINK_SHAPE_THE_LEGACY_RESET_MAIL_CARRIES =
  "/confirm?userid=00000000-0000-0000-0000-000000000000" +
  "&code=capture-only-never-submitted&emailconfirmed=true";

const MINIMUM_BYTES_A_RENDERED_FULL_PAGE_SHOT_EXCEEDS = 5_000;

const STAGED_SHOTS_THE_J19_GALLERY_PAIRS_AGAINST_ALPENFLIGHT = [
  "legacy-lostpassword-form.png",
  "legacy-lostpassword-success.png",
  "legacy-lostpassword-confirm.png",
] as const;

test.setTimeout(120_000);

test("J-19 capture (no parity claim): legacy /lostpassword form + its success message + the legacy /confirm choose-password form", async ({
  browser,
}, testInfo) => {
  const contextRecordingItsOwnVideoBecauseTestUseVideoIsInertForManualContexts =
    await browser.newContext({
      viewport: { width: 1280, height: 800 },
      recordVideo: {
        dir: testInfo.outputPath("video"),
        size: { width: 1280, height: 800 },
      },
    });
  const page =
    await contextRecordingItsOwnVideoBecauseTestUseVideoIsInertForManualContexts.newPage();

  try {
    await gotoRoute(page, "/lostpassword");

    const lostPasswordForm = page.locator('[data-testid="lostpassword-form"]');
    await lostPasswordForm.waitFor({ state: "visible", timeout: 30_000 });
    const usernameOrNotificationEmailField =
      lostPasswordForm.locator("#username");
    const generateNewPasswordSubmit = page.locator('[data-testid="submit"]');
    await expect(usernameOrNotificationEmailField).toBeVisible();
    await expect(generateNewPasswordSubmit).toBeVisible();

    await screenshot(page, "lostpassword-parity-J19-01-legacy-form");
    await page.screenshot({
      path: testInfo.outputPath("legacy-lostpassword-form.png"),
      fullPage: true,
    });

    await usernameOrNotificationEmailField.fill(
      SEEDED_USER_WHOSE_CONFIRMED_NOTIFICATION_ADDRESS_ACCEPTS_A_RESET_REQUEST,
    );
    await generateNewPasswordSubmit.click();

    const successMessage = page.locator('[data-testid="success-message"]');
    await successMessage.waitFor({ state: "visible", timeout: 30_000 });
    await expect(lostPasswordForm).toBeHidden();
    const successText = (await successMessage.textContent())?.trim() ?? "";
    expect(
      successText.length,
      "the legacy success message must carry rendered text, otherwise the staged shot proves nothing",
    ).toBeGreaterThan(0);

    await screenshot(page, "lostpassword-parity-J19-02-legacy-success");
    await page.screenshot({
      path: testInfo.outputPath("legacy-lostpassword-success.png"),
      fullPage: true,
    });

    await gotoRoute(page, CONFIRM_LINK_SHAPE_THE_LEGACY_RESET_MAIL_CARRIES);

    const choosePasswordForm = page.locator(
      '[data-testid="confirm-email-form"]',
    );
    await choosePasswordForm.waitFor({ state: "visible", timeout: 30_000 });
    await expect(page.locator("#newPassword")).toBeVisible();
    await expect(page.locator("#newPasswordConfirm")).toBeVisible();
    await expect(
      choosePasswordForm.locator('button[type="submit"]'),
      "the legacy app collects the new password itself; the capture never sends this form",
    ).toBeVisible();

    await screenshot(page, "lostpassword-parity-J19-03-legacy-confirm");
    await page.screenshot({
      path: testInfo.outputPath("legacy-lostpassword-confirm.png"),
      fullPage: true,
    });
  } finally {
    await contextRecordingItsOwnVideoBecauseTestUseVideoIsInertForManualContexts.close();
  }

  for (const stagedShot of STAGED_SHOTS_THE_J19_GALLERY_PAIRS_AGAINST_ALPENFLIGHT) {
    const stagedShotPath = testInfo.outputPath(stagedShot);
    expect(
      existsSync(stagedShotPath),
      `expected ${stagedShot} in the test output dir — the J-19 gallery legacy half reads it from there`,
    ).toBeTruthy();
    expect(
      statSync(stagedShotPath).size,
      `${stagedShot} is too small to hold a rendered screen — a blank capture is not a proof`,
    ).toBeGreaterThan(MINIMUM_BYTES_A_RENDERED_FULL_PAGE_SHOT_EXCEEDS);
  }
});
