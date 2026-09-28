<script setup>
import { Sun, Moon, Monitor, Check, Smartphone, Share, SquarePlus, CheckCircle2 } from 'lucide-vue-next'
import { themeMode, setThemeMode, accent, setAccent, resolvedTheme } from '../../stores/ui'
import { ACCENTS } from '../../lib/constants'
import { pwa, promptInstall } from '../../lib/pwa'
import { toast } from '../../stores/ui'
import Btn from '../../components/Btn.vue'
import Callout from '../../components/Callout.vue'

async function install() {
  const r = await promptInstall()
  if (r === 'accepted') toast('Đã cài. Mở “FB Ads” từ màn hình chính')
  else if (r === null) toast('Trình duyệt chưa cho cài lúc này, hãy tải lại trang rồi thử lại', 'error')
}

const modes = [
  { v: 'light', label: 'Sáng', icon: Sun },
  { v: 'dark', label: 'Tối', icon: Moon },
  { v: 'system', label: 'Theo hệ thống', icon: Monitor },
]
</script>

<template>
  <section class="card pad">
    <h3>Giao diện</h3>
    <p class="muted sub">Chọn chế độ sáng/tối và màu nhấn. Cài đặt này chỉ áp dụng trên trình duyệt này.</p>

    <div class="themes">
      <button v-for="m in modes" :key="m.v" class="th" :class="{ on: themeMode === m.v }" @click="setThemeMode(m.v)">
        <div class="pv" :class="m.v === 'system' ? 'split' : m.v">
          <div class="side" /><div class="mainp"><i /><i class="s" /><i class="a" /></div>
        </div>
        <span class="lb"><component :is="m.icon" :size="16" />{{ m.label }}<Check v-if="themeMode === m.v" :size="15" class="ck" /></span>
      </button>
    </div>

    <h4>Màu nhấn</h4>
    <div class="swatches">
      <button v-for="(a, k) in ACCENTS" :key="k" class="sw" :class="{ on: accent === k }" :title="a.label" :aria-label="a.label" @click="setAccent(k)"
        :style="{ background: `linear-gradient(135deg, ${a[resolvedTheme][0]}, ${a[resolvedTheme][1]})` }"><Check v-if="accent === k" :size="18" /></button>
    </div>

    <h4>Cài lên điện thoại</h4>
    <div class="app">
      <img src="/icons/icon-192.png" alt="" width="52" height="52" />
      <div class="ab">
        <p v-if="pwa.installed" class="st"><CheckCircle2 :size="16" /> Đang chạy như app</p>
        <p v-else class="muted">Thêm tool vào màn hình chính để mở nhanh như một ứng dụng, toàn màn hình, không có thanh địa chỉ. Số liệu vẫn luôn là mới nhất.</p>
        <template v-if="!pwa.installed">
          <Callout v-if="!pwa.secure" tone="info">Chỉ cài được khi mở tool qua <b>https</b> (vd bản trên Render) hoặc <code>localhost</code>. Mở bằng địa chỉ IP trong mạng nhà thì chỉ thêm được lối tắt.</Callout>
          <Btn v-else-if="pwa.canPrompt" variant="primary" :icon="Smartphone" :action="install">Cài app</Btn>
          <ol v-else-if="pwa.ios" class="ios">
            <li>Trong Safari, bấm nút <b>Chia sẻ</b> <Share :size="15" /> ở thanh dưới.</li>
            <li>Chọn <b>Thêm vào MH chính</b> <SquarePlus :size="15" /> rồi bấm <b>Thêm</b>.</li>
          </ol>
          <p v-else class="faint">Mở trang này bằng <b>Chrome</b> trên Android (hoặc Chrome/Edge trên máy tính) để thấy nút Cài app. Trên iPhone, dùng Safari. Nếu đã cài rồi, mở từ màn hình chính.</p>
        </template>
      </div>
    </div>
  </section>
</template>

<style scoped>
h3 { font-size: 18px; letter-spacing: -.02em; } .sub { margin: 4px 0 20px; font-size: 14.5px; }
h4 { font-size: 15px; margin: 26px 0 12px; }
.themes { display: grid; grid-template-columns: repeat(3, 1fr); gap: 14px; }
.th { border: 1.5px solid var(--border); background: var(--surface); border-radius: var(--r-lg); padding: 10px; text-align: left; transition: .2s var(--ease); }
.th:hover { border-color: var(--border-strong); transform: translateY(-2px); }
.th.on { border-color: var(--accent); box-shadow: 0 0 0 4px var(--accent-soft); }
.pv { height: 92px; border-radius: 12px; display: flex; overflow: hidden; border: 1px solid var(--border); }
.pv.light { --p-bg: #f4f5fa; --p-sf: #fff; --p-ln: #dfe2ee; }
.pv.dark { --p-bg: #090a10; --p-sf: #181b27; --p-ln: #2a2f45; }
.pv.split { background: linear-gradient(115deg, #f4f5fa 50%, #090a10 50%); --p-sf: transparent; --p-ln: #8a90a8; }
.pv .side { width: 26%; background: var(--p-sf); border-right: 1px solid var(--p-ln); }
.pv .mainp { flex: 1; background: var(--p-bg); padding: 10px; display: grid; gap: 6px; align-content: start; }
.pv.split .mainp { background: transparent; }
.pv i { display: block; height: 10px; border-radius: 4px; background: var(--p-sf); border: 1px solid var(--p-ln); }
.pv i.s { width: 60%; } .pv i.a { width: 34%; background: var(--accent-grad); border: 0; }
.lb { display: flex; align-items: center; gap: 8px; padding: 12px 4px 2px; font-weight: 600; font-size: 14.5px; }
.ck { margin-left: auto; color: var(--accent); }
.swatches { display: flex; gap: 12px; flex-wrap: wrap; }
.sw { width: 44px; height: 44px; border-radius: 50%; border: 0; color: #fff; display: grid; place-items: center; box-shadow: 0 6px 16px -6px rgba(0, 0, 0, .4); transition: transform .25s cubic-bezier(.34, 1.56, .64, 1); }
.sw:hover { transform: scale(1.12); } .sw.on { outline: 3px solid var(--surface); box-shadow: 0 0 0 5px var(--accent-ring); }
.app { display: flex; gap: 16px; align-items: flex-start; }
.app img { border-radius: 14px; flex: none; }
.ab { display: grid; gap: 10px; justify-items: start; min-width: 0; }
.ab p { margin: 0; font-size: 14.5px; }
.st { display: inline-flex; align-items: center; gap: 6px; font-weight: 600; color: var(--success); }
.ios { margin: 0; padding-left: 20px; display: grid; gap: 6px; font-size: 14.5px; }
.ios svg { vertical-align: -3px; }
@media (max-width: 700px) { .themes { grid-template-columns: 1fr; } }
</style>
