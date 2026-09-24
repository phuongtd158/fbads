# Đợi ngrok khởi động rồi in địa chỉ https công khai (lấy từ giao diện nội bộ của ngrok ở cổng 4040).
# Thoát mã 1 nếu sau $Seconds giây vẫn chưa có.
param([int]$Seconds = 30)
for ($i = 0; $i -lt $Seconds; $i++) {
  try {
    $t = (Invoke-RestMethod -Uri 'http://127.0.0.1:4040/api/tunnels' -TimeoutSec 2).tunnels |
      Where-Object { $_.public_url -like 'https://*' } | Select-Object -First 1
    if ($t) { Write-Output $t.public_url; exit 0 }
  } catch { }
  Start-Sleep -Seconds 1
}
exit 1
