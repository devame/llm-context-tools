$ErrorActionPreference = "Stop"

$Repository = "devame/llm-context-tools"
$Version = if ($env:LLM_CONTEXT_VERSION) { $env:LLM_CONTEXT_VERSION } else { "latest" }
$NextPlaidVersion = "1.6.4"
$ModelId = "lightonai/LateOn-Code"
$ModelRevision = "734b659a57935ef50562d79581c3ff1f8d825c93"
$InstallDir = if ($env:LLM_CONTEXT_INSTALL_DIR) {
    $env:LLM_CONTEXT_INSTALL_DIR
} else {
    Join-Path $env:LOCALAPPDATA "Programs\llm-context"
}
$ModelCacheRoot = if ($env:LLM_CONTEXT_MODEL_CACHE) {
    $env:LLM_CONTEXT_MODEL_CACHE
} else {
    Join-Path $env:LOCALAPPDATA "llm-context\models"
}
$ModelDir = Join-Path (Join-Path $ModelCacheRoot "lightonai--LateOn-Code") $ModelRevision

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

function Test-FileHash([string]$Path, [string]$Expected) {
    (Test-Path -LiteralPath $Path -PathType Leaf) -and
        ((Get-FileHash -Algorithm SHA256 $Path).Hash.ToLowerInvariant() -eq $Expected)
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

    $InstallSemantic = $env:LLM_CONTEXT_SKIP_SEMANTIC -notmatch '^(1|true|yes)$'
    if ($InstallSemantic) {
        $Architecture = [System.Runtime.InteropServices.RuntimeInformation]::OSArchitecture.ToString()
        if ($Architecture -ne "X64") {
            throw "LateOn runtime is not packaged for Windows $Architecture; rerun with LLM_CONTEXT_SKIP_SEMANTIC=1"
        }
        $NextPlaidTarget = "x86_64-pc-windows-msvc"
        $NextPlaidArchiveName = "next-plaid-api-$NextPlaidVersion-$NextPlaidTarget.zip"
        $NextPlaidArchive = Join-Path $TempDir $NextPlaidArchiveName
        $NextPlaidChecksum = "$NextPlaidArchive.sha256"
        Write-Host "Downloading NextPlaid API $NextPlaidVersion for $NextPlaidTarget..."
        Receive-File "$ReleaseUrl/$NextPlaidArchiveName" $NextPlaidArchive
        Receive-File "$ReleaseUrl/$NextPlaidArchiveName.sha256" $NextPlaidChecksum
        $ExpectedNextPlaidHash = ((Get-Content -Raw $NextPlaidChecksum).Trim() -split '\s+')[0].ToLowerInvariant()
        $ActualNextPlaidHash = (Get-FileHash -Algorithm SHA256 $NextPlaidArchive).Hash.ToLowerInvariant()
        if (-not $ExpectedNextPlaidHash -or $ExpectedNextPlaidHash -ne $ActualNextPlaidHash) {
            throw "NextPlaid runtime checksum verification failed"
        }
        $NextPlaidExtracted = Join-Path $TempDir "next-plaid"
        Expand-Archive -LiteralPath $NextPlaidArchive -DestinationPath $NextPlaidExtracted
        $NextPlaidExecutable = Join-Path $NextPlaidExtracted "next-plaid-api.exe"
        if (-not (Test-Path -LiteralPath $NextPlaidExecutable -PathType Leaf)) {
            throw "NextPlaid runtime archive did not contain next-plaid-api.exe"
        }
        $OnnxRuntime = Join-Path $NextPlaidExtracted "onnxruntime.dll"
        if (-not (Test-Path -LiteralPath $OnnxRuntime -PathType Leaf)) {
            throw "NextPlaid runtime archive did not contain ONNX Runtime"
        }

        $ModelHashes = [ordered]@{
            "model_int8.onnx" = "a62a88b4e3ebb76e8bc5f0263d17b773c667d27bc73c5120e3131048dd1554ef"
            "tokenizer.json" = "a388b94942e98e5c661c6c23f919842285738bfd123a0d148dea0c56287505d0"
            "config_sentence_transformers.json" = "34942289dec20e285b07132aa1d09980ed776a0bc34e531dd7b49c4701876871"
            "config.json" = "424fa6fedd42b6a78257145a6068c17cc7e67ac5d7cc3c011ed9d8141c9159d4"
            "onnx_config.json" = "eedf90bb3b71b7500a973e140b72a736c4c5ca4b6746c1f69fcc64b29924a8d5"
        }
        $ModelReady = $true
        foreach ($ModelFile in $ModelHashes.Keys) {
            if (-not (Test-FileHash (Join-Path $ModelDir $ModelFile) $ModelHashes[$ModelFile])) {
                $ModelReady = $false
                break
            }
        }
        if ($ModelReady) {
            Write-Host "Using verified LateOn-Code model snapshot at $ModelDir"
        } else {
            $ModelDownload = Join-Path $TempDir "model"
            New-Item -ItemType Directory -Path $ModelDownload | Out-Null
            $ModelUrlBase = if ($env:LLM_CONTEXT_MODEL_URL) {
                $env:LLM_CONTEXT_MODEL_URL.TrimEnd("/")
            } else {
                "https://huggingface.co/$ModelId/resolve/$ModelRevision"
            }
            Write-Host "Downloading pinned LateOn-Code INT8 model (about 154 MB)..."
            foreach ($ModelFile in $ModelHashes.Keys) {
                $Destination = Join-Path $ModelDownload $ModelFile
                Receive-File "$ModelUrlBase/$ModelFile`?download=true" $Destination
                if (-not (Test-FileHash $Destination $ModelHashes[$ModelFile])) {
                    throw "LateOn-Code model checksum verification failed for $ModelFile"
                }
            }
        }
    }

    New-Item -ItemType Directory -Force -Path $InstallDir | Out-Null
    $InstalledJar = Join-Path $InstallDir "llm-context.jar"
    Move-Item -Force $JarDownload $InstalledJar

    $Launcher = Join-Path $InstallDir "llm-context.cmd"
    $LauncherBody = "@echo off`r`nset `"LLM_CONTEXT_INSTALL_DIR=%~dp0`"`r`njava --enable-native-access=ALL-UNNAMED -jar `"%~dp0llm-context.jar`" %*`r`n"
    Set-Content -Encoding Ascii -NoNewline -Path $Launcher -Value $LauncherBody

    if ($InstallSemantic) {
        Move-Item -Force $NextPlaidExecutable (Join-Path $InstallDir "next-plaid-api.exe")
        Move-Item -Force $OnnxRuntime (Join-Path $InstallDir "onnxruntime.dll")
        $NextPlaidLicense = Join-Path $NextPlaidExtracted "next-plaid-LICENSE"
        if (Test-Path -LiteralPath $NextPlaidLicense -PathType Leaf) {
            Copy-Item -Force $NextPlaidLicense (Join-Path $InstallDir "next-plaid-LICENSE")
        }
        $OnnxLicense = Join-Path $NextPlaidExtracted "onnxruntime-LICENSE"
        if (Test-Path -LiteralPath $OnnxLicense -PathType Leaf) {
            Copy-Item -Force $OnnxLicense (Join-Path $InstallDir "onnxruntime-LICENSE")
        }
        $OnnxNotices = Join-Path $NextPlaidExtracted "onnxruntime-ThirdPartyNotices.txt"
        if (Test-Path -LiteralPath $OnnxNotices -PathType Leaf) {
            Copy-Item -Force $OnnxNotices (Join-Path $InstallDir "onnxruntime-ThirdPartyNotices.txt")
        }
        if (-not $ModelReady) {
            $ModelParent = Split-Path -Parent $ModelDir
            $ModelStaged = "$ModelDir.new.$PID"
            $ModelBackup = "$ModelDir.previous.$PID"
            New-Item -ItemType Directory -Force -Path $ModelParent | Out-Null
            Copy-Item -Recurse -LiteralPath $ModelDownload -Destination $ModelStaged
            if (Test-Path -LiteralPath $ModelDir) {
                Move-Item -LiteralPath $ModelDir -Destination $ModelBackup
            }
            try {
                Move-Item -LiteralPath $ModelStaged -Destination $ModelDir
                if (Test-Path -LiteralPath $ModelBackup) {
                    Remove-Item -Recurse -Force -LiteralPath $ModelBackup
                }
            } catch {
                if (Test-Path -LiteralPath $ModelBackup) {
                    Move-Item -LiteralPath $ModelBackup -Destination $ModelDir
                }
                throw
            }
        }
    }

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
    if ($InstallSemantic) {
        Write-Host "Installed NextPlaid API $NextPlaidVersion and LateOn-Code at $ModelDir"
    }

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
