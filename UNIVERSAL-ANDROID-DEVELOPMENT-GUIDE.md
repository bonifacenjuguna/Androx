# Universal Android Development Guide

A reusable Windows PowerShell reference for building, updating, testing, installing, inspecting, and archiving Android projects with Gradle.

**Designed for:** Android projects using the Gradle Wrapper  
**Platform:** Windows 10/11 · PowerShell  
**Default module:** `app` (change it when a project uses another module)

> Run commands from the project root: the directory containing `gradlew.bat`. Replace example paths, module names, and package IDs with those belonging to the project you are working on.

---

## 1. Open an Android project

```powershell
cd "C:\Path\To\Your\AndroidProject"
```

Confirm that the wrapper exists:

```powershell
Test-Path .\gradlew.bat
```

If this returns `False`, you are either in the wrong directory or the project does not contain the Gradle Wrapper.

## 2. Build a debug APK

For normal development and testing:

```powershell
.\gradlew.bat :app:assembleDebug
```

Typical output:

```text
app\build\outputs\apk\debug\app-debug.apk
```

Change `:app:` if the Android module has another name.

## 3. Pull the latest GitHub changes and build

```powershell
git status
git pull --ff-only
git log -1 --oneline
.\gradlew.bat :app:assembleDebug
```

Check `git status` before pulling. Commit or safely store local changes first. `--ff-only` prevents Git from creating an automatic merge commit; if the pull cannot fast-forward, stop and resolve the Git situation before building.

## 4. Clean build

```powershell
.\gradlew.bat clean :app:assembleDebug
```

This removes generated build outputs and rebuilds them. It does not delete source code.

## 5. Refresh dependencies

```powershell
.\gradlew.bat :app:assembleDebug --refresh-dependencies
```

Use when dependency resolution or cached dependency metadata appears to be the problem. This does not mean deleting the global Gradle cache.

## 6. Build without the Gradle build cache

```powershell
.\gradlew.bat :app:assembleDebug --no-build-cache
```

For a clean build without reusing Gradle's build cache:

```powershell
.\gradlew.bat --no-build-cache clean :app:assembleDebug
```

## 7. Run unit tests

```powershell
.\gradlew.bat :app:testDebugUnitTest
```

To run the common debug verification tasks together:

```powershell
.\gradlew.bat :app:testDebugUnitTest :app:assembleDebug
```

A successful APK build does not automatically mean all tests passed. Check the test task result separately.

## 8. Install the debug build

With an Android device connected and USB debugging enabled:

```powershell
.\gradlew.bat :app:installDebug
```

Or build first, then install the APK directly:

```powershell
adb install -r ".\app\build\outputs\apk\debug\app-debug.apk"
```

The `-r` option attempts to reinstall while retaining app data, subject to package/signature compatibility.

## 9. Check connected Android devices

```powershell
adb devices
```

If no device appears, check the USB cable, USB mode, debugging authorization, and ADB installation.

## 10. Check Gradle and Java versions

```powershell
.\gradlew.bat --version
java -version
```

The Gradle Wrapper selects the Gradle version configured by the project. The project's Android Gradle Plugin and Java requirements must also be compatible.

## 11. List available Gradle tasks

```powershell
.\gradlew.bat tasks
```

To inspect Android-related tasks:

```powershell
.\gradlew.bat tasks --all
```

Use the tasks actually exposed by the project; module names and variants can differ.

## 12. Check APK size

```powershell
$Apk = ".\app\build\outputs\apk\debug\app-debug.apk"

if (Test-Path $Apk) {
    Get-Item $Apk | Select-Object FullName,
        @{Name="SizeMB";Expression={[math]::Round($_.Length / 1MB, 2)}}
} else {
    Write-Host "APK not found. Build the project first."
}
```

## 13. Inspect APK details and checksum

```powershell
$Apk = ".\app\build\outputs\apk\debug\app-debug.apk"

if (Test-Path $Apk) {
    $File = Get-Item $Apk
    Write-Host "File: $($File.FullName)"
    Write-Host "Size: $([math]::Round($File.Length / 1MB, 2)) MB"
    Write-Host "Modified: $($File.LastWriteTime)"
    Get-FileHash $Apk -Algorithm SHA256
} else {
    Write-Host "APK not found."
}
```

A SHA-256 hash helps verify whether two APK files are identical.

## 14. Find an installed application

Replace the search text with part of the app's package ID:

```powershell
adb shell pm list packages | findstr /i "your.package.keyword"
```

To see the installed APK path for a known package:

```powershell
adb shell pm path com.example.app
```

## 15. Uninstall an application

```powershell
adb uninstall com.example.app
```

**Warning:** Uninstalling normally removes the app's local data. Replace the example package ID with the actual application ID.

## 16. Useful Gradle and Android notes

- Run wrapper commands from the project root, where `gradlew.bat` is located.
- The examples assume a module named `app`; some projects use a different module or multiple modules.
- You normally do not need to recreate the Gradle Wrapper for every build or commit.
- The wrapper can download its configured Gradle distribution when network access is available.
- `clean` removes generated build outputs, not source files.
- `--refresh-dependencies` rechecks dependency resolution; it is not a command to erase global caches.
- Debug APKs are for development and testing. Use a properly signed release build for distribution.
- The APK installed on a phone is separate from the APK file stored on the computer.
- A normal build can overwrite the APK in the build output directory. Use Section 17 to keep separate archived copies.

---

## 17. Universal APK Builder & Version Archiver

This optional PowerShell script works across Android projects that use the Gradle Wrapper. It asks for the project path, module, and build variant, runs the matching Gradle assemble task, and copies the resulting APK files into a project-local archive.

### What it does

- Works with different Android project directories; it is not tied to one repository.
- Supports standard `debug` and `release` variants.
- Lets you specify the module (default: `app`).
- Archives APKs only after Gradle reports a successful build.
- Assigns a sequential build number and preserves earlier archived copies.
- Records the timestamp, Git commit (when available), APK size, and SHA-256 hash in a JSON history file.

### 17.1 Save the script

Save the following as `Universal-Android-Builder.ps1`:

```powershell
$ErrorActionPreference = "Stop"

Write-Host "`n=== UNIVERSAL ANDROID APK BUILDER ===" -ForegroundColor Cyan

# Select project
$ProjectInput = Read-Host "Project root path (Enter = current directory)"
if ([string]::IsNullOrWhiteSpace($ProjectInput)) {
    $ProjectInput = (Get-Location).Path
}
if (!(Test-Path -LiteralPath $ProjectInput -PathType Container)) {
    throw "Project directory does not exist."
}
$Project = (Resolve-Path -LiteralPath $ProjectInput).Path

# Locate wrapper
$Wrapper = Join-Path $Project "gradlew.bat"
if (!(Test-Path -LiteralPath $Wrapper -PathType Leaf)) {
    throw "gradlew.bat was not found. Select the project root."
}

# Project settings
$Module = Read-Host "Android module (default: app)"
if ([string]::IsNullOrWhiteSpace($Module)) { $Module = "app" }
$Module = $Module.Trim().Trim(":")

$Variant = Read-Host "Variant: debug or release (default: debug)"
if ([string]::IsNullOrWhiteSpace($Variant)) { $Variant = "debug" }
$Variant = $Variant.Trim().ToLowerInvariant()
if ($Variant -notin @("debug", "release")) {
    throw "This script supports standard debug and release variants."
}

# Convert Gradle module notation to a filesystem path
$ModulePath = $Module.Replace(":", "\")
$ModuleDirectory = Join-Path $Project $ModulePath
if (!(Test-Path -LiteralPath $ModuleDirectory -PathType Container)) {
    throw "Module directory not found: $ModuleDirectory"
}

$VariantTitle = (Get-Culture).TextInfo.ToTitleCase($Variant)
$Task = ":$($Module.Replace('\', ':')):assemble$VariantTitle"
$OutputDirectory = Join-Path $ModuleDirectory "build\outputs\apk\$Variant"

$ProjectName = (Split-Path $Project -Leaf) -replace '[^A-Za-z0-9._-]', '_'
$ArchiveDirectory = Join-Path $Project "apk-archive"
$HistoryPath = Join-Path $ArchiveDirectory "build-history.json"

# Build
Write-Host "`nProject: $ProjectName"
Write-Host "Module:  $Module"
Write-Host "Variant: $Variant"
Write-Host "Task:    $Task"
Write-Host "`nRunning Gradle build..." -ForegroundColor Yellow

Push-Location $Project
try {
    & $Wrapper $Task
    if ($LASTEXITCODE -ne 0) {
        throw "Gradle build failed. Nothing was archived."
    }
}
finally {
    Pop-Location
}

# Locate APK outputs
if (!(Test-Path -LiteralPath $OutputDirectory -PathType Container)) {
    throw "Expected APK output directory was not created: $OutputDirectory"
}
$Apks = @(Get-ChildItem -LiteralPath $OutputDirectory -Filter "*.apk" -File -Recurse)
if ($Apks.Count -eq 0) {
    throw "Build succeeded, but no APK files were found."
}

# Prepare archive and history
New-Item -ItemType Directory -Path $ArchiveDirectory -Force | Out-Null

$History = @()
if (Test-Path -LiteralPath $HistoryPath) {
    $RawHistory = Get-Content -LiteralPath $HistoryPath -Raw
    if (-not [string]::IsNullOrWhiteSpace($RawHistory)) {
        $History = @($RawHistory | ConvertFrom-Json)
    }
}

# Number builds independently per project + module + variant
$Key = "$ProjectName|$Module|$Variant"
$PreviousNumbers = @(
    foreach ($Entry in $History) {
        if ($Entry.Key -eq $Key) { [int]$Entry.BuildNumber }
    }
)
$NextNumber = if ($PreviousNumbers.Count) {
    ($PreviousNumbers | Measure-Object -Maximum).Maximum + 1
} else {
    1
}
$BuildNumber = "{0:D3}" -f $NextNumber
$Timestamp = Get-Date -Format "yyyy-MM-ddTHH:mm:ssK"

# Read Git commit when this project is a Git working tree
$GitCommit = $null
$GitRoot = git -C $Project rev-parse --show-toplevel 2>$null
if ($LASTEXITCODE -eq 0 -and $GitRoot) {
    $GitCommit = git -C $Project rev-parse --short HEAD 2>$null
    if ($LASTEXITCODE -ne 0) { $GitCommit = $null }
}

# Copy and hash each APK
$ArchivedApks = @()
foreach ($Apk in $Apks) {
    $RelativeName = $Apk.FullName.Substring($OutputDirectory.Length).TrimStart('\')
    $SafeName = $RelativeName -replace '[\\/]', '_'
    $ArchiveName = "$ProjectName-$Variant-$BuildNumber-$SafeName"
    $Destination = Join-Path $ArchiveDirectory $ArchiveName

    if (Test-Path -LiteralPath $Destination) {
        throw "Archive destination already exists: $Destination"
    }

    Copy-Item -LiteralPath $Apk.FullName -Destination $Destination
    $ArchivedFile = Get-Item -LiteralPath $Destination
    $Hash = (Get-FileHash -LiteralPath $Destination -Algorithm SHA256).Hash

    $ArchivedApks += [PSCustomObject]@{
        File = $ArchiveName
        SizeBytes = $ArchivedFile.Length
        SizeMB = [math]::Round($ArchivedFile.Length / 1MB, 2)
        SHA256 = $Hash
    }
}

# Save history after successful copies
$Record = [PSCustomObject]@{
    Key = $Key
    Project = $ProjectName
    Module = $Module
    Variant = $Variant
    BuildNumber = [int]$NextNumber
    Timestamp = $Timestamp
    GitCommit = $GitCommit
    APKs = $ArchivedApks
}
$History += $Record

$History | ConvertTo-Json -Depth 8 |
    Set-Content -LiteralPath $HistoryPath -Encoding UTF8

Write-Host "`nBUILD AND ARCHIVE SUCCESSFUL" -ForegroundColor Green
Write-Host "Build number: $BuildNumber"
Write-Host "Archive:      $ArchiveDirectory"
Write-Host "History:      $HistoryPath"
$ArchivedApks | Format-Table File, SizeMB, SHA256 -AutoSize
```

### 17.2 Run the script

From PowerShell:

```powershell
powershell -ExecutionPolicy Bypass -File "C:\Path\To\Universal-Android-Builder.ps1"
```

Enter the Android project root when prompted. Press Enter to use the current directory. Then enter the module and variant, or accept the defaults.

### 17.3 Example archive

The script creates this folder inside each project:

```text
YourAndroidProject/
├── app/
│   └── build/
│       └── outputs/
│           └── apk/
│               └── debug/
│                   └── app-debug.apk
└── apk-archive/
    ├── YourAndroidProject-debug-001-app-debug.apk
    ├── YourAndroidProject-debug-002-app-debug.apk
    ├── YourAndroidProject-debug-003-app-debug.apk
    └── build-history.json
```

### 17.4 Important archiving notes

- The archive is local to each project; different projects maintain separate archives.
- The script does not intentionally overwrite an existing archived APK.
- Gradle's normal output APK may still be overwritten by later builds; the archive preserves copies.
- Archive build numbers are not Android `versionCode` or `versionName`.
- Standard debug and release variants are supported. Projects with product flavors may need a flavor-specific task and output path.
- This script archives APKs, not Android App Bundles (`.aab`).
- If a build fails, the script stops before copying APKs.
- Keep a separate backup of important archives. The archive is stored inside the project and may be lost if the project directory is deleted.

---

## Quick command selector

| Task | Command |
|---|---|
| Build debug APK | `.\gradlew.bat :app:assembleDebug` |
| Pull latest changes | `git pull --ff-only` |
| Clean and build | `.\gradlew.bat clean :app:assembleDebug` |
| Refresh dependencies | `.\gradlew.bat :app:assembleDebug --refresh-dependencies` |
| Disable build cache | `.\gradlew.bat :app:assembleDebug --no-build-cache` |
| Run unit tests | `.\gradlew.bat :app:testDebugUnitTest` |
| Build and install | `.\gradlew.bat :app:installDebug` |
| List devices | `adb devices` |
| Gradle information | `.\gradlew.bat --version` |
| List tasks | `.\gradlew.bat tasks` |

**Recommended routine:** Check Git status, pull when needed, build, run tests, and install on a device. Use the archiver when you want to keep a numbered APK snapshot.
