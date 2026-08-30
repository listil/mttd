<#
mTTD 저장소가 10분마다 자동 갱신하는 하드코어 시세(tools/generate-ttd-price-hardcore.js 참고)를
받아서 로컬 TTD 앱 폴더의 ttd_price.json 을 덮어쓴다.

사용법 (파일을 따로 받을 필요 없이, PowerShell에 아래 한 줄만 붙여넣기 — "C:\Games\TTD" 부분만
본인의 TTD.exe 설치 폴더 경로로 바꾼다):

  $d="$env:LOCALAPPDATA\mTTD"; New-Item -ItemType Directory -Force -Path $d | Out-Null; irm https://raw.githubusercontent.com/listil/mttd/main/tools/update-ttd-price-local.ps1 -OutFile "$d\update-ttd-price-local.ps1"; & "$d\update-ttd-price-local.ps1" -TargetDir "C:\Games\TTD" -RegisterTask

한 번만 등록해두면 10분마다 자동으로 ttd_price.json 을 갱신한다 (터미널을 계속 켜둘 필요 없음).

저장소를 이미 로컬에 갖고 있다면 그냥 파일로 직접 실행해도 된다:
  powershell -ExecutionPolicy Bypass -File update-ttd-price-local.ps1 -TargetDir "C:\Games\TTD" -RegisterTask
  (RegisterTask 없이 실행하면 그 순간 1회만 갱신)

등록 해제하려면: Unregister-ScheduledTask -TaskName "mTTD-HardcorePriceSync" -Confirm:$false
#>
param(
    [Parameter(Mandatory = $true)]
    [string]$TargetDir,

    [switch]$RegisterTask,

    [int]$IntervalMinutes = 10
)

$Url = "https://raw.githubusercontent.com/listil/mttd/main/tools/generated/ttd_price.json"
$TaskName = "mTTD-HardcorePriceSync"

if ($RegisterTask) {
    $ScriptPath = $MyInvocation.MyCommand.Path
    $Action = New-ScheduledTaskAction -Execute "powershell.exe" `
        -Argument "-NoProfile -WindowStyle Hidden -ExecutionPolicy Bypass -File `"$ScriptPath`" -TargetDir `"$TargetDir`""
    $Trigger = New-ScheduledTaskTrigger -Once -At (Get-Date) `
        -RepetitionInterval (New-TimeSpan -Minutes $IntervalMinutes) `
        -RepetitionDuration ([TimeSpan]::MaxValue)
    Register-ScheduledTask -TaskName $TaskName -Action $Action -Trigger $Trigger `
        -Description "ETOR 하드코어 시세를 TTD 스타일로 받아 $TargetDir\ttd_price.json 자동 교체" `
        -Force | Out-Null
    Write-Output "등록 완료: $IntervalMinutes 분마다 자동 실행됩니다 (작업 스케줄러 '$TaskName')."
    exit
}

if (-not (Test-Path $TargetDir)) {
    Write-Error "대상 폴더가 없습니다: $TargetDir"
    exit 1
}

$Dest = Join-Path $TargetDir "ttd_price.json"
try {
    Invoke-WebRequest -Uri $Url -OutFile $Dest -UseBasicParsing
    Write-Output "$(Get-Date -Format 'yyyy-MM-dd HH:mm:ss') 갱신 완료: $Dest"
} catch {
    Write-Error "다운로드 실패: $_"
    exit 1
}
