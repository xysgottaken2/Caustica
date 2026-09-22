param(
	[int]$TargetPid = 0,
	[string]$RecordingName = "mc",
	[string]$Settings = "profile"
)

$ErrorActionPreference = "Stop"

$jcmdCommand = Get-Command jcmd -ErrorAction SilentlyContinue
if ($null -eq $jcmdCommand -and $env:JAVA_HOME) {
	$javaHomeJcmd = Join-Path $env:JAVA_HOME "bin\jcmd.exe"
	if (Test-Path -LiteralPath $javaHomeJcmd) {
		$jcmdCommand = Get-Command $javaHomeJcmd
	}
}

if ($null -eq $jcmdCommand) {
	throw "Could not find jcmd. Put a JDK bin directory on PATH or set JAVA_HOME."
}

$jcmdPath = $jcmdCommand.Source

function Invoke-Jcmd {
	param(
		[Parameter(ValueFromRemainingArguments = $true)]
		[string[]]$JcmdArgs
	)

	& $jcmdPath @JcmdArgs
	if ($LASTEXITCODE -ne 0) {
		throw "jcmd failed: jcmd $($JcmdArgs -join ' ')"
	}
}

function Get-JcmdProcessList {
	$lines = & $jcmdPath
	if ($LASTEXITCODE -ne 0) {
		throw "jcmd failed while listing JVMs"
	}

	foreach ($line in $lines) {
		if ($line -match '^\s*(\d+)\s+(.+?)\s*$') {
			[pscustomobject]@{
				Id = [int]$matches[1]
				Command = $matches[2]
				Raw = $line
			}
		}
	}
}

$processes = @(Get-JcmdProcessList)

if ($TargetPid -ne 0) {
	$target = $processes | Where-Object { $_.Id -eq $TargetPid } | Select-Object -First 1
	if ($null -eq $target) {
		throw "Could not find JVM with PID $TargetPid."
	}
} else {
	$candidates = @(
		$processes | Where-Object {
			$_.Command -match 'net\.fabricmc\.devlaunchinjector\.Main|net\.minecraft\.client\.main\.Main|Minecraft' -and
			$_.Command -notmatch 'Gradle|GradleDaemon|GradleWrapperMain|jdk\.jcmd|JCmd|JMC'
		}
	)

	if ($candidates.Count -eq 0) {
		Write-Host "No Minecraft JVM found. Current jcmd output:"
		$processes | ForEach-Object { Write-Host "  $($_.Raw)" }
		throw "Start the Minecraft client first, or pass -TargetPid <pid>."
	}

	if ($candidates.Count -eq 1) {
		$target = $candidates[0]
	} else {
		Write-Host "Multiple Minecraft-like JVMs found:"
		for ($i = 0; $i -lt $candidates.Count; $i++) {
			Write-Host ("  [{0}] {1}" -f ($i + 1), $candidates[$i].Raw)
		}

		$selection = Read-Host "Select target [1]"
		if ([string]::IsNullOrWhiteSpace($selection)) {
			$selection = "1"
		}

		$index = [int]$selection - 1
		if ($index -lt 0 -or $index -ge $candidates.Count) {
			throw "Invalid selection: $selection"
		}

		$target = $candidates[$index]
	}
}

$timestamp = Get-Date -Format "yyyyMMdd-HHmmss"
$outDir = Join-Path $PSScriptRoot "run/jfr"
New-Item -ItemType Directory -Force -Path $outDir | Out-Null

$jfrPath = Join-Path $outDir "$RecordingName-$timestamp.jfr"
$targetPidText = [string]$target.Id
$started = $false

Write-Host "Target JVM: $($target.Raw)"
Write-Host "Recording:  $jfrPath"

try {
	Invoke-Jcmd $targetPidText "JFR.start" "name=$RecordingName" "settings=$Settings" "filename=$jfrPath"
	$started = $true
	Write-Host ""
	Read-Host "Profiling started. Press Enter to stop"
} finally {
	if ($started) {
		Write-Host "Stopping JFR recording..."
		try {
			Invoke-Jcmd $targetPidText "JFR.stop" "name=$RecordingName" "filename=$jfrPath"
			Write-Host "Saved JFR recording to $jfrPath"
		} catch {
			Write-Warning $_.Exception.Message
			Write-Warning "The target JVM may have exited before the recording was stopped."
		}
	}
}
