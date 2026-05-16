import eslint from '@eslint/js';
import tseslint from 'typescript-eslint';
import angular from 'angular-eslint';
import prettier from 'eslint-config-prettier';

export default tseslint.config(
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
