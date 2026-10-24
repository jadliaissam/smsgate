import js from '@eslint/js'
import globals from 'globals'
import reactHooks from 'eslint-plugin-react-hooks'
import reactRefresh from 'eslint-plugin-react-refresh'
import tseslint from 'typescript-eslint'

export default tseslint.config(
  { ignores: ['dist', 'node_modules'] },
  {
    files: ['**/*.{ts,tsx}'],
    extends: [js.configs.recommended, ...tseslint.configs.strict],
    languageOptions: {
      ecmaVersion: 2022,
      globals: { ...globals.browser },
    },
    plugins: {
      'react-hooks': reactHooks,
      'react-refresh': reactRefresh,
    },
    rules: {
      ...reactHooks.configs.recommended.rules,
      'react-refresh/only-export-components': ['warn', { allowConstantExport: true, allowExportNames: ['useToast'] }],
      // noUncheckedIndexedAccess makes `!` the honest way to state a checked invariant.
      '@typescript-eslint/no-non-null-assertion': 'off',
    },
  },
  {
    files: ['src/test/**', '**/*.test.{ts,tsx}', 'vite.config.ts'],
    languageOptions: { globals: { ...globals.node } },
    rules: { 'react-refresh/only-export-components': 'off' },
  },
)
