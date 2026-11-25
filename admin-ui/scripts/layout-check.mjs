// 聊天页布局回归探针（需服务端已运行 + 已 deploy 当前构建）：
//   node scripts/layout-check.mjs [baseUrl]
// 校验 app 式定高布局没退化：composer 钉在视口底、消息区内部滚动、默认自动选中首个会话。
// 任一项不通过 → exit 1。依赖 devDependency puppeteer-core + 本机 Chrome。
import puppeteer from 'puppeteer-core'

const BASE = process.argv[2] || 'http://127.0.0.1:8080'
const TOKEN = process.env.WXUW_ADMIN_UI_TOKEN || 'wxbot-admin-2026'
const CHROME = process.env.CHROME_PATH || 'C:\\Program Files\\Google\\Chrome\\Application\\chrome.exe'

const browser = await puppeteer.launch({ executablePath: CHROME, headless: 'new', args: ['--no-sandbox'] })
const fails = []
const check = (ok, msg) => { console.log(`${ok ? 'PASS' : 'FAIL'}  ${msg}`); if (!ok) fails.push(msg) }

async function open(query) {
  const page = await browser.newPage()
  await page.setViewport({ width: 1680, height: 1050 })
  // 页面有周期轮询，networkidle 永不触发 → 等 DOM + 会话行渲染
  await page.goto(`${BASE}/admin/chats?token=${TOKEN}${query}`, { waitUntil: 'domcontentloaded', timeout: 30000 })
  await page.waitForSelector('.chat-composer', { timeout: 15000 }).catch(() => {})
  await new Promise((r) => setTimeout(r, 2500))
  return page
}

const measure = (page) => page.evaluate(() => {
  const box = (sel) => {
    const el = document.querySelector(sel)
    if (!el) return null
    const r = el.getBoundingClientRect()
    return { top: Math.round(r.top), bottom: Math.round(r.bottom), h: Math.round(r.height) }
  }
  const wrap = document.querySelector('.chat-wrap')
  return {
    vpH: innerHeight,
    docScrollH: document.scrollingElement.scrollHeight,
    composer: box('.chat-composer'),
    wrap: box('.chat-wrap'),
    wrapScroll: wrap ? { st: wrap.scrollTop, sh: wrap.scrollHeight, ch: wrap.clientHeight } : null,
    activeRow: document.querySelector('.sess-item.active .sess-name')?.textContent || null,
    chatTitle: document.querySelector('.wx-chat-title')?.textContent || null,
    bubbles: document.querySelectorAll('.chat-row').length,
  }
})

// 1) 默认进入（无深链）：应自动选中首个会话
const p1 = await open('')
const m1 = await measure(p1)
check(m1.activeRow !== null, `默认进入自动选中首个会话（${m1.activeRow ?? '未选中'}）`)
check(m1.activeRow !== null && m1.chatTitle === m1.activeRow, `聊天头部与选中会话一致（${m1.chatTitle}）`)
check(m1.bubbles > 0, `消息流已加载（${m1.bubbles} 条）`)

// 2) 布局（两页共用同一断言）
for (const [name, m] of [['默认', m1]]) {
  check(m.docScrollH <= m.vpH, `${name}: 文档不整页滚动（scrollH ${m.docScrollH} / 视口 ${m.vpH}）`)
  check(!!m.composer && m.composer.bottom <= m.vpH && m.composer.h > 0,
    `${name}: composer 在视口内（${m.composer ? `${m.composer.top}→${m.composer.bottom}` : '缺失'}）`)
  check(!!m.wrap && !!m.composer && m.wrap.bottom <= m.composer.top + 1,
    `${name}: 消息区在 composer 之上（wrap 底 ${m.wrap?.bottom} / composer 顶 ${m.composer?.top}）`)
}
await p1.close()
await browser.close()

console.log(fails.length ? `\n${fails.length} 项不通过` : '\n布局回归全部通过')
process.exit(fails.length ? 1 : 0)
