// سيرفر «نمط الشبكة»: إعدادات بعيدة، إجبار تحديث، إعلانات، إحصائيات، أعطال، تقارير توافق، نسخ احتياطي.
const REPO = 'makanona-team/network-mode';
const STABLE_APK = `https://github.com/${REPO}/releases/latest/download/network-mode.apk`;
const SW_DEFAULT = {
  shizuku_apply: true, boot_reapply: true, call_switch: true,
  drop_alert: true, lte_timer: true, telemetry: true,
};
const SW_LABELS = {
  shizuku_apply: 'التطبيق المباشر عبر Shizuku',
  boot_reapply: 'إعادة التطبيق بعد التشغيل',
  call_switch: 'التحويل أثناء المكالمة',
  drop_alert: 'تنبيه الانقطاع',
  lte_timer: 'مؤقت LTE فقط',
  telemetry: 'إرسال الإحصائيات',
};

const json = (o, s = 200) => new Response(JSON.stringify(o), {
  status: s, headers: { 'content-type': 'application/json; charset=utf-8', 'access-control-allow-origin': '*' },
});

async function cfg(env) {
  const c = (await env.KV.get('config', 'json')) || {};
  return { minVersion: c.minVersion || 0, switches: { ...SW_DEFAULT, ...(c.switches || {}) } };
}

async function latest(env, force = false) {
  // الإصدار الذي يبلّغ عنه خط البناء مباشرة (المصدر الأساسي)
  const rel = await env.KV.get('release', 'json');
  if (rel && rel.code) return { url: STABLE_APK, releases: rel.releases || 1, ...rel };
  if (!force) {
    const c = await env.KV.get('latest', 'json');
    if (c) return c;
  }
  let out = { code: 0, name: '', notes: '', url: STABLE_APK, releases: 0 };
  try {
    const r = await fetch(`https://api.github.com/repos/${REPO}/releases?per_page=30`, {
      headers: { 'user-agent': 'network-mode-worker', accept: 'application/vnd.github+json' },
    });
    if (r.ok) {
      const list = (await r.json()).filter((x) => !x.draft && !x.prerelease);
      out.releases = list.length;
      const top = list[0];
      if (top) {
        const m = (top.body || '').match(/versionCode:\s*(\d+)/);
        out.code = m ? +m[1] : 0;
        out.name = (top.tag_name || '').replace(/^v/, '');
        out.notes = (top.body || '').replace(/versionCode:\s*\d+/, '').trim();
      }
    }
  } catch (e) {}
  await env.KV.put('latest', JSON.stringify(out), { expirationTtl: 600 });
  return out;
}

async function anns(env) { return (await env.KV.get('ann', 'json')) || []; }

async function body(req) { try { return await req.json(); } catch (e) { return {}; } }

const clip = (s, n) => String(s || '').slice(0, n);

export default {
  async fetch(req, env) {
    const url = new URL(req.url);
    const p = url.pathname.replace(/\/+$/, '') || '/';
    const m = req.method;

    // ===== للتطبيق =====
    if (p === '/config' && m === 'GET') {
      const [c, l, a] = await Promise.all([cfg(env), latest(env), anns(env)]);
      return json({
        minVersion: c.minVersion, switches: c.switches,
        latestVersion: l.code, latestName: l.name, latestUrl: l.url, whatsNew: l.notes,
        announcements: a,
      });
    }
    if (p === '/ping' && m === 'POST') {
      const b = await body(req);
      if (!b.id) return json({ ok: false }, 400);
      const meta = {
        vc: b.vc | 0, vn: clip(b.vn, 20), model: clip(b.model, 60), sdk: b.sdk | 0,
        android: clip(b.android, 10), method: clip(b.method, 10), sims: b.sims | 0, ts: Date.now(),
      };
      await env.KV.put('dev:' + clip(b.id, 40), '1', { metadata: meta, expirationTtl: 90 * 86400 });
      return json({ ok: true });
    }
    if (p === '/crash' && m === 'POST') {
      const b = await body(req);
      const k = 'crash:' + Date.now() + ':' + Math.random().toString(36).slice(2, 6);
      await env.KV.put(k, JSON.stringify(b).slice(0, 20000), {
        metadata: { vc: b.vc | 0, model: clip(b.model, 60), head: clip((b.trace || '').split('\n').slice(0, 3).join(' | '), 300), ts: Date.now() },
        expirationTtl: 60 * 86400,
      });
      return json({ ok: true });
    }
    if (p === '/compat' && m === 'POST') {
      const b = await body(req);
      const k = 'compat:' + clip(b.model, 60) + ':' + clip(b.id, 40);
      const rep = String(b.report || '');
      const ok = /طريقة التطبيق: Shizuku|طريقة التطبيق: Root/.test(rep) && /\[OK\] قراءة النمط/.test(rep);
      await env.KV.put(k, JSON.stringify(b).slice(0, 25000), {
        metadata: { vc: b.vc | 0, model: clip(b.model, 60), android: clip(b.android, 10), method: clip(b.method, 10), ok, ts: Date.now() },
        expirationTtl: 180 * 86400,
      });
      return json({ ok: true });
    }
    if (p === '/backup') {
      if (m === 'POST') {
        const b = await body(req);
        if (!b.id) return json({ ok: false }, 400);
        await env.KV.put('bk:' + clip(b.id, 40), JSON.stringify({ prefs: b.prefs || {}, ts: Date.now() }));
        return json({ ok: true });
      }
      const id = url.searchParams.get('id');
      const v = id && (await env.KV.get('bk:' + clip(id, 40)));
      return v ? new Response(v, { headers: { 'content-type': 'application/json' } }) : json({ ok: false }, 404);
    }

    // ===== لوحة الأدمن =====
    if (p === '/admin' || p === '/') {
      return new Response(ADMIN_HTML, { headers: { 'content-type': 'text/html; charset=utf-8' } });
    }
    if (p.startsWith('/admin/api/')) {
      if (!env.ADMIN_KEY || req.headers.get('x-key') !== env.ADMIN_KEY) return json({ error: 'مفتاح خاطئ' }, 401);
      const a = p.slice('/admin/api/'.length);

      if (a === 'state' && m === 'GET') {
        const [c, l, an] = await Promise.all([cfg(env), latest(env, true), anns(env)]);
        const list = async (prefix) => {
          let all = [], cursor;
          do {
            const r = await env.KV.list({ prefix, cursor, limit: 1000 });
            all = all.concat(r.keys.map((k) => ({ key: k.name, ...(k.metadata || {}) })));
            cursor = r.list_complete ? null : r.cursor;
          } while (cursor && all.length < 5000);
          return all;
        };
        const [devices, crashes, compat] = await Promise.all([list('dev:'), list('crash:'), list('compat:')]);
        return json({ config: c, latest: l, announcements: an, devices, crashes, compat, labels: SW_LABELS });
      }
      if (a === 'release' && m === 'POST') {
        const b = await body(req);
        const prev = (await env.KV.get('release', 'json')) || {};
        const r = {
          code: b.code | 0, name: clip(b.name, 20), notes: clip(b.notes, 3000), url: STABLE_APK,
          releases: (prev.releases || 0) + (prev.code === (b.code | 0) ? 0 : 1), ts: Date.now(),
        };
        if (!r.code) return json({ error: 'code مطلوب' }, 400);
        await env.KV.put('release', JSON.stringify(r));
        return json({ ok: true, release: r });
      }
      if (a === 'config' && m === 'POST') {
        const b = await body(req);
        const l = await latest(env, true);
        const minV = b.minVersion | 0;
        if (minV > l.code) {
          return json({ error: `لا يمكن أن يكون الحد الأدنى (${minV}) أعلى من آخر إصدار منشور (${l.code}).` }, 400);
        }
        const sw = {};
        for (const k of Object.keys(SW_DEFAULT)) sw[k] = b.switches && k in b.switches ? !!b.switches[k] : true;
        await env.KV.put('config', JSON.stringify({ minVersion: minV, switches: sw }));
        return json({ ok: true });
      }
      if (a === 'ann' && m === 'POST') {
        const b = await body(req);
        if (!b.title) return json({ error: 'العنوان مطلوب' }, 400);
        const list = await anns(env);
        list.unshift({ id: Date.now().toString(36), title: clip(b.title, 120), body: clip(b.body, 2000), url: clip(b.url, 300), ts: Date.now() });
        await env.KV.put('ann', JSON.stringify(list.slice(0, 50)));
        return json({ ok: true });
      }
      if (a === 'ann' && m === 'DELETE') {
        const id = url.searchParams.get('id');
        await env.KV.put('ann', JSON.stringify((await anns(env)).filter((x) => x.id !== id)));
        return json({ ok: true });
      }
      if (a === 'item' && m === 'GET') {
        const k = url.searchParams.get('key') || '';
        if (!/^(crash|compat):/.test(k)) return json({ error: 'bad key' }, 400);
        return new Response((await env.KV.get(k)) || '{}', { headers: { 'content-type': 'application/json' } });
      }
      if (a === 'item' && m === 'DELETE') {
        const k = url.searchParams.get('key') || '';
        if (/^(crash|compat):/.test(k)) await env.KV.delete(k);
        return json({ ok: true });
      }
      return json({ error: 'not found' }, 404);
    }
    return json({ error: 'not found' }, 404);
  },
};

const ADMIN_HTML = `<!doctype html><html lang="ar" dir="rtl"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1"><title>لوحة نمط الشبكة</title>
<style>
:root{--bg:#f4f6f5;--card:#fff;--t:#17201d;--s:#5f6b67;--a:#0e6e5c;--w:#c0392b;--l:#e3e8e6}
@media(prefers-color-scheme:dark){:root{--bg:#121614;--card:#1d2321;--t:#e8eeec;--s:#9ba8a4;--a:#3fc1a5;--w:#ff8a7a;--l:#2b3330}}
*{box-sizing:border-box}body{margin:0;font:15px system-ui,sans-serif;background:var(--bg);color:var(--t)}
main{max-width:760px;margin:auto;padding:14px}h1{font-size:20px}h2{font-size:16px;margin:0 0 10px}
.c{background:var(--card);border-radius:14px;padding:14px;margin-bottom:12px;border:1px solid var(--l)}
input,textarea{width:100%;padding:10px;border-radius:10px;border:1px solid var(--l);background:var(--bg);color:var(--t);font:inherit;margin:4px 0}
button{background:var(--a);color:#fff;border:0;border-radius:10px;padding:10px 14px;font:inherit;font-weight:600;margin:4px 0}
button.g{background:transparent;color:var(--a);border:1px solid var(--a)}button.r{background:var(--w)}
.row{display:flex;justify-content:space-between;align-items:center;gap:8px;padding:6px 0;border-bottom:1px solid var(--l)}
.row:last-child{border:0}.s{color:var(--s);font-size:13px}.ok{color:var(--a)}.bad{color:var(--w)}
pre{white-space:pre-wrap;word-break:break-word;font-size:12px;background:var(--bg);padding:8px;border-radius:8px;direction:ltr;text-align:left}
.bar{height:8px;background:var(--a);border-radius:4px}
</style></head><body><main>
<h1>📶 لوحة «نمط الشبكة»</h1>
<div class="c" id="login"><h2>الدخول</h2><input id="key" type="password" placeholder="مفتاح الأدمن (APP_SECRET)">
<button onclick="saveKey()">دخول</button></div>
<div id="app" hidden>
<div class="c"><h2>✅ قائمة ما قبل الإصدار</h2><div id="check"></div></div>
<div class="c"><h2>📦 الإصدارات</h2><div id="rel"></div></div>
<div class="c"><h2>⚙ التحكم عن بُعد</h2>
<label class="s">الحد الأدنى للإصدار (versionCode) — أي نسخة أقدم تُجبر على التحديث</label>
<input id="minv" type="number" min="0"><div id="sw"></div><button onclick="saveCfg()">حفظ</button></div>
<div class="c"><h2>🔔 الإعلانات</h2><input id="at" placeholder="العنوان"><textarea id="ab" rows="3" placeholder="النص"></textarea>
<input id="au" placeholder="رابط (اختياري)" dir="ltr"><button onclick="addAnn()">نشر الإعلان</button><div id="anns"></div></div>
<div class="c"><h2>📱 الأجهزة (<span id="dc"></span>)</h2><div id="vers"></div><div id="devs"></div></div>
<div class="c"><h2>🧪 تقارير التوافق</h2><div id="compat"></div></div>
<div class="c"><h2>💥 الأعطال</h2><div id="crashes"></div></div>
<button class="g" onclick="load()">تحديث</button> <button class="g" onclick="logout()">خروج</button>
</div></main>
<script>
let K=localStorage.getItem('nmk')||'',S=null;
const $=id=>document.getElementById(id),esc=s=>String(s??'').replace(/[&<>"]/g,c=>({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;'}[c]));
const d=t=>t?new Date(t).toLocaleString('ar-SY'):'';
async function api(path,opt={}){opt.headers={'x-key':K,'content-type':'application/json',...(opt.headers||{})};
 const r=await fetch('/admin/api/'+path,opt);const j=await r.json().catch(()=>({}));if(!r.ok)throw new Error(j.error||r.status);return j}
function saveKey(){K=$('key').value.trim();localStorage.setItem('nmk',K);load()}
function logout(){localStorage.removeItem('nmk');location.reload()}
async function load(){try{S=await api('state')}catch(e){$('login').hidden=false;$('app').hidden=true;if(K)alert(e.message);return}
 $('login').hidden=true;$('app').hidden=false;render()}
function render(){const c=S.config,l=S.latest;
 $('minv').value=c.minVersion;
 $('sw').innerHTML=Object.keys(S.labels).map(k=>'<div class="row"><span>'+S.labels[k]+'</span><input type="checkbox" style="width:auto" id="sw_'+k+'" '+(c.switches[k]?'checked':'')+'></div>').join('');
 $('rel').innerHTML='آخر إصدار منشور: <b>'+esc(l.name||'لا يوجد')+'</b> (versionCode '+l.code+') · عدد الإصدارات '+l.releases+
  '<br><a href="https://github.com/${REPO}/releases" target="_blank">كل الإصدارات على GitHub</a> · <a href="${STABLE_APK}">رابط التحميل الثابت</a>';
 $('anns').innerHTML=S.announcements.map(a=>'<div class="row"><div><b>'+esc(a.title)+'</b><div class="s">'+esc(a.body)+'<br>'+d(a.ts)+'</div></div><button class="r" onclick="delAnn(\\''+a.id+'\\')">حذف</button></div>').join('')||'<p class="s">لا توجد</p>';
 const dv=S.devices.sort((a,b)=>b.ts-a.ts);$('dc').textContent=dv.length;
 const byV={};dv.forEach(x=>byV[x.vn+' ('+x.vc+')']=(byV[x.vn+' ('+x.vc+')']||0)+1);
 $('vers').innerHTML=Object.entries(byV).sort().reverse().map(([v,n])=>'<div class="row"><span>'+esc(v)+'</span><span style="flex:1;margin:0 8px"><div class="bar" style="width:'+(100*n/dv.length)+'%"></div></span><b>'+n+'</b></div>').join('');
 $('devs').innerHTML=dv.slice(0,100).map(x=>'<div class="row"><div>'+esc(x.model)+' <span class="s">أندرويد '+esc(x.android)+' · '+esc(x.method)+' · '+x.sims+' شريحة</span></div><span class="s">'+esc(x.vn)+'<br>'+d(x.ts)+'</span></div>').join('');
 $('compat').innerHTML=S.compat.sort((a,b)=>b.ts-a.ts).map(x=>'<div class="row"><div><span class="'+(x.ok?'ok':'bad')+'">'+(x.ok?'✓ يعمل':'✗ لا يعمل مباشرة')+'</span> '+esc(x.model)+' <span class="s">أندرويد '+esc(x.android)+' · '+d(x.ts)+'</span></div><button class="g" onclick="view(\\''+encodeURIComponent(x.key)+'\\')">عرض</button></div>').join('')||'<p class="s">لا توجد</p>';
 $('crashes').innerHTML=S.crashes.sort((a,b)=>b.ts-a.ts).map(x=>'<div class="row"><div>'+esc(x.model)+' · v'+x.vc+'<div class="s">'+esc(x.head)+'<br>'+d(x.ts)+'</div></div><div><button class="g" onclick="view(\\''+encodeURIComponent(x.key)+'\\')">عرض</button><button class="r" onclick="delItem(\\''+encodeURIComponent(x.key)+'\\')">حذف</button></div></div>').join('')||'<p class="s ok">لا توجد أعطال 👍</p>';
 const chk=[[l.code>0,'يوجد إصدار منشور على GitHub Releases مع versionCode'],[true,'الحد الأدنى للإصدار مفعّل من أول نسخة (القيمة الحالية '+c.minVersion+')'],
  [true,'قناة الإعلانات تعمل'],[true,'مفاتيح الإيقاف عن بُعد تعمل'],[dv.length>0,'وصلت إحصائيات من جهاز واحد على الأقل'],
  [S.compat.some(x=>x.ok),'تقرير توافق ناجح من جهاز واحد على الأقل'],[l.releases>=1,'الإصدارات القديمة محفوظة للرجوع (rollback)'],
  [S.crashes.length===0,'لا توجد أعطال مفتوحة']];
 $('check').innerHTML=chk.map(([ok,t])=>'<div class="row"><span>'+t+'</span><b class="'+(ok?'ok':'bad')+'">'+(ok?'✓':'✗ ناقص')+'</b></div>').join('')}
async function saveCfg(){const sw={};Object.keys(S.labels).forEach(k=>sw[k]=$('sw_'+k).checked);
 try{await api('config',{method:'POST',body:JSON.stringify({minVersion:+$('minv').value||0,switches:sw})});alert('تم الحفظ');load()}catch(e){alert(e.message)}}
async function addAnn(){try{await api('ann',{method:'POST',body:JSON.stringify({title:$('at').value,body:$('ab').value,url:$('au').value})});
 $('at').value=$('ab').value=$('au').value='';load()}catch(e){alert(e.message)}}
async function delAnn(id){if(confirm('حذف الإعلان؟')){await api('ann?id='+id,{method:'DELETE'});load()}}
async function delItem(k){if(confirm('حذف؟')){await api('item?key='+k,{method:'DELETE'});load()}}
async function view(k){const j=await api('item?key='+k);const w=open('','_blank');
 w.document.write('<meta charset=utf-8><meta name=viewport content="width=device-width"><pre style="white-space:pre-wrap;font-size:13px">'+esc(j.report||j.trace||JSON.stringify(j,null,2))+'</pre>')}
if(K)load();
</script></body></html>`;
