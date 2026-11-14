param(
    [int]$Port = 8080
)

$ErrorActionPreference = "Stop"
$repositoryRoot = Split-Path -Parent $PSScriptRoot
$backendDirectory = Join-Path $repositoryRoot "services\backend"
$mavenRepository = Join-Path $repositoryRoot ".work\maven-repo"
$mavenUserHome = Join-Path $repositoryRoot ".work\maven-user-home"
$javaHome = Join-Path $repositoryRoot ".work\toolchain\jdk-21.0.12.1+1"
$previousValues = @{
    JWT_SECRET_BASE64 = $env:JWT_SECRET_BASE64
    JAVA_HOME = $env:JAVA_HOME
    MAVEN_USER_HOME = $env:MAVEN_USER_HOME
    PATH = $env:PATH
    SERVER_PORT = $env:SERVER_PORT
    SERVER_ADDRESS = $env:SERVER_ADDRESS
    APP_TRADING_MODE = $env:APP_TRADING_MODE
    APP_TRADING_LIVE_ORDERS_ENABLED = $env:APP_TRADING_LIVE_ORDERS_ENABLED
    APP_REGISTRATION_SINGLE_USER_MODE = $env:APP_REGISTRATION_SINGLE_USER_MODE
    SPRING_PROFILES_ACTIVE = $env:SPRING_PROFILES_ACTIVE
}
$secretBytes = New-Object byte[] 32
$random = [System.Security.Cryptography.RandomNumberGenerator]::Create()
$locationChanged = $false

try {
    if (-not (Test-Path -LiteralPath (Join-Path $javaHome "bin\java.exe"))) {
        throw "The cached Java 21 toolchain is missing. Restore it or install Java 21 before starting the demo."
    }
    $random.GetBytes($secretBytes)
    $env:JWT_SECRET_BASE64 = [Convert]::ToBase64String($secretBytes)
    $env:JAVA_HOME = $javaHome
    $env:MAVEN_USER_HOME = $mavenUserHome
    $env:PATH = (Join-Path $javaHome "bin") + ";" + $env:PATH
    $env:SERVER_PORT = [string]$Port
    $env:SERVER_ADDRESS = "127.0.0.1"
    $env:APP_TRADING_MODE = "MOCK"
    $env:APP_TRADING_LIVE_ORDERS_ENABLED = "false"
    $env:APP_REGISTRATION_SINGLE_USER_MODE = "true"
    $env:SPRING_PROFILES_ACTIVE = "local-demo"
    Push-Location $backendDirectory
    $locationChanged = $true
    & .\mvnw.cmd -o "-Dmaven.repo.local=$mavenRepository" "-Dspring-boot.run.profiles=local-demo" spring-boot:run
    if ($LASTEXITCODE -ne 0) { throw "The local demo backend exited with code $LASTEXITCODE." }
} finally {
    if ($locationChanged) { Pop-Location }
    $random.Dispose()
    [Array]::Clear($secretBytes, 0, $secretBytes.Length)
    foreach ($name in $previousValues.Keys) {
        $prior = $previousValues[$name]
        if ($null -eq $prior) { Remove-Item "Env:$name" -ErrorAction SilentlyContinue }
        else { Set-Item "Env:$name" $prior }
    }
}
