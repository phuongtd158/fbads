const store = require('./store');

async function telegram(text) {
  const { telegramToken, telegramChatId } = store.get().settings;
  if (!telegramToken || !telegramChatId) return false;
  try {
    const r = await fetch(`https://api.telegram.org/bot${telegramToken}/sendMessage`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ chat_id: telegramChatId, text, parse_mode: 'HTML' }),
    });
    return r.ok;
  } catch (e) {
    console.error('Telegram lỗi:', e.message);
    return false;
  }
}

module.exports = { telegram };
