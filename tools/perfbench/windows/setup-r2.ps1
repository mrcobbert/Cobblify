$ErrorActionPreference = "Stop"
Set-Location C:\Users\agent\perfbench
tar -xf stage2.tar
if ($LASTEXITCODE -ne 0) { throw "tar failed" }
if (Test-Path inst\forgeof) { Remove-Item -Recurse -Force inst\forgeof }
Copy-Item -Recurse inst\forge inst\forgeof
Copy-Item optionsof-tuned.txt inst\forgeof\optionsof.txt
foreach ($i in "forge", "forgeof") { Copy-Item inst\one\options.txt "inst\$i\options.txt" -Force }
foreach ($d in Get-ChildItem inst -Directory) {
  $o = Join-Path $d.FullName "options.txt"
  $lines = Get-Content $o | ForEach-Object { if ($_ -like "maxFps:*") { "maxFps:1500" } else { $_ } }
  Set-Content -Path $o -Value $lines -Encoding ASCII
  $d.Name + " " + ((Get-Content $o | Select-String "maxFps|entityShadows|renderDistance|enableVsync") -join " ")
}
Remove-Item stage2.tar
"setup ok"
