# CORTEX POS — Tuzatishlar (v1.1.0)

## ✅ 7 ta asosiy muammo - barchasi tuzatildi

### 1. Xona ichidagi buyurtma soddalashtirildi ✅
**Fayl:** `MainActivity.kt` (RoomCard, SimpleRoomOrderDialog, OrderRow)

- `SimpleRoomOrderDialog` — endi mahsulot qo'shilib "Qo'shish" bosilganda buyurtma avtomatik `confirmed` statusiga o'tadi va dialog yopiladi.
- `OrderRow` — "Tayyorlashni boshlash" va "Tayyor deb belgilash" tugmalari **olib tashlandi**.
- Faqat ikki amal qoldi: **Tahrir** (mahsulot qo'shish/o'chirish) va **To'lov va Chek**.
- `RoomCard` ko'rinishi:
  - **Chap:** xona nomi + holat indikatori (yashil/qizil/sariq) + sig'imi
  - **O'rta:** "+ Buyurtma" (bo'sh xonada) yoki "Tahrir" + "To'lov" tugmalari (band xonada)
  - **O'ng:** 3 nuqta menyu (faqat admin uchun) — Tahrirlash / O'chirish

### 2. Menyu ixchamlashtirildi ✅
**Fayl:** `MainActivity.kt` (MenuScreen, CompactProductRow)

- Yangi `CompactProductRow` qatorli ko'rinish — har mahsulot 1 qator
- Yuqorida **kategoriya filter** (horizontal scroll, sanoq raqamlari bilan)
- Mahsulotlar **kategoriya bo'yicha gruppalanadi**, har gruppada sarlavha
- Admin uchun har qatorda kichik Edit/Delete iconchalar

### 3. Bot config saqlanmasligi ✅
**Fayl:** `MainActivity.kt` (SettingsScreen)

- `LaunchedEffect(refreshKey)` mexanizmi qo'shildi
- Har safar Sozlamalar tabiga kirilganda bot config qayta yuklanadi
- "Saqlandi va ulandi" deganidan keyin chiqib qayta kirsangiz, holat to'g'ri saqlanib qoladi

### 4. Iconlar joylandi ✅
**Fayllar:** 5 ta `mipmap-*` papkalar + `mipmap-anydpi-v26/ic_launcher.xml`

- 5 ta o'lcham uchun PNG iconlar yaratildi (48px - 192px)
- "C" harfli, dark blue + amber dot dizayni
- Adaptive icon Android 8.0+ uchun
- `AndroidManifest.xml` ga `android:icon` va `android:roundIcon` qo'shildi

### 5. Chek PDF formatida ✅
**Fayl:** `MainActivity.kt` (shareReceiptAsPdf, ReceiptDialog)

- Yangi `shareReceiptAsPdf` funksiyasi — Android `PdfDocument` API
- ReceiptDialog'da "PDF Yuborish" tugmasi
- FileProvider orqali xavfsiz ulashish (Telegram, WhatsApp, email va boshqalar)
- Fallback: agar PDF xato bersa, matn formatida yuboradi

### 6. Login persistence ✅
**Fayllar:** `CortexPOSApp` (MainActivity.kt) + `validateToken` (ApiService.kt)

- Tokenni `SharedPreferences`'da saqlaydi (token + role + full_name)
- Ilova ochilganda `/auth/me` endpointi orqali tokenni tekshiradi
- Internet yo'q bo'lsa ham foydalanuvchi tizimda qoladi (offline mode)
- Faqat 401 bo'lsa logout bo'ladi

### 7. APK tezligi ✅
**Fayllar:** `ApiService.kt`, `gradle.properties`, `build.gradle`

- HttpLoggingInterceptor **olib tashlandi** (BODY level juda sekin edi)
- Timeoutlar 30s → 20s
- `retryOnConnectionFailure(true)` qo'shildi
- gradle.properties: `parallel=true`, `caching=true`, JVM heap 4GB
- enableJetifier=false (kerakmas, Android 35 ishlatilyapti)
- Yangi versiya dependencylari (core-ktx 1.12, activity-compose 1.8, navigation 2.7.5)

---

## ✅ Qo'shimcha tuzatishlar

### To'lov tarixi ochilmasligi ✅
PaymentScreen 2 ta tab bilan: **Aktiv** | **Tarix**. Tarixda barcha to'langan va bekor qilingan buyurtmalar.

### Aktiv tepada, tarix pastda ✅
RoomsTab tartibi:
1. Xonalar (RoomCard)
2. Aktiv buyurtmalar (xona bog'liq emas) — sarlavha bilan
3. Buyurtmalar tarixi — `CompactOrderRow` bilan

### CompactOrderRow ✅
Yopiq holatda: **#ID + ism + narx + sana**.
Bosilsa kengayadi va to'liq buyurtma ko'rinadi (status, telefon, izoh, mahsulotlar).

---

## O'rnatish

```bash
cd cortex/android
./gradlew assembleDebug
# yoki release uchun:
./gradlew assembleRelease
```

APK joylashuvi: `cortex/android/app/build/outputs/apk/debug/app-debug.apk`

---

## Backend versiya

Backend (FastAPI) o'zgarmadi. Allaqachon mavjud `/bot-config`, `/orders/{id}/extra` endpointlari ishlatildi.
URL: `https://cortex-pos-cortex.up.railway.app`
