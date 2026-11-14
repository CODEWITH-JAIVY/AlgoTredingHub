import type { DemoStatus } from "@algotrade/contracts";

export function getScannerStatusMessage(
  status: DemoStatus | null,
  noProviderMessage: string,
  unavailableMessage: string,
): string {
  if (status === null) return unavailableMessage;
  if (!status.licensedSourceConfigured) return noProviderMessage;
  return status.statusMessage.trim() || unavailableMessage;
}
