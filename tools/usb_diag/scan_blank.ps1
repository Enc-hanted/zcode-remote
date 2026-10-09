# scan_blank.ps1 —— 物理屏截图底部空白带量化（usb_diag scan/blank 调用）
# 用法：powershell -File scan_blank.ps1 <img.png>
# 原理：取 x∈[30%,42%] 竖条（避开左下角悬浮图标与居中的"滚动到底部"圆钮），
# 逐行与背景亮度（竖条中位数）比对判"内容行"，找下半屏最长连续空白段。
$path = $env:USB_DIAG_IMG
Add-Type -AssemblyName System.Drawing
$bmp = [System.Drawing.Bitmap]::FromFile($path)
$W = $bmp.Width; $H = $bmp.Height
Write-Output ("img {0}: {1}x{2}" -f $path, $W, $H)
$x0 = [int]($W*0.30); $x1 = [int]($W*0.42)
$lums = @()
for ($y = [int]($H*0.55); $y -lt [int]($H*0.95); $y += 3) {
  for ($x = $x0; $x -lt $x1; $x += 4) {
    $c = $bmp.GetPixel($x, $y)
    $lums += [double]($c.R*0.3 + $c.G*0.59 + $c.B*0.11)
  }
}
$sorted = $lums | Sort-Object
$bg = $sorted[[int]($sorted.Count/2)]
$isContent = New-Object 'bool[]' $H
for ($y = 0; $y -lt $H; $y++) {
  $hit = 0; $n = 0
  for ($x = $x0; $x -lt $x1; $x += 3) {
    $c = $bmp.GetPixel($x, $y)
    $l = [double]($c.R*0.3 + $c.G*0.59 + $c.B*0.11)
    $n++
    if ([Math]::Abs($l - $bg) -gt 28) { $hit++ }
  }
  if ($n -gt 0 -and ($hit / $n) -gt 0.06) { $isContent[$y] = $true }
}
$bestStart=-1; $bestLen=0; $curStart=-1; $curLen=0
for ($y = [int]($H*0.45); $y -lt $H; $y++) {
  if (-not $isContent[$y]) { if ($curStart -lt 0) { $curStart=$y }; $curLen++ }
  else {
    if ($curLen -gt $bestLen) { $bestLen=$curLen; $bestStart=$curStart }
    $curStart=-1; $curLen=0
  }
}
if ($curLen -gt $bestLen) { $bestLen=$curLen; $bestStart=$curStart }
Write-Output ("bgLum={0:N0}  底部最大空白段: y {1}-{2} ({3:N1}-{4:N1}% H), 高 {5}px = {6:N1}% 屏高" -f $bg, $bestStart, ($bestStart+$bestLen-1), ($bestStart*100.0/$H), (($bestStart+$bestLen)*100.0/$H), $bestLen, ($bestLen*100.0/$H))
$bmp.Dispose()
