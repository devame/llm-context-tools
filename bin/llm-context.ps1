$ErrorActionPreference = "Stop"
$PackageDir = Split-Path -Parent (Split-Path -Parent $MyInvocation.MyCommand.Path)
$JarPath = Join-Path $PackageDir "dist/llm-context.jar"

if (Test-Path $JarPath) {
    & java --enable-native-access=ALL-UNNAMED -jar $JarPath @args
} else {
    Push-Location $PackageDir
    try { & clojure -M -m llm-context.main @args }
    finally { Pop-Location }
}
exit $LASTEXITCODE
