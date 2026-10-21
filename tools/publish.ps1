# Publish snake-2d: commit -> GitHub repo -> push -> release with APK.
$ErrorActionPreference = 'Continue'
[Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12
Set-Location 'D:\snake'

$tok = (Get-Content "$env:TEMP\dsh_gh_token.txt" -Raw).Trim()
$login = (Get-Content "$env:TEMP\dsh_gh_login.txt" -Raw).Trim()
$repo = 'snake-game'
$headers = @{ Authorization = "Bearer $tok"; 'User-Agent' = 'dsh'; Accept = 'application/vnd.github+json' }

function Run-Git {
    param([string[]]$GitArgs)
    $out = & git @GitArgs 2>&1
    return @{ Code = $LASTEXITCODE; Out = $out }
}

Write-Host "== commit =="
Run-Git @('add', '-A') | Out-Null
$msgFile = Join-Path $env:TEMP 'dsh_commit_msg.txt'
$msg = "Snake 2D - Android (pure Java, no Gradle)`n`n4 modes: CHOI (waypoint editor) / AUTO WIN (grid region + BFS) / SHORT PATH / MANGO (random Hamiltonian cycle).`nBiting itself = one death, the tail is cut and the run continues.`n"
[System.IO.File]::WriteAllText($msgFile, $msg, (New-Object System.Text.UTF8Encoding($false)))
$head = Run-Git @('rev-parse', '--verify', 'HEAD')
if ($head.Code -eq 0) {
    Run-Git @('-c', 'i18n.commitEncoding=utf-8', 'commit', '-q', '--amend', '-F', $msgFile) | Out-Null
} else {
    Run-Git @('-c', 'i18n.commitEncoding=utf-8', 'commit', '-q', '-F', $msgFile) | Out-Null
}
(Run-Git @('log', '--oneline', '-1')).Out

Write-Host "== repo =="
$existing = $null
try { $existing = Invoke-RestMethod -Uri "https://api.github.com/repos/$login/$repo" -Headers $headers } catch { $existing = $null }
if ($existing) {
    Write-Host "repo: $($existing.html_url)"
} else {
    $body = @{
        name        = $repo
        description = 'Snake 2D for Android - pure Java (no Gradle), 4 modes incl. waypoint editor and auto-play AI'
        private     = $false
        has_issues  = $true
        has_wiki    = $false
    } | ConvertTo-Json
    $existing = Invoke-RestMethod -Method Post -Uri 'https://api.github.com/user/repos' -Headers $headers -Body $body -ContentType 'application/json'
    Write-Host "created: $($existing.html_url)"
}

Write-Host "== push =="
$env:GIT_TERMINAL_PROMPT = '0'
$r = Run-Git @('remote', 'set-url', 'origin', "https://github.com/$login/$repo.git")
if ($r.Code -ne 0) { $r = Run-Git @('remote', 'add', 'origin', "https://github.com/$login/$repo.git") }
$r = Run-Git @('push', '-u', 'origin', 'main')
if ($r.Code -ne 0) {
    Write-Host "credential helper failed, retrying with a token url"
    Run-Git @('remote', 'set-url', 'origin', "https://x-access-token:$tok@github.com/$login/$repo.git") | Out-Null
    $r = Run-Git @('push', '-u', 'origin', 'main')
    Run-Git @('remote', 'set-url', 'origin', "https://github.com/$login/$repo.git") | Out-Null
}
if ($r.Code -ne 0) {
    Write-Host "PUSH FAILED"
    $r.Out | Select-Object -Last 6
    exit 2
}
$r.Out | Select-Object -Last 3

Write-Host "== release =="
$rel = $null
try { $rel = Invoke-RestMethod -Uri "https://api.github.com/repos/$login/$repo/releases/tags/v1.0.0" -Headers $headers } catch { $rel = $null }
if (-not $rel) {
    $notesFile = Join-Path $env:TEMP 'dsh_release_notes.md'
    $notes = @"
## Snake 2D v1.0.0 (Android)

APK ký debug, cài trực tiếp: **snake-2d.apk** (Android 8+).

### 4 chế độ
- **CHƠI** - điều khiển bằng waypoint: chạm board để đặt điểm, 4 phím mũi tên + nút ✕ để dịch/xoá,
  hộp thoại danh sách **kéo-thả** đổi thứ tự, nút **AUTO** tự đặt waypoint theo đường ngắn nhất tới quả,
  nút **REPLAY** phát lại toàn bộ lịch sử waypoint, bóng rắn cam mờ hiện vị trí đầu/đuôi dự kiến.
- **AUTO WIN** - AI tìm đường trong vùng grid (min/max của vùng trống + quả), BFS + kiểm tra đuôi,
  chống lặp vô tận bằng cách nới vùng min/max dần tới toàn board, luôn thắng.
- **SHORT PATH** - BFS đường ngắn nhất tới quả (virtual-eat, kiểm tra đuôi, bám đuôi khi bí).
- **MANGO** - chu trình Hamilton ngẫu nhiên mỗi ván (split + mend 2-opt).

### Luật chơi
- Cắn vào thân: cắt bỏ đoạn từ chỗ đâm tới đuôi, tính **1 lần chết**, đổi màu rắn, **chạy tiếp**.
- Chỉ đâm tường mới kết thúc ván.
- Lấp kín board = **CHIẾN THẮNG**.

### Build
`powershell -ExecutionPolicy Bypass -File build.ps1 -Deploy` (JDK 17+, Android SDK 35, adb).
"@
    [System.IO.File]::WriteAllText($notesFile, $notes, (New-Object System.Text.UTF8Encoding($false)))
    $relBody = @{
        tag_name         = 'v1.0.0'
        target_commitish = 'main'
        name             = 'Snake 2D v1.0.0'
        body             = [System.IO.File]::ReadAllText($notesFile)
        draft            = $false
        prerelease       = $false
    } | ConvertTo-Json -Depth 4
    $rel = Invoke-RestMethod -Method Post -Uri "https://api.github.com/repos/$login/$repo/releases" `
        -Headers $headers -Body ([System.Text.Encoding]::UTF8.GetBytes($relBody)) -ContentType 'application/json; charset=utf-8'
    Write-Host "release created: $($rel.html_url)"
} else {
    Write-Host "release v1.0.0 already exists: $($rel.html_url)"
}

Write-Host "== upload apk =="
$upload = ($rel.upload_url -replace '\{\?name,label\}', '') + '?name=snake-2d.apk'
try {
    $asset = Invoke-RestMethod -Method Post -Uri $upload -Headers $headers -InFile 'D:\snake\build\snake-2d.apk' -ContentType 'application/vnd.android.package-archive'
    Write-Host "asset: $($asset.browser_download_url)"
    Write-Host "asset size: $($asset.size) bytes"
} catch {
    Write-Host ("upload error: " + $_.Exception.Message)
}

Write-Host ""
Write-Host "REPO   : https://github.com/$login/$repo"
Write-Host "RELEASE: $($rel.html_url)"
