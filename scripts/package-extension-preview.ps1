$ErrorActionPreference = "Stop"

$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
$extensionDist = (Resolve-Path (Join-Path $repoRoot "apps\extension\dist")).Path
$downloadDirectory = Join-Path $repoRoot "apps\website\public\downloads"
$archivePath = Join-Path $downloadDirectory "algotrade-hub-extension-preview.zip"

if (-not (Test-Path -LiteralPath (Join-Path $extensionDist "manifest.json") -PathType Leaf)) {
  throw "Built extension manifest.json is missing. Run pnpm --filter @algotrade/extension build first."
}

New-Item -ItemType Directory -Path $downloadDirectory -Force | Out-Null
$resolvedArchive = [System.IO.Path]::GetFullPath($archivePath)
if (-not $resolvedArchive.StartsWith($repoRoot + [System.IO.Path]::DirectorySeparatorChar, [System.StringComparison]::OrdinalIgnoreCase)) {
  throw "Refusing to write the extension archive outside the project."
}

if (Test-Path -LiteralPath $archivePath -PathType Leaf) {
  Remove-Item -LiteralPath $archivePath -Force
}
Compress-Archive -Path (Join-Path $extensionDist "*") -DestinationPath $archivePath -CompressionLevel Optimal

Add-Type -AssemblyName System.IO.Compression.FileSystem
$archive = [System.IO.Compression.ZipFile]::OpenRead($archivePath)
try {
  if (-not ($archive.Entries | Where-Object { $_.FullName -eq "manifest.json" })) {
    throw "Extension archive is invalid: manifest.json is not at the ZIP root."
  }
  if (-not ($archive.Entries | Where-Object { $_.FullName -eq "index.html" })) {
    throw "Extension archive is invalid: index.html is not at the ZIP root."
  }
} finally {
  $archive.Dispose()
}

Write-Output "Created $archivePath"
