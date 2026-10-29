param(
    [Parameter(Mandatory = $true)][string]$SourceDirectory,
    [Parameter(Mandatory = $true)][string]$Jdk21
)
$ErrorActionPreference = 'Stop'
$expectedCommit = '3c3a899541074aa4bd944636b25659aef7d23da7'
$sourcePath = [System.IO.Path]::GetFullPath($SourceDirectory)
if (-not (Test-Path -LiteralPath $sourcePath)) {
    git clone --depth 1 --branch v3.0.29 https://github.com/CIRFMF/ksef-client-java.git $sourcePath
    if ($LASTEXITCODE -ne 0) { throw 'SDK_CLONE_FAILED' }
}
$actualCommit = git -C $sourcePath rev-parse HEAD
if ($LASTEXITCODE -ne 0 -or $actualCommit -ne $expectedCommit) { throw 'SDK_COMMIT_MISMATCH' }
git -C $sourcePath diff --quiet HEAD
if ($LASTEXITCODE -ne 0) { throw 'SDK_TRACKED_SOURCES_MODIFIED' }
if (-not (Test-Path -LiteralPath (Join-Path $Jdk21 'bin/java.exe'))) { throw 'JDK21_NOT_FOUND' }
$previousJavaHome = $env:JAVA_HOME
$previousRefName = $env:GITHUB_REF_NAME
try {
    $env:JAVA_HOME = $Jdk21
    # Gradle MF wyprowadza wersję z tej zmiennej także poza GitHub Actions.
    $env:GITHUB_REF_NAME = 'v3.0.29'
    Push-Location -LiteralPath $sourcePath
    try {
        & ./gradlew.bat :ksef-client:publishToMavenLocal --no-daemon
        if ($LASTEXITCODE -ne 0) { throw 'SDK_BUILD_FAILED' }
    } finally { Pop-Location }
} finally {
    $env:JAVA_HOME = $previousJavaHome
    $env:GITHUB_REF_NAME = $previousRefName
}
