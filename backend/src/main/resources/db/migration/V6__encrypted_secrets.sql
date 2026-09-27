-- Token Facebook, App Secret, token Telegram được mã hoá trước khi ghi (common/SecretConverter, khoá SECRET_KEY):
-- bản mã hoá dài hơn bản gốc nên nới rộng cột.
ALTER TABLE app_settings MODIFY fb_app_secret VARCHAR(300) NOT NULL DEFAULT '';
ALTER TABLE app_settings MODIFY telegram_token VARCHAR(500) NOT NULL DEFAULT '';
