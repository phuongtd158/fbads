@echo off
chcp 65001 >nul
setlocal
cd /d "%~dp0"
title FB Ads Auto - chia sẻ qua ngrok
if not defined PORT set "PORT=3000"

echo.
echo   FB Ads Auto - chia sẻ qua ngrok
echo   -------------------------------

where node >nul 2>nul
if errorlevel 1 ( set "MSG=Chưa cài Node.js. Tải tại https://nodejs.org rồi chạy lại." & goto :die )
where curl >nul 2>nul
if errorlevel 1 ( set "MSG=Không tìm thấy curl (có sẵn từ Windows 10). Hãy cập nhật Windows." & goto :die )
where ngrok >nul 2>nul
if errorlevel 1 ( set "MSG=Chưa cài ngrok. Chạy: winget install ngrok.ngrok   rồi chạy: ngrok config add-authtoken TOKEN_CUA_BAN" & goto :die )
if not exist "public\index.html" ( set "MSG=Chưa có bản build giao diện (thư mục public)." & goto :die )

rem --- Tên miền tĩnh (tuỳ chọn): đặt trong biến NGROK_DOMAIN hoặc file ngrok-domain.txt (1 dòng)
if not defined NGROK_DOMAIN if exist "ngrok-domain.txt" (
  for /f "usebackq delims=" %%D in ("ngrok-domain.txt") do if not defined NGROK_DOMAIN set "NGROK_DOMAIN=%%D"
)
if defined NGROK_DOMAIN set "NGROK_DOMAIN=%NGROK_DOMAIN:https://=%"
if defined NGROK_DOMAIN set "NGROK_DOMAIN=%NGROK_DOMAIN:/=%"

rem --- Tool đã chạy sẵn ở cổng này chưa? Nếu có thì bắt buộc phải có mật khẩu, nếu không sẽ lộ ra internet
set "STARTED="
curl -s -o nul http://127.0.0.1:%PORT%/api/auth 2>nul
if errorlevel 1 goto :startserver

curl -s http://127.0.0.1:%PORT%/api/auth 2>nul | findstr /r "required.:true" >nul
if errorlevel 1 (
  set "MSG=Đang có một bản tool chạy ở cổng %PORT% mà KHÔNG có mật khẩu. Hãy đóng nó (cửa sổ start.bat) rồi chạy lại file này để không lộ ra internet."
  goto :die
)
echo   Tool đang chạy sẵn ở cổng %PORT% và đã có mật khẩu: dùng luôn.
goto :tunnel

:startserver
rem --- Chưa chạy: hỏi mật khẩu (nếu chưa đặt sẵn trong biến môi trường APP_PASSWORD) rồi khởi động tool
if not defined APP_PASSWORD (
  for /f "usebackq delims=" %%P in (`powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0scripts\read-password.ps1"`) do set "APP_PASSWORD=%%P"
)
if not defined APP_PASSWORD ( set "MSG=Chưa có mật khẩu nên không thể mở ra internet." & goto :die )
set "TRUST_PROXY=1"
echo   Đang khởi động tool...
start "FB Ads Auto - server" /min cmd /c "node server.js"
set "STARTED=1"
call :wait_server
if errorlevel 1 (
  set "MSG=Tool không khởi động được. Mở cửa sổ 'FB Ads Auto - server' trên thanh tác vụ để xem lỗi."
  goto :die
)

:tunnel
rem --- Mở đường hầm ngrok
set "NGROK_ARGS=http %PORT%"
if defined NGROK_DOMAIN set "NGROK_ARGS=http --url=%NGROK_DOMAIN% %PORT%"
echo   Đang mở đường hầm ngrok...
start "FB Ads Auto - ngrok" /min cmd /k ngrok %NGROK_ARGS%

set "PUBLIC_URL="
for /f "usebackq delims=" %%U in (`powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0scripts\ngrok-url.ps1"`) do set "PUBLIC_URL=%%U"
if not defined PUBLIC_URL (
  set "MSG=Ngrok chưa cho địa chỉ công khai. Mở cửa sổ 'FB Ads Auto - ngrok' trên thanh tác vụ để xem lỗi (thường do chưa chạy: ngrok config add-authtoken TOKEN_CUA_BAN)."
  goto :die
)

echo %PUBLIC_URL%| clip
echo.
echo   ============================================================
echo    Địa chỉ công khai (đã sao chép vào clipboard):
echo.
echo      %PUBLIC_URL%
echo.
echo    Gửi địa chỉ này KÈM MẬT KHẨU cho người cần dùng.
echo    Giữ cửa sổ này mở. Máy tắt hoặc đóng cửa sổ thì hết truy cập.
echo   ============================================================
echo.
echo   Lưu ý: ngrok đi qua máy chủ của ngrok. Nên nhập token Facebook khi
echo   truy cập trực tiếp http://localhost:%PORT% trên máy này, không qua link.
echo.
echo   Nhấn phím bất kỳ để DỪNG tool và ngrok...
pause >nul

taskkill /fi "WINDOWTITLE eq FB Ads Auto - ngrok*" /t /f >nul 2>nul
if defined STARTED taskkill /fi "WINDOWTITLE eq FB Ads Auto - server*" /t /f >nul 2>nul
echo   Đã dừng.
endlocal
exit /b 0

:die
echo.
echo   [LỖI] %MSG%
echo.
taskkill /fi "WINDOWTITLE eq FB Ads Auto - ngrok*" /t /f >nul 2>nul
if defined STARTED taskkill /fi "WINDOWTITLE eq FB Ads Auto - server*" /t /f >nul 2>nul
pause
endlocal
exit /b 1

rem --- Hàm con: đợi tool trả lời (tối đa ~20 giây). errorlevel 0 = sẵn sàng, 1 = quá hạn
:wait_server
set /a TRIES=0
:wait_loop
curl -s -o nul http://127.0.0.1:%PORT%/api/auth 2>nul
if not errorlevel 1 exit /b 0
set /a TRIES+=1
if %TRIES% GEQ 20 exit /b 1
timeout /t 1 /nobreak >nul
goto :wait_loop
