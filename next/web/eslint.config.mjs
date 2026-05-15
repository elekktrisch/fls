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
        { type: 'attribute', prefix: 'fls', style: 'camelCase' },
      ],
      '@angular-eslint/component-selector': [
        'error',
        { type: 'element', prefix: 'fls', style: 'kebab-case' },
      ],
      '@angular-eslint/prefer-standalone': 'error',
      'no-restricted-syntax': [
        'error',
        {
          selector: "MemberExpression[object.name='DomSanitizer'][property.name=/^bypassSecurityTrust/]",
          message: 'DomSanitizer.bypassSecurityTrust* is forbidden — sanitize inputs at the source.',
        },
        {
          selector: "CallExpression[callee.object.name=/^(localStorage|sessionStorage)$/][callee.property.name='setItem']",
          message: 'Direct localStorage/sessionStorage writes are forbidden — auth-owned files in S-021 are the only allowlist.',
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
    },
  },
);
