$ErrorActionPreference = 'Stop'

$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$rustRoot = Join-Path $repoRoot '.work\rust-toolchain'
$toolchainRoot = Join-Path $repoRoot '.work\toolchain'
$rustupHome = Join-Path $rustRoot 'rustup-home'
$cargoHome = Join-Path $rustRoot 'cargo-home'
$rustBin = Join-Path $rustupHome 'toolchains\stable-x86_64-pc-windows-msvc\bin'
$xwinBin = Join-Path $toolchainRoot 'portable-cargo-xwin\unpacked'
$llvmBin = Join-Path $toolchainRoot 'portable-llvm-23.1.4\unpacked\clang+llvm-23.1.4-x86_64-pc-windows-msvc\bin'
$requiredFiles = @(
    (Join-Path $cargoHome 'bin\cargo.exe'),
    (Join-Path $rustBin 'rustc.exe'),
    (Join-Path $xwinBin 'cargo-xwin.exe'),
    (Join-Path $llvmBin 'clang-cl.exe'),
    (Join-Path $llvmBin 'lld-link.exe'),
    (Join-Path $llvmBin 'llvm-rc.exe')
)
$missingFiles = @($requiredFiles | Where-Object { -not (Test-Path -LiteralPath $_ -PathType Leaf) })
if ($missingFiles.Count -gt 0) {
    throw "Portable desktop toolchain is incomplete. Missing: $($missingFiles -join ', '). Install the official Tauri Windows prerequisites and run pnpm build:desktop, or restore this checkout's ignored .work toolchain."
}

$env:RUSTUP_HOME = $rustupHome
$env:CARGO_HOME = $cargoHome
$env:RUSTUP_TOOLCHAIN = 'stable-x86_64-pc-windows-msvc'
$env:XWIN_CACHE_DIR = Join-Path $toolchainRoot 'cargo-xwin-cache'
$env:RC = Join-Path $llvmBin 'llvm-rc.exe'
$env:PATH = "$($cargoHome)\bin;$rustBin;$xwinBin;$llvmBin;$env:PATH"

Push-Location $repoRoot
try {
    & pnpm --filter @algotrade/desktop build 2>&1
    if ($LASTEXITCODE -ne 0) {
        throw "Desktop frontend build failed with exit code $LASTEXITCODE."
    }

    & cargo-xwin build --manifest-path apps\desktop\src-tauri\Cargo.toml --locked --release --target x86_64-pc-windows-msvc 2>&1
    if ($LASTEXITCODE -ne 0) {
        throw "Windows MSVC release build failed with exit code $LASTEXITCODE."
    }

    $exe = Join-Path $repoRoot 'apps\desktop\src-tauri\target\x86_64-pc-windows-msvc\release\algotrade-hub-desktop.exe'
    if (-not (Test-Path -LiteralPath $exe -PathType Leaf)) {
        throw "Native executable was not produced at $exe."
    }
    Get-Item -LiteralPath $exe | Select-Object FullName, Length, LastWriteTime
}
finally {
    Pop-Location
}
