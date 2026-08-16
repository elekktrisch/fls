import { type Page } from '@playwright/test';

import { type TestUser } from './test-user';

export const KC_LOGIN_FORM = '#kc-form-login';
export const KC_REGISTER_FORM = '#kc-register-form';

export const KC_ERROR_SELECTOR =
  '.pf-v5-c-form__helper-text, .pf-v5-c-helper-text__item-text, ' +
  '.pf-v5-c-helper-text__item[data-variant="error"], ' +
  '.pf-v5-c-alert, .alert-error, ' +
  '#input-error, [id^="input-error-"]';

async function submitForm(page: Page, formSelector: string): Promise<void> {
  await page
    .locator(`${formSelector} button[type="submit"], ${formSelector} input[type="submit"]`)
    .click();
}

export async function fillKcLogin(page: Page, username: string, password: string): Promise<void> {
  await page.locator('#username').fill(username);
  await page.locator('#password').fill(password);
  await submitForm(page, KC_LOGIN_FORM);
}

const KC_REGISTRATION_USERNAME_FIELD_REQUIRED_WHILE_THE_REALM_KEEPS_USERNAME_SEPARATE_FROM_EMAIL = `${KC_REGISTER_FORM} #username`;

async function fillEveryFieldTheKcRegistrationFormRequires(
  page: Page,
  user: TestUser,
  password: string,
): Promise<void> {
  await page
    .locator(
      KC_REGISTRATION_USERNAME_FIELD_REQUIRED_WHILE_THE_REALM_KEEPS_USERNAME_SEPARATE_FROM_EMAIL,
    )
    .fill(user.email);
  await page.locator('#firstName').fill(user.firstName);
  await page.locator('#lastName').fill(user.lastName);
  await page.locator('#email').fill(user.email);
  await page.locator('#password').fill(password);
  await page.locator('#password-confirm').fill(password);
  await submitForm(page, KC_REGISTER_FORM);
}

export async function fillKcRegistration(page: Page, user: TestUser): Promise<void> {
  await fillEveryFieldTheKcRegistrationFormRequires(page, user, user.password);
}

export async function fillKcRegistrationWithPassword(
  page: Page,
  user: TestUser,
  password: string,
): Promise<void> {
  await fillEveryFieldTheKcRegistrationFormRequires(page, user, password);
}
