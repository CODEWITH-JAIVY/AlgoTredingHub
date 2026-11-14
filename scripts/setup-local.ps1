$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
$envFile = Join-Path $root '.env'
function New-Secret([int]$bytes = 32) {
  $buffer = [byte[]]::new($bytes)
  $rng = [Security.Cryptography.RandomNumberGenerator]::Create()
  try { $rng.GetBytes($buffer) } finally { $rng.Dispose() }
  [Convert]::ToBase64String($buffer)
}
$content = if (Test-Path -LiteralPath $envFile) { [IO.File]::ReadAllText($envFile) } else { '' }
$newline = if ($content.Contains("`r`n")) { "`r`n" } else { "`n" }
$requiredKeys = @('DATABASE_PASSWORD', 'REDIS_PASSWORD', 'JWT_SECRET_BASE64', 'ANALYSIS_INTERNAL_TOKEN', 'CORS_ALLOWED_ORIGINS', 'APP_SINGLE_USER_MODE')
$updatedKeys = [System.Collections.Generic.List[string]]::new()

foreach ($key in $requiredKeys) {
  $pattern = "(?m)^$([regex]::Escape($key))=([^`r`n]*)(`r?)$"
  $match = [regex]::Match($content, $pattern)
  $currentValue = if ($match.Success) { $match.Groups[1].Value.Trim() } else { '' }
  $needsValue = [string]::IsNullOrWhiteSpace($currentValue) -or $currentValue.StartsWith('replace-with-', [StringComparison]::OrdinalIgnoreCase)
  if (-not $needsValue) { continue }

  $value = switch ($key) {
    'DATABASE_PASSWORD' { New-Secret 24 }
    'REDIS_PASSWORD' { New-Secret 24 }
    'JWT_SECRET_BASE64' { New-Secret 32 }
    'ANALYSIS_INTERNAL_TOKEN' { New-Secret 48 }
    'CORS_ALLOWED_ORIGINS' { 'http://localhost:3000,chrome-extension://replace-with-extension-id,http://tauri.localhost,http://127.0.0.1:1420' }
    'APP_SINGLE_USER_MODE' { 'true' }
  }

  $replacement = "$key=$value"
  if ($match.Success) {
    $content = [regex]::Replace($content, $pattern, [System.Text.RegularExpressions.MatchEvaluator]{ param($found) $replacement + $found.Groups[2].Value }, 1)
  } else {
    if ($content.Length -gt 0 -and -not $content.EndsWith("`n") -and -not $content.EndsWith("`r")) { $content += $newline }
    $content += $replacement + $newline
  }
  $updatedKeys.Add($key)
}

if ($updatedKeys.Count -gt 0) {
  [IO.File]::WriteAllText($envFile, $content, [Text.UTF8Encoding]::new($false))
  Write-Host "Created or filled local .env settings for: $($updatedKeys -join ', '). Existing non-empty values were preserved."
} else {
  Write-Host 'Local .env already has all required non-empty settings.'
}
Write-Host 'Keep .env private and uncommitted.'
