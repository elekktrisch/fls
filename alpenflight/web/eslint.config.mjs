import eslint from '@eslint/js';
import tseslint from 'typescript-eslint';
import angular from 'angular-eslint';
import prettier from 'eslint-config-prettier';

const APP_WIDE_RESTRICTED_SYNTAX = [
  {
    selector: 'CallExpression[callee.property.name=/^bypassSecurityTrust/]',
    message:
      'DomSanitizer.bypassSecurityTrust* is forbidden — sanitize inputs at the source. Override only with a per-line eslint-disable, and get a reviewer to approve it on the PR.',
  },
  {
    selector:
      "MemberExpression[object.type='MemberExpression'][object.property.name=/^(local|session)Storage$/]",
    message:
      'window.localStorage / window.sessionStorage are forbidden (R10 calcification risk). See no-restricted-globals message.',
  },
];

const REAL_IDP_LANE_FILES = ['e2e/tests/real-idp/**/*.ts', 'e2e/tests/profile/**/*.ts'];

const ROUTE_INSTALLING_PLAYWRIGHT_MEMBER_NAMES = '^(route|routeFromHAR|routeWebSocket)$';

const HELPER_EXPORT_NAMES_THAT_READ_AS_ROUTE_INSTALLERS =
  '^(install|stub|mock|fake|intercept|route)';

const WHY_A_REAL_IDP_SPEC_MUST_NOT_INSTALL_A_NETWORK_INTERCEPT =
  'A real-idp spec proves the full legacy → migrate → Keycloak → app chain. A network intercept turns that proof into a mock, so it is forbidden in this lane. The lane is e2e/tests/real-idp/** AND e2e/tests/profile/**, because the real-idp Playwright project runs both (e2e/playwright.config.ts:99-100). To COUNT or inspect requests use page.on("request", ...), which observes without intercepting. To mock, write the spec under a mock-auth project directory instead.';

const WHAT_THE_REAL_IDP_INTERCEPT_BAN_STILL_CANNOT_SEE =
  'Residual limit — this rule reads names, not values, and it reads them only inside the lane it lists. Four shapes still pass: a member name computed at run time (page[nameHeldInAVariable], Reflect.get(page, name)); a helper that installs routes under a name outside the install|stub|mock|fake|intercept|route convention; a re-export that renames such a helper in a file outside this lane; and a new directory that joins the real-idp Playwright project without joining REAL_IDP_LANE_FILES here AND lintFilePatterns in angular.json. A reviewer owns those four, not the rule.';

const forbidRouteInstallShapeInRealIdpLane = (whatTheAuthorWrote, selector) => ({
  selector,
  message: `${whatTheAuthorWrote} — ${WHY_A_REAL_IDP_SPEC_MUST_NOT_INSTALL_A_NETWORK_INTERCEPT} ${WHAT_THE_REAL_IDP_INTERCEPT_BAN_STILL_CANNOT_SEE}`,
});

const REAL_IDP_ROUTE_INSTALL_BANS = [
  forbidRouteInstallShapeInRealIdpLane(
    'this reads a route-installing member off an object by a dot (page.route, context.route, page.routeFromHAR, page.routeWebSocket), whether it calls it, binds it or stores it in a variable',
    `MemberExpression[computed=false][property.name=/${ROUTE_INSTALLING_PLAYWRIGHT_MEMBER_NAMES}/]`,
  ),
  forbidRouteInstallShapeInRealIdpLane(
    'this reads a route-installing member off an object by a computed string or template literal (page["route"])',
    `MemberExpression[computed=true][property.value=/${ROUTE_INSTALLING_PLAYWRIGHT_MEMBER_NAMES}/], MemberExpression[computed=true][property.type='TemplateLiteral']`,
  ),
  forbidRouteInstallShapeInRealIdpLane(
    'this destructures a route-installing member out of page or context, under its own name or an alias (const { route } = page)',
    `ObjectPattern > Property[computed=false][key.name=/${ROUTE_INSTALLING_PLAYWRIGHT_MEMBER_NAMES}/], ObjectPattern > Property[computed=true][key.value=/${ROUTE_INSTALLING_PLAYWRIGHT_MEMBER_NAMES}/]`,
  ),
  forbidRouteInstallShapeInRealIdpLane(
    'this calls a bare route-installing binding that a destructure, an import or a re-export brought into scope (route(...))',
    `CallExpression[callee.type='Identifier'][callee.name=/${ROUTE_INSTALLING_PLAYWRIGHT_MEMBER_NAMES}/]`,
  ),
  forbidRouteInstallShapeInRealIdpLane(
    'this loads a module at run time (dynamic import(), require()), which hides the imported names from the import ban below — use a static import',
    "ImportExpression, CallExpression[callee.name='require']",
  ),
];

const REAL_IDP_ROUTE_INSTALLING_HELPER_IMPORT_BAN = {
  patterns: [
    {
      group: ['**'],
      importNamePattern: HELPER_EXPORT_NAMES_THAT_READ_AS_ROUTE_INSTALLERS,
      message: `this imports a helper whose name reads as a route installer, such as installMockApiStubs from e2e/tests/_helpers/console-guard.ts, which routes **/api/v1/**. ${WHY_A_REAL_IDP_SPEC_MUST_NOT_INSTALL_A_NETWORK_INTERCEPT} The shared helper directory itself stays outside this ban, because console-guard.ts installs those stubs for the mock-auth project on purpose. ${WHAT_THE_REAL_IDP_INTERCEPT_BAN_STILL_CANNOT_SEE}`,
    },
  ],
};

export default tseslint.config(
  { ignores: ['src/app/api/generated/**'] },
  {
    files: ['**/*.ts'],
    ignores: ['e2e/tests/real-idp/**/*.ts'],
    extends: [
      eslint.configs.recommended,
      ...tseslint.configs.recommended,
      ...tseslint.configs.stylistic,
      ...angular.configs.tsRecommended,
      prettier,
    ],
    processor: angular.processInlineTemplates,
    rules: {
      '@angular-eslint/directive-selector': [
        'error',
        { type: 'attribute', prefix: 'af', style: 'camelCase' },
      ],
      '@angular-eslint/component-selector': [
        'error',
        { type: 'element', prefix: 'af', style: 'kebab-case' },
      ],
      '@angular-eslint/prefer-standalone': 'error',
      'no-restricted-globals': [
        'error',
        {
          name: 'localStorage',
          message:
            'Direct localStorage usage is forbidden (R10 calcification risk). Auth-owned files in S-021 are the only allowlist; use a typed wrapper there.',
        },
        {
          name: 'sessionStorage',
          message:
            'Direct sessionStorage usage is forbidden (R10 calcification risk). Auth-owned files in S-021 are the only allowlist; use a typed wrapper there.',
        },
      ],
      'no-restricted-syntax': ['error', ...APP_WIDE_RESTRICTED_SYNTAX],
    },
  },
  {
    files: ['src/app/features/**/*.component.ts'],
    rules: {
      'no-restricted-imports': [
        'error',
        {
          paths: [
            {
              name: '@angular/common/http',
              message:
                'Components consume Signal Stores, not HttpClient. See alpenflight/web/CLAUDE.md §4.',
            },
          ],
        },
      ],
    },
  },
  {
    files: ['src/app/**/*.ts'],
    ignores: ['src/app/shared/ui/**'],
    rules: {
      'no-restricted-imports': [
        'error',
        {
          paths: [
            {
              name: 'ng-zorro-antd',
              message:
                'Import per-component entry points (e.g. ng-zorro-antd/button), not the umbrella module. Outside shared/ui/, prefer the af-* wrappers. See S-008 design.',
            },
          ],
        },
      ],
    },
  },
  {
    files: ['src/app/shared/ui/atoms/**/*.ts'],
    rules: {
      'no-restricted-imports': [
        'error',
        {
          patterns: [
            {
              group: ['**/shared/ui/molecules/**', '**/shared/ui/organisms/**'],
              message:
                'Atoms must not import molecules or organisms. See alpenflight/web/CLAUDE.md §1.',
            },
            {
              regex: '^ng-zorro-antd$',
              message:
                'Import per-component entry points (e.g. ng-zorro-antd/button), not the umbrella module.',
            },
          ],
        },
      ],
    },
  },
  {
    files: ['src/app/shared/ui/molecules/**/*.ts'],
    rules: {
      'no-restricted-imports': [
        'error',
        {
          patterns: [
            {
              group: ['**/shared/ui/organisms/**'],
              message: 'Molecules must not import organisms. See alpenflight/web/CLAUDE.md §1.',
            },
            {
              regex: '^ng-zorro-antd$',
              message:
                'Import per-component entry points (e.g. ng-zorro-antd/button), not the umbrella module.',
            },
          ],
        },
      ],
    },
  },
  {
    files: ['src/app/shared/ui/organisms/**/*.ts'],
    rules: {
      'no-restricted-imports': [
        'error',
        {
          patterns: [
            {
              regex: '^ng-zorro-antd$',
              message:
                'Import per-component entry points (e.g. ng-zorro-antd/button), not the umbrella module.',
            },
          ],
        },
      ],
    },
  },
  {
    files: ['src/app/shared/ui/recency/**/*.ts'],
    rules: {
      'no-restricted-globals': 'off',
      'no-restricted-syntax': 'off',
    },
  },
  {
    files: [
      'src/app/features/signup/signup-pending.ts',
      'src/app/features/signup/signup-pending.spec.ts',
    ],
    rules: {
      'no-restricted-globals': 'off',
      'no-restricted-syntax': 'off',
    },
  },
  {
    files: ['src/app/features/**/*.store.ts'],
    rules: {
      'no-restricted-imports': [
        'error',
        {
          patterns: [
            {
              group: ['**/features/*/!(index)', '../*/*.store', '../../**/*.store'],
              message:
                'Domain stores do not import sibling stores. Coordinate via MUTATION_BUS. See alpenflight/web/src/app/core/mutation-bus/README.md.',
            },
          ],
        },
      ],
    },
  },
  {
    files: REAL_IDP_LANE_FILES,
    extends: [tseslint.configs.base],
    linterOptions: { reportUnusedDisableDirectives: 'off' },
    rules: {
      'no-restricted-syntax': [
        'error',
        ...APP_WIDE_RESTRICTED_SYNTAX,
        ...REAL_IDP_ROUTE_INSTALL_BANS,
      ],
      'no-restricted-imports': ['error', REAL_IDP_ROUTE_INSTALLING_HELPER_IMPORT_BAN],
    },
  },
  {
    files: ['**/*.html'],
    extends: [...angular.configs.templateRecommended, ...angular.configs.templateAccessibility],
    rules: {
      '@angular-eslint/template/no-any': 'error',
    },
  },
);
