# Cobblify SELF-HOSTED stats backend setup (Windows helper, run by setup-windows.bat).
# Only needed with a blank public-Release jar or a from-source build - official
# (privately distributed) builds ship with the backend built in and skip this
# entirely. Deploys your own free Cloudflare Worker and prints the
# /cobblify statsurl + statstoken chat commands that point the mod at it.

$ErrorActionPreference = 'Stop'
[Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12

$NodeVersion = 'v22.23.0'
$NodePlat    = 'win-x64'   # the x64 build also runs fine on Windows-on-ARM via emulation
$RepoZip     = 'https://github.com/mrcobbert/Cobblify/archive/refs/heads/main.zip'
$Work        = Join-Path $env:USERPROFILE '.bedwarsqol-setup'

function Say($m) { Write-Host "`n$m" -ForegroundColor Cyan }
# PS 5.1 quirk: capturing a native command's stderr (2>&1) while ErrorActionPreference is
# 'Stop' turns ANY stderr line into a terminating error. Relax it just for these captures.
function Capture([string[]]$a) {
  $old = $ErrorActionPreference
  $ErrorActionPreference = 'Continue'
  try { return (& $npx @a 2>&1 | Out-String) } finally { $ErrorActionPreference = $old }
}
function CaptureStdout([string[]]$a) {
  $old = $ErrorActionPreference
  $ErrorActionPreference = 'Continue'
  try { return (& $npx @a 2>$null | Out-String) } finally { $ErrorActionPreference = $old }
}
function Ok($m)  { Write-Host $m -ForegroundColor Green }
function Die($m) {
  Write-Host "`nSetup failed: $m`n" -ForegroundColor Red
  Write-Host 'Ask in the Discord for help and copy the red text above.'
  exit 1
}

# Pixel-art banner (61 cols wide - safe in an 80-col conhost window).
# File stays pure ASCII: block glyphs are built from char codes at runtime.
function Show-Banner {
  $B = [string][char]0x2588   # full block
  $rows = @(
    ' ###   ###  ####  ####  #     ### ##### #   #',
    '#     #   # #   # #   # #      #  #      # # ',
    '#     #   # ####  ####  #      #  ####    #  ',
    '#     #   # #   # #   # #      #  #       #  ',
    ' ###   ###  ####  ####  ##### ### #       #  '
  )
  $pad    = ''
  $tagPad = ''
  $rule   = [string][char]0x2500 * 8   # thin horizontal line

  # Try to enable VT processing so truecolor ANSI works in conhost.
  $vt = $false
  try {
    Add-Type -Namespace BwqolWin32 -Name Vt -MemberDefinition @'
[DllImport("kernel32.dll")] public static extern System.IntPtr GetStdHandle(int nStdHandle);
[DllImport("kernel32.dll")] public static extern bool GetConsoleMode(System.IntPtr hConsoleHandle, out uint lpMode);
[DllImport("kernel32.dll")] public static extern bool SetConsoleMode(System.IntPtr hConsoleHandle, uint dwMode);
'@ -ErrorAction Stop
    $h = [BwqolWin32.Vt]::GetStdHandle(-11)
    $mode = [uint32]0
    if ([BwqolWin32.Vt]::GetConsoleMode($h, [ref]$mode)) {
      $vt = [BwqolWin32.Vt]::SetConsoleMode($h, $mode -bor 4)
    }
  } catch {}

  Write-Host ''
  if ($vt) {
    $e = [char]27
    $r = "$e[0m"
    $shades = @('235;235;235', '205;205;205', '175;175;175', '145;145;145', '115;115;115')
    for ($i = 0; $i -lt $rows.Count; $i++) {
      Write-Host ("$pad$e[38;2;$($shades[$i])m" + ($rows[$i] -replace '#', $B) + $r)
    }
    Write-Host ''
    Write-Host ("$tagPad$e[38;2;100;100;100m$rule$e[38;2;190;190;190m  Created by MrCobbert  $e[38;2;100;100;100m$rule$r")
  } else {
    # No VT support: approximate the greyscale gradient with console colors.
    $shades = @('White', 'Gray', 'Gray', 'DarkGray', 'DarkGray')
    for ($i = 0; $i -lt $rows.Count; $i++) {
      Write-Host ($pad + ($rows[$i] -replace '#', $B)) -ForegroundColor $shades[$i]
    }
    Write-Host ''
    Write-Host ("$tagPad$rule  Created by MrCobbert  $rule") -ForegroundColor DarkGray
  }
  Write-Host ''
}

Show-Banner

New-Item -ItemType Directory -Force -Path $Work | Out-Null
Set-Location $Work

# 1. Private Node.js
$NodeDir = Join-Path $Work "node-$NodeVersion-$NodePlat"
if (-not (Test-Path (Join-Path $NodeDir 'node.exe'))) {
  Say 'Downloading a private copy of Node.js (one-time, ~30 MB)...'
  $zip = Join-Path $Work 'node.zip'
  try { Invoke-WebRequest -UseBasicParsing "https://nodejs.org/dist/$NodeVersion/node-$NodeVersion-$NodePlat.zip" -OutFile $zip }
  catch { Die 'could not download Node.js (check your internet connection)' }
  Expand-Archive -Path $zip -DestinationPath $Work -Force
  Remove-Item $zip
}
$env:Path = "$NodeDir;" + $env:Path
$npm = Join-Path $NodeDir 'npm.cmd'
$npx = Join-Path $NodeDir 'npx.cmd'
Ok "Node.js ready ($(& (Join-Path $NodeDir 'node.exe') -v))."

# 2. Download the Worker source
Say 'Downloading the Cobblify stats backend...'
$srcZip = Join-Path $Work 'source.zip'
try { Invoke-WebRequest -UseBasicParsing $RepoZip -OutFile $srcZip } catch { Die 'could not download the mod source' }
$ext = Join-Path $Work 'src-extract'
$nmCache = Join-Path $Work 'nm-cache'
if (Test-Path $ext) {
  # Keep the previous run's npm packages so re-runs don't re-download them.
  $prevNm = Get-ChildItem -Path $ext -Recurse -Directory -Filter 'node_modules' |
            Where-Object { $_.FullName -match 'server\\stats-worker\\node_modules$' } | Select-Object -First 1
  if ($prevNm) {
    if (Test-Path $nmCache) { Remove-Item $nmCache -Recurse -Force }
    Move-Item $prevNm.FullName $nmCache
  }
  Remove-Item $ext -Recurse -Force
}
Expand-Archive -Path $srcZip -DestinationPath $ext -Force
Remove-Item $srcZip
$worker = Get-ChildItem -Path $ext -Recurse -Directory -Filter 'stats-worker' |
          Where-Object { $_.FullName -match 'server\\stats-worker$' } | Select-Object -First 1
if (-not $worker) { Die 'could not find the worker folder in the download' }
Set-Location $worker.FullName
if (Test-Path $nmCache) { Move-Item $nmCache (Join-Path $worker.FullName 'node_modules') }
Ok 'Source ready.'

# 3. Install dependencies
Say 'Installing (this can take a minute)...'
& $npm install --no-fund --no-audit
if ($LASTEXITCODE -ne 0) { Die 'npm install failed' }
Ok 'Installed.'

# 4. Log in to Cloudflare (opens a browser)
$who = Capture @('wrangler', 'whoami')
if ($who -match 'not authenticated|not logged|wrangler login') {
  Say 'A browser window will open - log in or sign up for Cloudflare, then click "Allow".'
  & $npx wrangler login
  if ($LASTEXITCODE -ne 0) { Die 'Cloudflare login was cancelled' }
}
Ok 'Logged in to Cloudflare.'

# 5. Create the provider-key store (KV namespace) and wire it into this copy of wrangler.toml
Say 'Setting up the provider-key store...'
$kvTitle = 'STATS_KV'
$kvOut = Capture @('wrangler', 'kv', 'namespace', 'create', 'STATS_KV')
$kvId = ([regex]::Match($kvOut, '[0-9a-f]{32}')).Value
if (-not $kvId) {
  # Already exists from an earlier run - look its id up instead.
  # Stdout only: stderr notices could contain a '[' and confuse the JSON trim below.
  $raw = CaptureStdout @('wrangler', 'kv', 'namespace', 'list')
  # Trim to the outermost [...] - wrangler prints banners/update notices around the JSON.
  $i = $raw.IndexOf('[')
  $j = $raw.LastIndexOf(']')
  if ($i -ge 0 -and $j -gt $i) {
    try {
      $ns = ($raw.Substring($i, $j - $i + 1) | ConvertFrom-Json) | Where-Object { $_.title -eq $kvTitle } | Select-Object -First 1
      if ($ns) { $kvId = $ns.id }
    } catch {}
  }
}
if (-not $kvId) { Write-Host $kvOut; Die 'could not create the provider-key store' }
Add-Content -Path 'wrangler.toml' -Value "`n[[kv_namespaces]]`nbinding = `"STATS_KV`"`nid = `"$kvId`""
Ok 'Provider-key store ready.'

# 6. Deploy - interactive on purpose: brand-new Cloudflare accounts are asked to register a
# free workers.dev name here, and that prompt only appears when the output is a real terminal.
Say 'Deploying your stats backend...'
Say '(If asked to register a workers.dev subdomain, type anything - e.g. your Minecraft name.)'
& $npx wrangler deploy
if ($LASTEXITCODE -ne 0) { Die 'deploy failed' }

# The URL was printed to the terminal above where we cannot read it, so re-deploy captured -
# the subdomain now exists, so this second pass never prompts.
Say 'Reading your backend address...'
$deploy = Capture @('wrangler', 'deploy')
if ($LASTEXITCODE -ne 0) { Write-Host $deploy; Die 'deploy failed' }
$m = [regex]::Match($deploy, 'https://[a-zA-Z0-9._-]+\.workers\.dev')
if (-not $m.Success) { Write-Host $deploy; Die 'deployed, but could not read the URL from the output above' }
$url = $m.Value
Ok "Backend live at $url"

# 7. Lock the backend with a private token so only this user's mod can use it
Say 'Locking your backend with a private token...'
$bytes = New-Object byte[] 16
[Security.Cryptography.RandomNumberGenerator]::Create().GetBytes($bytes)
$token = -join ($bytes | ForEach-Object { $_.ToString('x2') })
$oldEap = $ErrorActionPreference
$ErrorActionPreference = 'Continue'
$secretOut = ($token | & $npx wrangler secret put STATS_TOKEN 2>&1 | Out-String)
$ErrorActionPreference = $oldEap
if ($LASTEXITCODE -ne 0) { Write-Host $secretOut; Die 'could not set the backend token' }
Ok 'Backend locked.'

$cmdUrl = "/cobblify statsurl $url"
$cmdToken = "/cobblify statstoken $token"
try { Set-Clipboard -Value $cmdUrl } catch {}
# Also save the commands to a file so closing this window loses nothing.
$cmdFile = Join-Path ([Environment]::GetFolderPath('Desktop')) 'cobblify-commands.txt'
try { "$cmdUrl`r`n$cmdToken`r`n" | Set-Content -Path $cmdFile } catch { $cmdFile = $null }

# Done
Write-Host ''
Write-Host '======================================================' -ForegroundColor Green
Write-Host '  DONE!  Your stats backend is live.'                   -ForegroundColor Green
Write-Host '======================================================' -ForegroundColor Green
Write-Host ''
Write-Host 'In Minecraft, paste BOTH commands into chat (the first is on your clipboard):'
Write-Host ''
Write-Host "   $cmdUrl" -ForegroundColor Yellow
Write-Host "   $cmdToken" -ForegroundColor Yellow
Write-Host ''
Write-Host 'Then turn on Hypixel Stats in the mod settings (press Right Shift).'
Write-Host ''
Write-Host 'Optional - community cheater tags from Urchin: get a free API key from the'
Write-Host 'Urchin Discord bot (/grant), then run in chat:  /cobblify urchinkey <your key>'
Write-Host ''
if ($cmdFile) { Write-Host "Both commands are also saved to:  $cmdFile" }
Write-Host ''
Read-Host 'Press Enter to close this window'
