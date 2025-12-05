
$localAppData = $env:LOCALAPPDATA


 $toolsPath = Join-Path $localAppData "Android\Sdk\platform-tools"


$adbCommand = Join-Path $toolsPath "adb.exe"

#
if (Test-Path $adbCommand) {
    Write-Host "ADB found at: $adbCommand" -ForegroundColor Green
    

     & $adbCommand shell dumpsys batterystats --reset
    
    Write-Host "Start battery stats tracking." -ForegroundColor Cyan

    Start-Sleep -Seconds 120

     & $adbCommand shell dumpsys batterystats >battery_stats.txt

      Write-Host "End battery stats tracking." -ForegroundColor Cyan

} else {
    Write-Error "Error: adb.exe not found. Check path: $adbCommand"
}