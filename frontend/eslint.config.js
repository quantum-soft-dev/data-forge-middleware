import js from '@eslint/js'
import globals from 'globals'
import tseslint from 'typescript-eslint'
import reactHooks from 'eslint-plugin-react-hooks'
import reactRefresh from 'eslint-plugin-react-refresh'

export default tseslint.config(
  { ignores: ['dist', 'coverage', 'node_modules', 'playwright-report', 'test-results'] },
  {
    files: ['**/*.{ts,tsx}'],
    extends: [js.configs.recommended, ...tseslint.configs.recommended],
    languageOptions: {
      ecmaVersion: 2022,
      globals: { ...globals.browser, ...globals.node },
    },
    plugins: {
      'react-hooks': reactHooks,
      'react-refresh': reactRefresh,
    },
    rules: {
      ...reactHooks.configs.recommended.rules,
      // React Compiler is not enabled in this build (vite.config.ts runs
      // @vitejs/plugin-react without babel-plugin-react-compiler), and this rule
      // only reports "the compiler would skip this component" for third-party
      // APIs we cannot change — react-hook-form's watch(), TanStack table/virtual
      // instances. Turn it back on if the compiler is ever switched on.
      'react-hooks/incompatible-library': 'off',
      'react-refresh/only-export-components': [
        'warn',
        {
          allowConstantExport: true,
          // shadcn primitives ship their styling helper next to the component.
          allowExportNames: ['badgeVariants', 'buttonVariants', 'tableCellNumeric'],
        },
      ],
      // `_name` is the codebase's existing marker for a binding that only exists
      // to satisfy a signature (unused props, positional callback arguments).
      '@typescript-eslint/no-unused-vars': [
        'error',
        {
          argsIgnorePattern: '^_',
          varsIgnorePattern: '^_',
          caughtErrorsIgnorePattern: '^_',
          destructuredArrayIgnorePattern: '^_',
        },
      ],
    },
  },
  {
    // The route table declares one lazy component per route next to the router
    // it exports; splitting them apart would only move the route definitions
    // away from the pages they mount.
    files: ['src/app/router.tsx'],
    rules: { 'react-refresh/only-export-components': 'off' },
  },
  {
    // Tests lean on `any` for fixtures and on non-null assertions for query results.
    files: ['**/*.test.{ts,tsx}', 'tests/**/*.{ts,tsx}'],
    languageOptions: { globals: globals.vitest },
    rules: {
      '@typescript-eslint/no-explicit-any': 'off',
      '@typescript-eslint/no-non-null-assertion': 'off',
    },
  },
)
