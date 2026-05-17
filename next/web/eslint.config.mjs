import eslint from '@eslint/js';
import tseslint from 'typescript-eslint';
import angular from 'angular-eslint';
import prettier from 'eslint-config-prettier';

export default tseslint.config(
  { ignores: ['src/app/api/generated/**'] },
  {
    files: ['**/*.ts'],
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
      'no-restricted-syntax': [
        'error',
        {
          selector:
            "CallExpression[callee.property.name=/^bypassSecurityTrust/]",
          message:
            'DomSanitizer.bypassSecurityTrust* is forbidden — sanitize inputs at the source. Override only with a per-line eslint-disable + reviewer approval comment.',
        },
        {
          selector:
            "MemberExpression[object.type='MemberExpression'][object.property.name=/^(local|session)Storage$/]",
          message:
            'window.localStorage / window.sessionStorage are forbidden (R10 calcification risk). See no-restricted-globals message.',
        },
      ],
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
                'Components consume Signal Stores, not HttpClient. See next/web/CLAUDE.md §4.',
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
                'Atoms must not import molecules or organisms. See next/web/CLAUDE.md §1.',
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
              message:
                'Molecules must not import organisms. See next/web/CLAUDE.md §1.',
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
    files: ['src/app/features/**/*.store.ts'],
    rules: {
      'no-restricted-imports': [
        'error',
        {
          patterns: [
            {
              group: ['**/features/*/!(index)', '../*/*.store', '../../**/*.store'],
              message:
                'Domain stores do not import sibling stores. Coordinate via MUTATION_BUS. See next/web/src/app/core/mutation-bus/README.md.',
            },
          ],
        },
      ],
    },
  },
  {
    files: ['**/*.html'],
    extends: [
      ...angular.configs.templateRecommended,
      ...angular.configs.templateAccessibility,
    ],
    rules: {
      '@angular-eslint/template/no-any': 'error',
      // Note: the security plan listed `@angular-eslint/template/no-bypass-trust`
      // but that rule does not exist in @angular-eslint v21. The TS-side
      // `no-restricted-syntax` selector for `bypassSecurityTrust*` is the
      // actual guard; templates can't directly reach DomSanitizer anyway
      // (component binding is the only path).
    },
  },
);
