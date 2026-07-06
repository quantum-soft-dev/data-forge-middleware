/**
 * Legacy path — monitoring tokens moved to `@/shared/ui/tokens` (024, T001).
 * Kept as a re-export for import stability during the migration; deleted in T035.
 */

export type { SeverityToken } from '@/shared/ui/tokens';
export { monitoringTokens, severityTokens } from '@/shared/ui/tokens';
