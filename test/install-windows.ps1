$ErrorActionPreference = "Stop"

$ProjectDir = Split-Path -Parent (Split-Path -Parent $MyInvocation.MyCommand.Path)
$TestDir = Join-Path ([System.IO.Path]::GetTempPath()) ("llm-context-installer-test-" + [guid]::NewGuid())
$ReleaseDir = Join-Path $TestDir "release"
$InstallDir = Join-Path $TestDir "bin"

try {
    New-Item -ItemType Directory -Path $ReleaseDir, $InstallDir | Out-Null
    Copy-Item (Join-Path $ProjectDir "dist\llm-context.jar") $ReleaseDir
    $ReleaseJar = Join-Path $ReleaseDir "llm-context.jar"
    $ReleaseHash = (Get-FileHash -Algorithm SHA256 $ReleaseJar).Hash.ToLowerInvariant()
    Set-Content -Encoding Ascii -Path (Join-Path $ReleaseDir "llm-context.jar.sha256") -Value "$ReleaseHash  llm-context.jar"

    $env:LLM_CONTEXT_RELEASE_URL = ([uri]$ReleaseDir).AbsoluteUri.TrimEnd("/")
    $env:LLM_CONTEXT_INSTALL_DIR = $InstallDir
    & (Join-Path $ProjectDir "install.ps1")

    $Launcher = Join-Path $InstallDir "llm-context.cmd"
    if (-not (Test-Path $Launcher)) { throw "Launcher was not installed" }
    $ExpectedVersion = & java --enable-native-access=ALL-UNNAMED -jar (Join-Path $ProjectDir "dist\llm-context.jar") version
    $InstalledVersion = & $Launcher version
    if ($InstalledVersion -ne $ExpectedVersion) {
        throw "Installed version $InstalledVersion did not match $ExpectedVersion"
    }

    $InstalledJar = Join-Path $InstallDir "llm-context.jar"
    $InstalledHash = (Get-FileHash -Algorithm SHA256 $InstalledJar).Hash
    Set-Content -Encoding Ascii -Path (Join-Path $ReleaseDir "llm-context.jar.sha256") -Value "0000000000000000000000000000000000000000000000000000000000000000  llm-context.jar"
    $Rejected = $false
    try {
        & (Join-Path $ProjectDir "install.ps1")
    } catch {
        $Rejected = $_.Exception.Message -match "checksum"
    }
    if (-not $Rejected) { throw "Installer accepted an invalid checksum" }
    if ((Get-FileHash -Algorithm SHA256 $InstalledJar).Hash -ne $InstalledHash) {
        throw "Failed installation changed the existing jar"
    }
} finally {
    Remove-Item Env:LLM_CONTEXT_RELEASE_URL -ErrorAction SilentlyContinue
    Remove-Item Env:LLM_CONTEXT_INSTALL_DIR -ErrorAction SilentlyContinue
    if (Test-Path $TestDir) {
        Remove-Item -Recurse -Force $TestDir
    }
}
