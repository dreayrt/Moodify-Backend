param(
    [ValidateSet('preview', 'apply', 'rollback')]
    [string]$Mode = 'preview',
    [string]$PreviewFile,
    [string]$ReviewedFile,
    [string]$ManifestFile,
    [string]$ModelProfileSource,
    [Nullable[double]]$MaxDistance,
    [Nullable[double]]$MinMargin,
    [ValidateRange(0.0, 1.0)]
    [double]$MinFeatureCoverage = 1.0
)

$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$previousLocation = Get-Location
$profilesFile = Join-Path ([System.IO.Path]::GetTempPath()) ("moodify-model-profiles-{0}.json" -f [guid]::NewGuid().ToString('N'))

function Resolve-RequiredPath([string]$Path, [string]$Name) {
    if ([string]::IsNullOrWhiteSpace($Path)) {
        throw "Provide -$Name."
    }
    return (Resolve-Path -LiteralPath $Path).Path
}

function Resolve-OutputPath([string]$Path) {
    if ([string]::IsNullOrWhiteSpace($Path)) {
        throw 'Provide an output path.'
    }
    $fullPath = [System.IO.Path]::GetFullPath($Path)
    $parent = Split-Path -Parent $fullPath
    if (-not [string]::IsNullOrWhiteSpace($parent)) {
        New-Item -ItemType Directory -Force -Path $parent | Out-Null
    }
    return $fullPath
}

try {
    Set-Location $projectRoot

    $runner = Join-Path $projectRoot 'mvnw.cmd'
    if (-not (Test-Path -LiteralPath $runner)) {
        throw "Maven wrapper not found: $runner"
    }

    $arguments = @(
        '--spring.main.web-application-type=NONE',
        '--spring.jpa.hibernate.ddl-auto=none',
        '--app.seed.tracks-on-startup=false',
        '--app.scheduling.enabled=false',
        '--app.maintenance.enabled=false',
        '--sync.scheduler.enabled=false',
        "--mood-tags.command=$Mode"
    )

    switch ($Mode) {
        'preview' {
            if ([string]::IsNullOrWhiteSpace($PreviewFile)) {
                $stamp = Get-Date -Format 'yyyyMMdd-HHmmss'
                $PreviewFile = Join-Path $projectRoot "docs/mood-tags-preview-$stamp.json"
            }
            $PreviewFile = Resolve-OutputPath $PreviewFile
            if (Test-Path -LiteralPath $PreviewFile) {
                throw "Preview file already exists; choose another path: $PreviewFile"
            }
            if ($MaxDistance.HasValue -xor $MinMargin.HasValue) {
                throw 'Set both -MaxDistance and -MinMargin, or leave both unset for ranking-only preview.'
            }
            if ($MaxDistance.HasValue -and ($MaxDistance.Value -lt 0 -or $MaxDistance.Value -gt 1)) {
                throw '-MaxDistance must be between 0 and 1.'
            }
            if ($MinMargin.HasValue -and ($MinMargin.Value -lt 0 -or $MinMargin.Value -gt 1)) {
                throw '-MinMargin must be between 0 and 1.'
            }

            $exportArgs = @('scripts/export_model_mood_profiles.py', '--output', $profilesFile)
            if (-not [string]::IsNullOrWhiteSpace($ModelProfileSource)) {
                $exportArgs += @('--source', (Resolve-RequiredPath $ModelProfileSource 'ModelProfileSource'))
            }
            & python @exportArgs
            if ($LASTEXITCODE -ne 0) {
                throw "Model profile export failed with exit code $LASTEXITCODE."
            }

            $arguments += "--mood-tags.profiles-file=$profilesFile"
            $arguments += "--mood-tags.output=$PreviewFile"
            $arguments += "--mood-tags.min-feature-coverage=$MinFeatureCoverage"
            if ($MaxDistance.HasValue) {
                $arguments += "--mood-tags.max-distance=$($MaxDistance.Value)"
                $arguments += "--mood-tags.min-margin=$($MinMargin.Value)"
            }
            Write-Host "Creating read-only mood tag preview: $PreviewFile"
        }
        'apply' {
            $ReviewedFile = Resolve-RequiredPath $ReviewedFile 'ReviewedFile'
            $ManifestFile = Resolve-OutputPath $ManifestFile
            if (Test-Path -LiteralPath $ManifestFile) {
                throw "Manifest already exists; choose a new path: $ManifestFile"
            }

            $exportArgs = @('scripts/export_model_mood_profiles.py', '--output', $profilesFile)
            if (-not [string]::IsNullOrWhiteSpace($ModelProfileSource)) {
                $exportArgs += @('--source', (Resolve-RequiredPath $ModelProfileSource 'ModelProfileSource'))
            }
            & python @exportArgs
            if ($LASTEXITCODE -ne 0) {
                throw "Model profile export failed with exit code $LASTEXITCODE."
            }

            $arguments += "--mood-tags.profiles-file=$profilesFile"
            $arguments += "--mood-tags.reviewed-file=$ReviewedFile"
            $arguments += "--mood-tags.manifest=$ManifestFile"
            Write-Host 'Applying only the reviewed mood_tags values and saving a rollback manifest.'
        }
        'rollback' {
            $ManifestFile = Resolve-RequiredPath $ManifestFile 'ManifestFile'
            $arguments += "--mood-tags.manifest=$ManifestFile"
            Write-Host "Restoring mood_tags from manifest: $ManifestFile"
        }
    }

    $quotedArguments = foreach ($argument in $arguments) {
        if ($argument -match '\s|"') {
            '"' + $argument.Replace('"', '\"') + '"'
        } else {
            $argument
        }
    }
    $springArguments = $quotedArguments -join ' '
    & $runner "-Dspring-boot.run.arguments=$springArguments" 'spring-boot:run'
    if ($LASTEXITCODE -ne 0) {
        throw "Mood tag command failed with exit code $LASTEXITCODE."
    }
}
finally {
    if (Test-Path -LiteralPath $profilesFile) {
        Remove-Item -LiteralPath $profilesFile -Force
    }
    Set-Location $previousLocation
}
