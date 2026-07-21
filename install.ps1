$ErrorActionPreference = "Stop"

$Repository = "devame/llm-context-tools"
$Version = if ($env:LLM_CONTEXT_VERSION) { $env:LLM_CONTEXT_VERSION } else { "latest" }
$InstallDir = if ($env:LLM_CONTEXT_INSTALL_DIR) {
    $env:LLM_CONTEXT_INSTALL_DIR
} else {
    Join-Path $env:LOCALAPPDATA "Programs\llm-context"
}

if ($env:LLM_CONTEXT_RELEASE_URL) {
    $ReleaseUrl = $env:LLM_CONTEXT_RELEASE_URL.TrimEnd("/")
} elseif ($Version -eq "latest") {
    $ReleaseUrl = "https://github.com/$Repository/releases/latest/download"
} else {
    $ReleaseUrl = "https://github.com/$Repository/releases/download/v$Version"
}

try {
    $JavaLine = (& java -version 2>&1 | Select-Object -First 1).ToString()
} catch {
    throw "Java 23 or newer is required but java was not found on PATH"
}
if ($JavaLine -notmatch 'version "(?:1\.)?(\d+)') {
    throw "Could not determine the Java version from: $JavaLine"
}
if ([int]$Matches[1] -lt 23) {
    throw "Java 23 or newer is required; found Java $($Matches[1])"
}

$TempDir = Join-Path ([System.IO.Path]::GetTempPath()) ("llm-context-install-" + [guid]::NewGuid())
New-Item -ItemType Directory -Path $TempDir | Out-Null

function Receive-File([string]$Source, [string]$Destination) {
    $Uri = [uri]$Source
    if ($Uri.IsFile) {
        Copy-Item -LiteralPath $Uri.LocalPath -Destination $Destination
    } else {
        Invoke-WebRequest -UseBasicParsing -Uri $Source -OutFile $Destination
    }
}

try {
    $JarDownload = Join-Path $TempDir "llm-context.jar"
    $ChecksumDownload = Join-Path $TempDir "llm-context.jar.sha256"
    Write-Host "Downloading llm-context $Version..."
    Receive-File "$ReleaseUrl/llm-context.jar" $JarDownload
    Receive-File "$ReleaseUrl/llm-context.jar.sha256" $ChecksumDownload

    $ExpectedHash = ((Get-Content -Raw $ChecksumDownload).Trim() -split '\s+')[0].ToLowerInvariant()
    $ActualHash = (Get-FileHash -Algorithm SHA256 $JarDownload).Hash.ToLowerInvariant()
    if (-not $ExpectedHash -or $ExpectedHash -ne $ActualHash) {
        throw "Release checksum verification failed"
    }

    New-Item -ItemType Directory -Force -Path $InstallDir | Out-Null
    $InstalledJar = Join-Path $InstallDir "llm-context.jar"
    Move-Item -Force $JarDownload $InstalledJar

    $Launcher = Join-Path $InstallDir "llm-context.cmd"
    $LauncherBody = "@echo off`r`njava --enable-native-access=ALL-UNNAMED -jar `"%~dp0llm-context.jar`" %*`r`n"
    Set-Content -Encoding Ascii -NoNewline -Path $Launcher -Value $LauncherBody

    $UserPath = [Environment]::GetEnvironmentVariable("Path", "User")
    $PathEntries = @($UserPath -split ';' | Where-Object { $_ })
    if ($PathEntries -notcontains $InstallDir) {
        $NewPath = (@($PathEntries) + $InstallDir) -join ';'
        [Environment]::SetEnvironmentVariable("Path", $NewPath, "User")
    }
    if (($env:Path -split ';') -notcontains $InstallDir) {
        $env:Path = "$InstallDir;$env:Path"
    }

    $InstalledVersion = & $Launcher version
    if ($LASTEXITCODE -ne 0) {
        throw "Installed launcher failed its version check"
    }
    Write-Host "Installed llm-context $InstalledVersion at $Launcher"

    if ($env:LLM_CONTEXT_INSTALL_SCIP -match '^(1|true|yes)$') {
        if (-not (Get-Command npm -ErrorAction SilentlyContinue)) {
            throw "npm is required when LLM_CONTEXT_INSTALL_SCIP=1"
        }
        Write-Host "Installing optional SCIP TypeScript provider..."
        & npm install --global '@sourcegraph/scip-typescript@^0.4.0'
        if ($LASTEXITCODE -ne 0) {
            throw "SCIP TypeScript installation failed"
        }
    }

    Write-Host "New terminals can run: llm-context doctor"
} finally {
    if (Test-Path -LiteralPath $TempDir) {
        Remove-Item -Recurse -Force -LiteralPath $TempDir
    }
}
