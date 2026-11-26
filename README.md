# نمط الشبكة (network-mode)

تطبيق أندرويد خفيف لتثبيت نمط الشبكة لكل شريحة (تلقائي شامل، 4G + مكالمات، 4G/3G، LTE فقط…)، بدون إعلانات وبدون تتبّع.

- **التطبيق المباشر:** عبر Shizuku (أو root) بالأمر `cmd phone set-allowed-network-types-for-users -s <slot> <bitmask>` — أندرويد 12+.
- **الطريقة اليدوية:** فتح قائمة Phone info مع شرح ما يجب اختياره.
- **السيرفر:** Cloudflare Worker في `worker/` (إعدادات بعيدة، إجبار تحديث، إعلانات، إحصائيات، أعطال، تقارير توافق). لوحة الأدمن على `/admin`.

## التنزيل
الرابط الثابت لآخر نسخة: https://github.com/makanona-team/network-mode/releases/latest/download/network-mode.apk

## الأسرار المطلوبة (Settings → Secrets → Actions)
| السر | الاستخدام |
|---|---|
| `APP_SECRET` | فك مفتاح التوقيع `keystore.enc` + مفتاح لوحة الأدمن |
| `CLOUDFLARE_API_TOKEN` | نشر السيرفر (صلاحيات Workers Scripts: Edit و Workers KV Storage: Edit) |
| `CLOUDFLARE_ACCOUNT_ID` | حساب Cloudflare |

## إصدار جديد
1. ارفع `versionCode` و `versionName` في `app/build.gradle.kts`.
2. اكتب الجديد في `WHATSNEW.md`.
3. ادفع إلى `main` ← يُبنى الـ APK ويُنشر تلقائياً على Releases.
4. للإجبار على التحديث: لوحة الأدمن ← «الحد الأدنى للإصدار».

## قائمة ما قبل أول إصدار
تظهر تلقائياً في أعلى لوحة الأدمن (إصدار منشور، إحصائيات، تقرير توافق ناجح، لا أعطال…).
