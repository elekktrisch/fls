import { type Page } from '@playwright/test';

import { type TestUser } from './test-user';

/**
 * Shared selectors + helpers for Keycloak's keycloak.v2 login + registration
 * forms (S-171's visual theme inherits this base; field IDs are stable).
 * Scope the submit-button selector to the specific form ID — KC pages may
 * render social-IdP buttons as `<button type="submit">` siblings on some
 * builds, and `.first()` would risk targeting the wrong one.
 */

export const KC_LOGIN_FORM = '#kc-form-login';
export const KC_REGISTER_FORM = '#kc-register-form';

/**
 * Selector union covering KC 26.x's error variants: per-field PF5 helper-text,
 * field-suffixed `#input-error-<name>` (KC 26.5 keycloak.v2 default), un-suffixed
 * legacy `#input-error`, PF5 alert banner, helper-text error variant, and the
 * stock-theme `.alert-error`. Broad enough to survive minor KC bumps without
 * silently passing on the wrong selector.
 */
export const KC_ERROR_SELECTOR =
  '.pf-v5-c-form__helper-text, .pf-v5-c-helper-text__item-text, ' +
  '.pf-v5-c-helper-text__item[data-variant="error"], ' +
  '.pf-v5-c-alert, .alert-error, ' +
  '#input-error, [id^="input-error-"]';

async function submitForm(page: Page, formSelector: string): Promise<void> {
  // Scope to the form so we never click a social-IdP submit-styled button.
  await page
    .locator(`${formSelector} button[type="submit"], ${formSelector} input[type="submit"]`)
    .click();
}

export async function fillKcLogin(page: Page, username: string, password: string): Promise<void> {
  await page.locator('#username').fill(username);
  await page.locator('#password').fill(password);
  await submitForm(page, KC_LOGIN_FORM);
}

export async function fillKcRegistration(page: Page, user: TestUser): Promise<void> {
  await page.locator('#firstName').fill(user.firstName);
  await page.locator('#lastName').fill(user.lastName);
  await page.locator('#email').fill(user.email);
  await page.locator('#password').fill(user.password);
  await page.locator('#password-confirm').fill(user.password);
  await submitForm(page, KC_REGISTER_FORM);
}

/**
 * Registration submit with a deliberately weak password — used by the
 * password-policy reject spec to inject `short` without re-deriving the
 * form-fill dance.
 */
export async function fillKcRegistrationWithPassword(
  page: Page,
  user: TestUser,
  password: string,
): Promise<void> {
  await page.locator('#firstName').fill(user.firstName);
  await page.locator('#lastName').fill(user.lastName);
  await page.locator('#email').fill(user.email);
  await page.locator('#password').fill(password);
  await page.locator('#password-confirm').fill(password);
  await submitForm(page, KC_REGISTER_FORM);
}
