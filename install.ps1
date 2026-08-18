$ErrorActionPreference = "Stop"

$Repository = "devame/llm-context-tools"
$Version = if ($env:LLM_CONTEXT_VERSION) { $env:LLM_CONTEXT_VERSION } else { "latest" }
$NextPlaidVersion = "1.6.4"
$ModelId = "lightonai/LateOn-Code"
$ModelRevision = "734b659a57935ef50562d79581c3ff1f8d825c93"
$RouterModelId = "mixedbread-ai/mxbai-edge-colbert-v0-32m"
$RouterModelRevision = "963e23afa1478d8bcc12e5d7115adcfdbd22c3af"
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
$RouterModelDir = Join-Path (Join-Path $ModelCacheRoot "mixedbread-ai--mxbai-edge-colbert-v0-32m") $RouterModelRevision

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

$RequestedAcceleratorPackage = if ($env:LLM_CONTEXT_ACCELERATOR_PACKAGE) {
    $env:LLM_CONTEXT_ACCELERATOR_PACKAGE
} else {
    "auto"
}
if ($RequestedAcceleratorPackage -notin @("auto", "cpu", "cuda")) {
    throw "LLM_CONTEXT_ACCELERATOR_PACKAGE must be auto, cpu, or cuda"
}
if ($RequestedAcceleratorPackage -eq "cuda") {
    throw "The Windows installer currently ships the CPU semantic runtime; use Linux/WSL for the CUDA package or set LLM_CONTEXT_ACCELERATOR_PACKAGE=cpu"
}
$NvidiaSmi = Get-Command nvidia-smi.exe -ErrorAction SilentlyContinue
if (-not $NvidiaSmi) {
    $NvidiaSmi = Get-Command nvidia-smi -ErrorAction SilentlyContinue
}
if ($NvidiaSmi) {
    $GpuInfo = @(& $NvidiaSmi.Source '--query-gpu=name,driver_version' '--format=csv,noheader,nounits' 2>$null |
        Select-Object -First 1)
    if ($GpuInfo) {
        Write-Host "NVIDIA GPU detected ($GpuInfo). The Windows package is CPU-only; run 'llm-context setup' after installation for CUDA guidance."
        Write-Host "For CUDA inference, use the Linux/WSL package with the NVIDIA Windows driver rather than installing a Linux driver inside WSL."
    }
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
    $GuideDownload = Join-Path $TempDir "USER-GUIDE.md"
    $GuideChecksumDownload = Join-Path $TempDir "USER-GUIDE.md.sha256"
    Write-Host "Downloading llm-context $Version..."
    Receive-File "$ReleaseUrl/llm-context.jar" $JarDownload
    Receive-File "$ReleaseUrl/llm-context.jar.sha256" $ChecksumDownload
    Receive-File "$ReleaseUrl/USER-GUIDE.md" $GuideDownload
    Receive-File "$ReleaseUrl/USER-GUIDE.md.sha256" $GuideChecksumDownload

    $ExpectedHash = ((Get-Content -Raw $ChecksumDownload).Trim() -split '\s+')[0].ToLowerInvariant()
    $ActualHash = (Get-FileHash -Algorithm SHA256 $JarDownload).Hash.ToLowerInvariant()
    if (-not $ExpectedHash -or $ExpectedHash -ne $ActualHash) {
        throw "Release checksum verification failed"
    }
    $ExpectedGuideHash = ((Get-Content -Raw $GuideChecksumDownload).Trim() -split '\s+')[0].ToLowerInvariant()
    $ActualGuideHash = (Get-FileHash -Algorithm SHA256 $GuideDownload).Hash.ToLowerInvariant()
    if (-not $ExpectedGuideHash -or $ExpectedGuideHash -ne $ActualGuideHash) {
        throw "User guide checksum verification failed"
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

        if (-not $env:LLM_CONTEXT_MODEL_MANIFEST) {
        $ModelHashes = [ordered]@{
            "model.onnx" = "75f8f308994224ac88d580d5a37b68e94bd78be4887b7beb8578ed8b30bad242"
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
            Write-Host "Downloading pinned LateOn-Code FP32 and INT8 models (about 747 MB)..."
            foreach ($ModelFile in $ModelHashes.Keys) {
                $Destination = Join-Path $ModelDownload $ModelFile
                Receive-File "$ModelUrlBase/$ModelFile`?download=true" $Destination
                if (-not (Test-FileHash $Destination $ModelHashes[$ModelFile])) {
                    throw "LateOn-Code model checksum verification failed for $ModelFile"
                }
            }
        }

        $RouterModelHashes = [ordered]@{
            "model.onnx" = "886e3a1638af8222613a8b3baf73520d5ab8c8275fc5ea16e3166982d01df24e"
            "model_int8.onnx" = "264ba680e960af9fffb4f78c3af1e4ff92520678b8e136c79434d88fb2549e1b"
            "tokenizer.json" = "594291000b476c98ed600cbb1914ff128c79642a9433aac86213c7a5562d7c1a"
            "config_sentence_transformers.json" = "0c4eb4090ff55ddee69380ad5ea88a3a89500651996a56953af72bafdb7965b6"
            "config.json" = "a60a035a715a686dca530cf41da553a571e26ea45288d04d750b9da1a27c268d"
            "onnx_config.json" = "e10f017e4a8355f6b15f5be5f67295c90d5b25e487568bf0b0d9ee3259dc0eb7"
        }
        $RouterModelReady = $true
        foreach ($ModelFile in $RouterModelHashes.Keys) {
            if (-not (Test-FileHash (Join-Path $RouterModelDir $ModelFile) $RouterModelHashes[$ModelFile])) {
                $RouterModelReady = $false
                break
            }
        }
        if ($RouterModelReady) {
            Write-Host "Using verified Mixedbread query router at $RouterModelDir"
        } else {
            $RouterModelDownload = Join-Path $TempDir "router-model"
            New-Item -ItemType Directory -Path $RouterModelDownload | Out-Null
            $RouterModelUrlBase = if ($env:LLM_CONTEXT_QUERY_ROUTER_MODEL_URL) {
                $env:LLM_CONTEXT_QUERY_ROUTER_MODEL_URL.TrimEnd("/")
            } else {
                "https://huggingface.co/$RouterModelId/resolve/$RouterModelRevision"
            }
            Write-Host "Downloading pinned Mixedbread FP32 and INT8 query router (about 165 MB)..."
            foreach ($ModelFile in $RouterModelHashes.Keys) {
                $Destination = Join-Path $RouterModelDownload $ModelFile
                Receive-File "$RouterModelUrlBase/$ModelFile`?download=true" $Destination
                if (-not (Test-FileHash $Destination $RouterModelHashes[$ModelFile])) {
                    throw "Mixedbread query-router model checksum verification failed for $ModelFile"
                }
            }
        }
        } else {
            $ModelReady = $true
            $RouterModelReady = $true
        }
    }

    New-Item -ItemType Directory -Force -Path $InstallDir | Out-Null
    $InstalledJar = Join-Path $InstallDir "llm-context.jar"
    Move-Item -Force $JarDownload $InstalledJar
    Move-Item -Force $GuideDownload (Join-Path $InstallDir "USER-GUIDE.md")

    $Launcher = Join-Path $InstallDir "llm-context.cmd"
    $LauncherBody = "@echo off`r`nset `"LLM_CONTEXT_INSTALL_DIR=%~dp0`"`r`nset `"LLM_CONTEXT_MODEL_REGISTRY=%~dp0models.edn`"`r`njava --enable-native-access=ALL-UNNAMED -jar `"%~dp0llm-context.jar`" %*`r`n"
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
        if (-not $RouterModelReady) {
            $RouterModelParent = Split-Path -Parent $RouterModelDir
            $RouterModelStaged = "$RouterModelDir.new.$PID"
            $RouterModelBackup = "$RouterModelDir.previous.$PID"
            New-Item -ItemType Directory -Force -Path $RouterModelParent | Out-Null
            Copy-Item -Recurse -LiteralPath $RouterModelDownload -Destination $RouterModelStaged
            if (Test-Path -LiteralPath $RouterModelDir) {
                Move-Item -LiteralPath $RouterModelDir -Destination $RouterModelBackup
            }
            try {
                Move-Item -LiteralPath $RouterModelStaged -Destination $RouterModelDir
                if (Test-Path -LiteralPath $RouterModelBackup) {
                    Remove-Item -Recurse -Force -LiteralPath $RouterModelBackup
                }
            } catch {
                if (Test-Path -LiteralPath $RouterModelBackup) {
                    Move-Item -LiteralPath $RouterModelBackup -Destination $RouterModelDir
                }
                throw
            }
        }
    }

    $ModelRoles = $env:LLM_CONTEXT_MODEL_ROLES
    if (-not $ModelRoles -and $InstallSemantic) {
        $ModelRoles = "semantic-retriever,query-router-reranker"
    }
    if ($ModelRoles) {
        $ModelArguments = @(
            "--enable-native-access=ALL-UNNAMED", "-jar", $InstalledJar,
            "models", "install", "--cache", $ModelCacheRoot,
            "--registry", (Join-Path $InstallDir "models.edn"),
            "--roles", $ModelRoles)
        if ($env:LLM_CONTEXT_MODEL_MANIFEST) {
            if (-not $env:LLM_CONTEXT_MODEL_MANIFEST_SHA256) {
                throw "LLM_CONTEXT_MODEL_MANIFEST_SHA256 is required for a custom model manifest"
            }
            $ModelArguments += @(
                "--manifest", $env:LLM_CONTEXT_MODEL_MANIFEST,
                "--manifest-sha256", $env:LLM_CONTEXT_MODEL_MANIFEST_SHA256)
        }
        & java @ModelArguments
        if ($LASTEXITCODE -ne 0) {
            throw "Verified model package installation failed"
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
    Write-Host "Installed user guide at $(Join-Path $InstallDir 'USER-GUIDE.md')"
    if ($InstallSemantic) {
        Write-Host "Installed NextPlaid API $NextPlaidVersion, LateOn-Code at $ModelDir, and query router at $RouterModelDir"
    }

    Write-Host "Run 'llm-context setup' to inspect GPU/CUDA prerequisites, or 'llm-context doctor' to check the complete installation."
} finally {
    if (Test-Path -LiteralPath $TempDir) {
        Remove-Item -Recurse -Force -LiteralPath $TempDir
    }
}
