# Hỏi mật khẩu đăng nhập cho tool (không hiện ký tự khi gõ) và in mật khẩu ra stdout.
# Lời nhắc ghi ra stderr để không lẫn vào kết quả mà start-ngrok.bat đọc.
$min = 8
while ($true) {
  [Console]::Error.Write("Nhap mat khau dang nhap cho tool (it nhat $min ky tu, khong chua dau nhay kep): ")
  $sb = New-Object System.Text.StringBuilder
  while ($true) {
    $k = [Console]::ReadKey($true)
    if ($k.Key -eq 'Enter') { break }
    if ($k.Key -eq 'Backspace') { if ($sb.Length -gt 0) { [void]$sb.Remove($sb.Length - 1, 1) }; continue }
    if ($k.KeyChar -ne [char]0) { [void]$sb.Append($k.KeyChar) }
  }
  [Console]::Error.WriteLine()
  $p = $sb.ToString()
  if ($p.Length -lt $min) { [Console]::Error.WriteLine("Mat khau qua ngan, nhap lai."); continue }
  if ($p.Contains('"') -or $p.StartsWith(';')) { [Console]::Error.WriteLine("Mat khau khong duoc chua dau nhay kep hoac bat dau bang dau cham phay, nhap lai."); continue }
  break
}
Write-Output $p
