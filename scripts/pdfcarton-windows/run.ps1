param([switch]$Preflight)
$ErrorActionPreference = 'Stop'
function Get-Environment {
    if (-not $IsWindows -or [System.Runtime.InteropServices.RuntimeInformation]::OSArchitecture -ne 'X64') { throw 'Windows x64 is required' }
    $sdk = & dotnet --list-sdks
    if ($LASTEXITCODE -ne 0 -or -not ($sdk -match '^10\.\d+\.\d+ \[')) { throw '.NET 10 SDK is required' }
    $runtimes = & dotnet --list-runtimes
    if ($LASTEXITCODE -ne 0 -or -not ($runtimes -match '^Microsoft\.NETCore\.App 10\.\d+\.\d+ \[')) { throw '.NET 10 runtime is required' }
    $framework = (Get-ItemProperty 'HKLM:\SOFTWARE\Microsoft\NET Framework Setup\NDP\v4\Full' -Name Release).Release
    if ($framework -lt 533320) { throw '.NET Framework 4.8.1 runtime is required' }
    $os = Get-CimInstance Win32_OperatingSystem
    $cpu = Get-CimInstance Win32_ComputerSystem
    $environment = [ordered]@{os=$os.Caption; version=$os.Version; architecture='X64'; cpu=$cpu.NumberOfLogicalProcessors; totalGiB=$cpu.TotalPhysicalMemory/1GB; availableGiB=$os.FreePhysicalMemory/1MB; diskGiB=(Get-PSDrive C).Free/1GB; sdk=$sdk; runtimes=$runtimes; frameworkRelease=$framework}
    if ($environment.diskGiB -lt 10 -or $environment.availableGiB -lt 2) { throw "Insufficient Windows capacity: $($environment | ConvertTo-Json -Compress)" }
    return $environment
}
if ($Preflight) { Get-Environment | ConvertTo-Json -Depth 5; exit 0 }
$runRoot = Split-Path $PSScriptRoot -Parent
$output = Join-Path $runRoot 'o'
New-Item -ItemType Directory -Force $output | Out-Null
$request = Get-Content -Raw (Join-Path $PSScriptRoot 'request.json') | ConvertFrom-Json
$steps = [System.Collections.Generic.List[object]]::new()
$result = [ordered]@{runId=$request.runId; inputDigest=$request.inputDigest; mode=$request.mode; version=$request.version; commit=$request.commit; status='failed'; exitCode=1}
$env:DOTNET_CLI_HOME = Join-Path $runRoot 'w/h'
$env:NUGET_PACKAGES = Join-Path $runRoot 'w/p'
$env:DOTNET_NOLOGO = '1'
$env:DOTNET_SKIP_FIRST_TIME_EXPERIENCE = '1'
$env:DOTNET_CLI_TELEMETRY_OPTOUT = '1'
# Guest resources, never inherit the Mac generator's 22 workers or JVM heap.
Remove-Item Env:DRIPSHARP_WORKERS,Env:JAVA_TOOL_OPTIONS -ErrorAction SilentlyContinue
function Invoke-Step([string]$Name, [string]$File, [string[]]$Arguments) {
    $watch = [Diagnostics.Stopwatch]::StartNew()
    $process = [Diagnostics.Process]::new()
    $process.StartInfo = [Diagnostics.ProcessStartInfo]::new($File)
    $process.StartInfo.WorkingDirectory = $PSScriptRoot
    $process.StartInfo.UseShellExecute = $false
    $process.StartInfo.RedirectStandardOutput = $true
    $process.StartInfo.RedirectStandardError = $true
    foreach ($arg in $Arguments) { $process.StartInfo.ArgumentList.Add($arg) }
    [void]$process.Start()
    $stdout = $process.StandardOutput.ReadToEndAsync()
    $stderr = $process.StandardError.ReadToEndAsync()
    if (-not $process.WaitForExit(600000)) { $process.Kill($true); $process.WaitForExit(); $code = 124 } else { $code = $process.ExitCode }
    [IO.File]::WriteAllText((Join-Path $output "$Name.stdout.log"), $stdout.GetAwaiter().GetResult())
    [IO.File]::WriteAllText((Join-Path $output "$Name.stderr.log"), $stderr.GetAwaiter().GetResult())
    $steps.Add([pscustomobject]@{name=$Name; exitCode=$code; seconds=$watch.Elapsed.TotalSeconds})
    $steps | ConvertTo-Json | Set-Content (Join-Path $output 'steps.json')
    Write-Host "$Name exit=$code seconds=$($watch.Elapsed.TotalSeconds)"
    if ($code -ne 0) { throw "$Name failed with exit code $code" }
}
function Check-Package([string]$Package, [bool]$Exact) {
    $hash = (Get-FileHash $Package -Algorithm SHA256).Hash.ToLowerInvariant()
    if ($Exact -and $hash -ne $request.packageSHA256) { throw 'Candidate archive identity mismatch (same version is not sufficient)' }
    $zip = [IO.Compression.ZipFile]::OpenRead($Package)
    try {
        $entry = @($zip.Entries | Where-Object { $_.FullName.EndsWith('.nuspec') })
        if ($entry.Count -ne 1) { throw 'Expected one nuspec' }
        $reader = [IO.StreamReader]::new($entry[0].Open())
        try { [xml]$nuspec = $reader.ReadToEnd() } finally { $reader.Dispose() }
        $metadata = $nuspec.package.metadata
        if ($metadata.id -cne 'DripSharp.PdfCarton' -or $metadata.version -cne $request.version -or $metadata.repository.commit -cne $request.commit) { throw 'Package ID/version/product commit mismatch' }
        $assemblies = @($zip.Entries | Where-Object { $_.FullName -match '^lib/netstandard2.0/DripSharp.PdfCarton(\.(IO|Fonts|Xmp|Preflight))?\.dll$' })
        if ($assemblies.Count -ne 5) { throw 'Missing netstandard2.0 five-assembly package contract' }
    } finally { $zip.Dispose() }
    [ordered]@{sha256=$hash; id=$metadata.id; version=$metadata.version; commit=$metadata.repository.commit; source=$request.mode} | ConvertTo-Json | Set-Content (Join-Path $output 'package.json')
}
try {
    Get-Environment | ConvertTo-Json -Depth 5 | Set-Content (Join-Path $output 'environment.json')
    if (Test-Path $env:NUGET_PACKAGES) { throw 'Fresh isolated package cache required' }
    if ($request.mode -eq 'candidate') { Check-Package (Join-Path $PSScriptRoot ('feed/'+$request.packageFile)) $true }
    Set-Location $PSScriptRoot
    $dotnet = (Get-Command dotnet).Source
    $failures = [System.Collections.Generic.List[string]]::new()
    foreach ($case in @('smoke','legacy')) {
      try {
        $project = "$case/$case.csproj"
        Invoke-Step "$case-restore" $dotnet @('restore',$project,'--configfile','NuGet.Config','--packages',$env:NUGET_PACKAGES,'--no-cache','--force')
        $package = Join-Path $env:NUGET_PACKAGES ("dripsharp.pdfcarton/$($request.version)/dripsharp.pdfcarton.$($request.version).nupkg")
        Check-Package $package ($request.mode -eq 'candidate')
        Invoke-Step "$case-build" $dotnet @('build',$project,'-c','Release','--no-restore','--no-incremental','-warnaserror','-m:1','-p:UseSharedCompilation=false')
        if ($case -eq 'smoke') {
            Invoke-Step 'smoke-execute' $dotnet @('test',$project,'-c','Release','--no-restore','--no-build','--logger','trx;LogFileName=smoke.trx','--results-directory',$output)
        } else {
            Invoke-Step 'legacy-execute' (Join-Path $PSScriptRoot 'legacy/bin/Release/net481/legacy.exe') @()
        }
      } catch { $failures.Add($_.ToString()); $_ | Out-String | Set-Content (Join-Path $output ($case+'-error.log')) }
    }
    if ($failures.Count) { throw ($failures -join '; ') }
    $result.status = 'passed'
    $result.exitCode = 0
} catch { $_ | Out-String | Set-Content (Join-Path $output 'runner-error.log') }
finally {
    $result | ConvertTo-Json -Depth 5 | Set-Content (Join-Path $output 'result.json')
    $result.exitCode | Set-Content (Join-Path $output 'exit-code.txt')
    $record = Get-Content (Join-Path $runRoot 'run.json') -Raw | ConvertFrom-Json
    $record.state = 'completed'
    $record | Add-Member ended ([DateTime]::UtcNow.ToString('o')) -Force
    $record | Add-Member exitCode $result.exitCode -Force
    $record | ConvertTo-Json | Set-Content (Join-Path $runRoot 'run.json')
}
exit $result.exitCode
