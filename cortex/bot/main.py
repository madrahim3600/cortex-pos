"""
CORTEX POS — Telegram Bot
──────────────────────────────────────────────────────────────────
Xizmatlar:
- 🥡 Olib ketish — menyu → savat → telefon → tasdiq
- 🪑 Joy buyurtma:
    1) xona tanlash
    2) vaqt:
       • "🟢 Hozir" → menyu → savat → telefon → tasdiq (joy + mahsulot)
       • "🕐 Boshqa vaqt" → sana (bugun/ertaga/...) → soat → telefon → izoh → tasdiq

Hammasi backend'da order_type="bot" (online) sifatida saqlanadi.
"""
import asyncio
import logging
import os
from datetime import datetime, timedelta

import aiohttp
from aiohttp import web
from dotenv import load_dotenv
from aiogram import Bot, Dispatcher, types, F
from aiogram.filters import CommandStart
from aiogram.fsm.context import FSMContext
from aiogram.fsm.state import State, StatesGroup
from aiogram.fsm.storage.memory import MemoryStorage
from aiogram.types import (
    ReplyKeyboardMarkup, KeyboardButton, ReplyKeyboardRemove,
    InlineKeyboardMarkup, InlineKeyboardButton
)

load_dotenv()
logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(message)s")
logger = logging.getLogger(__name__)

BACKEND_URL = os.getenv("BACKEND_URL", "http://localhost:8000")
WORK_START_HOUR = int(os.getenv("WORK_START_HOUR", "9"))
WORK_END_HOUR = int(os.getenv("WORK_END_HOUR", "22"))  # oxirgi band qilish soati
RESERVE_DAYS_AHEAD = int(os.getenv("RESERVE_DAYS_AHEAD", "3"))  # bugundan tashqari nechta kun

UZ_MONTHS = ["Yanvar", "Fevral", "Mart", "Aprel", "May", "Iyun",
             "Iyul", "Avgust", "Sentabr", "Oktabr", "Noyabr", "Dekabr"]

# { bot_token: { bot, dp, tenant_id, tenant_name, admin_chat_id, task } }
active_bots: dict = {}


# ══════════════════════════════════════════════
# FSM
# ══════════════════════════════════════════════
class S(StatesGroup):
    # umumiy buyurtma oqimi (olib ketish va joy-hozir)
    browsing       = State()
    entering_phone = State()
    entering_note  = State()
    confirming     = State()
    # joy buyurtma
    choosing_room  = State()
    choosing_time  = State()       # Hozir / Boshqa vaqt
    choosing_date  = State()       # bugun, ertaga, ...
    choosing_hour  = State()       # 09:00, 10:00, ...
    # oldindan band qilish (mahsulotsiz)
    reserve_phone   = State()
    reserve_note    = State()
    reserve_confirm = State()


# ══════════════════════════════════════════════
# API
# ══════════════════════════════════════════════
async def api_get(path: str):
    try:
        async with aiohttp.ClientSession() as s:
            async with s.get(f"{BACKEND_URL}{path}", timeout=aiohttp.ClientTimeout(total=10)) as r:
                if r.status == 200:
                    return await r.json()
                logger.error(f"API GET {path}: {r.status}")
    except Exception as e:
        logger.error(f"API GET {path}: {e}")
    return None


async def api_post(path: str, data: dict):
    try:
        async with aiohttp.ClientSession() as s:
            async with s.post(f"{BACKEND_URL}{path}", json=data, timeout=aiohttp.ClientTimeout(total=10)) as r:
                if r.status in [200, 201]:
                    return await r.json()
                text = await r.text()
                logger.error(f"API POST {path}: {r.status} - {text}")
    except Exception as e:
        logger.error(f"API POST {path}: {e}")
    return None


# ══════════════════════════════════════════════
# REPLY KEYBOARDS
# ══════════════════════════════════════════════
def main_kb():
    return ReplyKeyboardMarkup(keyboard=[
        [KeyboardButton(text="🥡 Olib ketish"), KeyboardButton(text="🪑 Joy buyurtma")],
        [KeyboardButton(text="📋 Buyurtmalarim"), KeyboardButton(text="📖 Menyu")],
    ], resize_keyboard=True)


def phone_kb():
    return ReplyKeyboardMarkup(keyboard=[
        [KeyboardButton(text="📱 Raqamimni yuborish", request_contact=True)],
        [KeyboardButton(text="❌ Bekor qilish")],
    ], resize_keyboard=True)


def note_kb():
    return ReplyKeyboardMarkup(keyboard=[
        [KeyboardButton(text="⏭ Izohsiz davom etish")],
        [KeyboardButton(text="❌ Bekor qilish")],
    ], resize_keyboard=True)


def confirm_kb():
    return ReplyKeyboardMarkup(keyboard=[
        [KeyboardButton(text="✅ Tasdiqlash")],
        [KeyboardButton(text="❌ Bekor qilish")],
    ], resize_keyboard=True)


# ══════════════════════════════════════════════
# INLINE KEYBOARDS
# ══════════════════════════════════════════════
def rooms_inline(rooms: list):
    free = [r for r in rooms if r.get("status") == "free"]
    if not free:
        return None
    kb = []
    for r in free:
        kb.append([InlineKeyboardButton(
            text=f"🪑 {r['name']} • {r.get('capacity', 0)} kishilik",
            callback_data=f"room:{r['id']}"
        )])
    kb.append([InlineKeyboardButton(text="❌ Bekor qilish", callback_data="cancel:all")])
    return InlineKeyboardMarkup(inline_keyboard=kb)


def time_choice_inline():
    """Hozir / Boshqa vaqt"""
    return InlineKeyboardMarkup(inline_keyboard=[
        [InlineKeyboardButton(text="🟢 Hozir (joy + buyurtma)", callback_data="time:now")],
        [InlineKeyboardButton(text="🗓 Boshqa vaqtga oldindan band", callback_data="time:later")],
        [InlineKeyboardButton(text="◀️ Xonalar", callback_data="back:rooms")],
    ])


def dates_inline():
    """Bugun, ertaga, 2 kun keyin, ..."""
    kb = []
    today = datetime.now().date()
    for i in range(RESERVE_DAYS_AHEAD + 1):
        d = today + timedelta(days=i)
        if i == 0:
            label = f"📅 Bugun ({d.day}-{UZ_MONTHS[d.month - 1].lower()})"
        elif i == 1:
            label = f"📅 Ertaga ({d.day}-{UZ_MONTHS[d.month - 1].lower()})"
        else:
            label = f"📅 {d.day}-{UZ_MONTHS[d.month - 1].lower()}"
        kb.append([InlineKeyboardButton(text=label, callback_data=f"date:{d.isoformat()}")])
    kb.append([InlineKeyboardButton(text="◀️ Vaqt", callback_data="back:time")])
    return InlineKeyboardMarkup(inline_keyboard=kb)


def hours_inline(date_iso: str):
    """Soat tanlash. Bugun bo'lsa, hozirgi vaqtdan keyingilar."""
    target_date = datetime.fromisoformat(date_iso).date()
    today = datetime.now().date()
    now = datetime.now()
    if target_date == today:
        start = max(WORK_START_HOUR, now.hour + 1 if now.minute > 0 else now.hour)
    else:
        start = WORK_START_HOUR
    if start > WORK_END_HOUR:
        return None
    kb = []
    row = []
    for h in range(start, WORK_END_HOUR + 1):
        row.append(InlineKeyboardButton(text=f"{h:02d}:00", callback_data=f"hour:{h}"))
        if len(row) == 3:
            kb.append(row)
            row = []
    if row:
        kb.append(row)
    kb.append([InlineKeyboardButton(text="◀️ Sana", callback_data="back:date")])
    return InlineKeyboardMarkup(inline_keyboard=kb)


def categories_inline(cats: list, cart: dict):
    kb = []
    for c in cats:
        kb.append([InlineKeyboardButton(
            text=f"📂 {c['name'].strip()}",
            callback_data=f"cat:{c['id']}"
        )])
    if cart:
        total = sum(v["price"] * v["qty"] for v in cart.values())
        qty = sum(v["qty"] for v in cart.values())
        kb.append([InlineKeyboardButton(
            text=f"🛒 Savat ({qty} ta) — {int(total):,} so'm",
            callback_data="cart:view"
        )])
    return InlineKeyboardMarkup(inline_keyboard=kb)


def products_inline(products: list, cart: dict):
    kb = []
    for p in products:
        pid = str(p["id"])
        qty = cart.get(pid, {}).get("qty", 0)
        name = p["name"].strip()
        price = int(p["price"])
        if qty == 0:
            kb.append([InlineKeyboardButton(
                text=f"➕ {name} — {price:,} so'm",
                callback_data=f"add:{p['id']}"
            )])
        else:
            kb.append([
                InlineKeyboardButton(text="➖", callback_data=f"sub:{p['id']}"),
                InlineKeyboardButton(text=f"{name} ({qty})", callback_data="noop"),
                InlineKeyboardButton(text="➕", callback_data=f"add:{p['id']}"),
            ])
    nav = []
    if cart:
        total = sum(v["price"] * v["qty"] for v in cart.values())
        nav.append(InlineKeyboardButton(text=f"🛒 Savat — {int(total):,} so'm", callback_data="cart:view"))
    nav.append(InlineKeyboardButton(text="◀️ Kategoriyalar", callback_data="cat:back"))
    kb.append(nav)
    return InlineKeyboardMarkup(inline_keyboard=kb)


def cart_inline(cart: dict):
    kb = []
    for pid, item in cart.items():
        kb.append([InlineKeyboardButton(
            text=f"{item['name']} ({item['qty']}) = {int(item['price'] * item['qty']):,} so'm",
            callback_data="noop"
        )])
        kb.append([
            InlineKeyboardButton(text="➖", callback_data=f"sub:{pid}"),
            InlineKeyboardButton(text="🗑 O'chirish", callback_data=f"del:{pid}"),
            InlineKeyboardButton(text="➕", callback_data=f"add:{pid}"),
        ])
    kb.append([InlineKeyboardButton(text="✅ Buyurtmani rasmiylashtirish", callback_data="cart:checkout")])
    kb.append([
        InlineKeyboardButton(text="◀️ Menyuga", callback_data="cat:back"),
        InlineKeyboardButton(text="🗑 Tozalash", callback_data="cart:clear"),
    ])
    return InlineKeyboardMarkup(inline_keyboard=kb)


def admin_order_inline(order_id: int):
    return InlineKeyboardMarkup(inline_keyboard=[[
        InlineKeyboardButton(text="✅ Qabul qilish", callback_data=f"accept_{order_id}"),
        InlineKeyboardButton(text="❌ Rad etish", callback_data=f"reject_{order_id}"),
    ]])


# ══════════════════════════════════════════════
# FORMATTERS
# ══════════════════════════════════════════════
def fmt_cart(cart: dict) -> str:
    if not cart:
        return "🛒 Savat bo'sh"
    lines = ["🛒 *Savatingiz:*\n"]
    total = 0
    for item in cart.values():
        lt = item["price"] * item["qty"]
        total += lt
        lines.append(f"• {item['name']} × {item['qty']} = *{int(lt):,}* so'm")
    lines.append(f"\n💰 *Jami: {int(total):,} so'm*")
    return "\n".join(lines)


def fmt_admin_notify(order_id, kind_label, room_label, time_label, cart, phone, note, customer, tenant_name):
    """kind_label: '🥡 Olib ketish', '🪑 Joy buyurtma', '🗓 Oldindan band'"""
    lines = [
        f"🔔 *Yangi buyurtma!*",
        f"🏪 {tenant_name}",
        f"━━━━━━━━━━━━━━━━━━",
        f"📋 #{str(order_id).zfill(4)}",
        f"{kind_label}",
    ]
    if room_label:
        lines.append(room_label)
    if time_label:
        lines.append(time_label)
    lines.append(f"👤 {customer or 'Mehmon'}")
    lines.append(f"📱 {phone}")
    if note:
        lines.append(f"💬 {note}")
    lines.append("━━━━━━━━━━━━━━━━━━")
    if cart:
        total = 0
        for item in cart.values():
            lt = item["price"] * item["qty"]
            total += lt
            lines.append(f"• {item['name']} × {item['qty']} = {int(lt):,} so'm")
        lines.append("━━━━━━━━━━━━━━━━━━")
        lines.append(f"💰 *Jami: {int(total):,} so'm*")
    else:
        lines.append("_(Mahsulot tanlanmagan — faqat joy band qilingan)_")
    return "\n".join(lines)


def fmt_receipt(order_data, tenant_name):
    lines = [
        f"🧾 *{tenant_name}*",
        f"━━━━━━━━━━━━━━━━━━",
        f"📋 #{str(order_data.get('id', 0)).zfill(4)}",
        f"━━━━━━━━━━━━━━━━━━",
    ]
    for item in order_data.get("items", []):
        lines.append(
            f"• {item['product_name']} × {int(item['quantity'])}\n"
            f"  {int(item['unit_price']):,} × {int(item['quantity'])} = *{int(item['total_price']):,}* so'm"
        )
    if order_data.get("discount", 0) > 0:
        lines.append(f"\n🎁 Chegirma: -{int(order_data['discount']):,} so'm")
    lines.append("━━━━━━━━━━━━━━━━━━")
    lines.append(f"💰 *JAMI: {int(order_data.get('total', 0)):,} so'm*")
    method_map = {"cash": "💵 Naqd", "card": "💳 Karta", "click": "📱 Click", "payme": "📱 Payme"}
    method = method_map.get(order_data.get("method", ""), order_data.get("method", ""))
    if method:
        lines.append(f"💳 To'lov: {method}")
    lines.append("━━━━━━━━━━━━━━━━━━")
    lines.append("✅ *Xaridingiz uchun rahmat!*")
    return "\n".join(lines)


def fmt_status(status: str) -> str:
    m = {
        "new": "🆕 Yangi",
        "confirmed": "✅ Tasdiqlangan",
        "preparing": "👨‍🍳 Tayyorlanmoqda",
        "ready": "🔔 Tayyor",
        "paid": "💚 To'langan",
        "closed": "✔️ Yakunlangan",
        "cancelled": "❌ Bekor qilingan"
    }
    return m.get(status, status)


def fmt_date_uz(d):
    if isinstance(d, str):
        d = datetime.fromisoformat(d).date()
    return f"{d.day}-{UZ_MONTHS[d.month - 1].lower()}"


# ══════════════════════════════════════════════
# DISPATCHER
# ══════════════════════════════════════════════
def create_dispatcher(tenant_id: int, tenant_name: str, admin_chat_id: str) -> Dispatcher:
    dp = Dispatcher(storage=MemoryStorage())

    # ───────── /start ─────────
    @dp.message(CommandStart())
    async def start(msg: types.Message, state: FSMContext):
        await state.clear()
        await api_post("/public/bot-user", {
            "tenant_id": tenant_id,
            "chat_id": str(msg.from_user.id),
            "full_name": msg.from_user.full_name or "",
            "username": msg.from_user.username or ""
        })
        await msg.answer(
            f"👋 Assalomu alaykum, *{msg.from_user.full_name or 'Mehmon'}*!\n\n"
            f"🏪 *{tenant_name}*\n\n"
            f"Quyidagi xizmatlardan birini tanlang 👇",
            reply_markup=main_kb(), parse_mode="Markdown"
        )

    # ════════════════════════════════════════════
    # 🥡 OLIB KETISH
    # ════════════════════════════════════════════
    @dp.message(F.text == "🥡 Olib ketish")
    async def takeaway_start(msg: types.Message, state: FSMContext):
        cats = await api_get(f"/public/categories?tenant_id={tenant_id}") or []
        if not cats:
            await msg.answer("😔 Menyu hali sozlanmagan.", reply_markup=main_kb())
            return
        await state.clear()
        await state.update_data(
            mode="takeaway",
            kind_label="🥡 Olib ketish",
            cart={},
            cats=cats
        )
        await state.set_state(S.browsing)
        await msg.answer(
            "🥡 *Olib ketish*\n\n📂 Kategoriyani tanlang:",
            parse_mode="Markdown",
            reply_markup=categories_inline(cats, {})
        )

    # ════════════════════════════════════════════
    # 🪑 JOY BUYURTMA — boshlanish
    # ════════════════════════════════════════════
    @dp.message(F.text == "🪑 Joy buyurtma")
    async def dine_in_start(msg: types.Message, state: FSMContext):
        rooms = await api_get(f"/public/rooms?tenant_id={tenant_id}") or []
        free = [r for r in rooms if r.get("status") == "free"]
        if not free:
            await msg.answer(
                "😔 Hozir bo'sh xona yo'q.\n\nBir oz vaqtdan keyin urinib ko'ring.",
                reply_markup=main_kb()
            )
            return
        await state.clear()
        await state.update_data(rooms=rooms)
        await state.set_state(S.choosing_room)
        await msg.answer(
            f"🪑 *Bo'sh xonalar:* {len(free)} ta\n\nXonani tanlang:",
            parse_mode="Markdown",
            reply_markup=rooms_inline(rooms)
        )

    # ─── XONA TANLASH ───
    @dp.callback_query(F.data.startswith("room:"), S.choosing_room)
    async def room_selected(call: types.CallbackQuery, state: FSMContext):
        try:
            rid = int(call.data.split(":")[1])
        except (ValueError, IndexError):
            await call.answer("Xato")
            return
        data = await state.get_data()
        room = next((r for r in data.get("rooms", []) if r["id"] == rid), None)
        if not room:
            await call.answer("Xona topilmadi", show_alert=True)
            return
        await state.update_data(room_id=rid, room_name=room["name"], room_capacity=room.get("capacity", 0))
        await state.set_state(S.choosing_time)
        await call.message.edit_text(
            f"✅ *{room['name']}* ({room.get('capacity', 0)} kishilik)\n\n"
            f"🕐 *Qachon kelmoqchisiz?*",
            parse_mode="Markdown",
            reply_markup=time_choice_inline()
        )
        await call.answer()

    # ─── VAQT: HOZIR ───
    @dp.callback_query(F.data == "time:now", S.choosing_time)
    async def time_now(call: types.CallbackQuery, state: FSMContext):
        cats = await api_get(f"/public/categories?tenant_id={tenant_id}") or []
        if not cats:
            await call.answer("Menyu sozlanmagan", show_alert=True)
            return
        data = await state.get_data()
        await state.update_data(
            mode="dine_now",
            kind_label="🪑 Joy buyurtma",
            cart={},
            cats=cats
        )
        await state.set_state(S.browsing)
        await call.message.edit_text(
            f"🪑 *{data.get('room_name', '')}* — Hozir\n\n📂 Kategoriyani tanlang:",
            parse_mode="Markdown",
            reply_markup=categories_inline(cats, {})
        )
        await call.answer()

    # ─── VAQT: BOSHQA VAQT ───
    @dp.callback_query(F.data == "time:later", S.choosing_time)
    async def time_later(call: types.CallbackQuery, state: FSMContext):
        await state.set_state(S.choosing_date)
        await call.message.edit_text(
            "📅 *Sanani tanlang:*",
            parse_mode="Markdown",
            reply_markup=dates_inline()
        )
        await call.answer()

    # ─── SANA TANLASH ───
    @dp.callback_query(F.data.startswith("date:"), S.choosing_date)
    async def date_selected(call: types.CallbackQuery, state: FSMContext):
        date_iso = call.data.split(":", 1)[1]
        kb = hours_inline(date_iso)
        if kb is None:
            await call.answer("Bu sanaga ish vaqti tugagan", show_alert=True)
            return
        await state.update_data(reserve_date=date_iso)
        await state.set_state(S.choosing_hour)
        await call.message.edit_text(
            f"📅 {fmt_date_uz(date_iso)}\n\n🕐 *Soatni tanlang:*",
            parse_mode="Markdown",
            reply_markup=kb
        )
        await call.answer()

    # ─── SOAT TANLASH → telefon ───
    @dp.callback_query(F.data.startswith("hour:"), S.choosing_hour)
    async def hour_selected(call: types.CallbackQuery, state: FSMContext):
        try:
            hour = int(call.data.split(":")[1])
        except (ValueError, IndexError):
            await call.answer("Xato")
            return
        await state.update_data(reserve_hour=hour)
        await state.set_state(S.reserve_phone)
        data = await state.get_data()
        await call.message.edit_text(
            f"🪑 *{data.get('room_name', '')}*\n"
            f"📅 {fmt_date_uz(data.get('reserve_date', ''))} • 🕐 {hour:02d}:00\n\n"
            f"📱 *Telefon raqamingizni yuboring*",
            parse_mode="Markdown"
        )
        await call.message.answer(
            "Tugma orqali yuboring yoki qo'lda yozing:",
            reply_markup=phone_kb()
        )
        await call.answer()

    # ─── ORQAGA NAVIGATSIYA ───
    @dp.callback_query(F.data == "back:rooms")
    async def back_to_rooms(call: types.CallbackQuery, state: FSMContext):
        rooms = await api_get(f"/public/rooms?tenant_id={tenant_id}") or []
        free = [r for r in rooms if r.get("status") == "free"]
        if not free:
            await call.answer("Bo'sh xona yo'q", show_alert=True)
            return
        await state.update_data(rooms=rooms)
        await state.set_state(S.choosing_room)
        await call.message.edit_text(
            f"🪑 *Bo'sh xonalar:* {len(free)} ta\n\nXonani tanlang:",
            parse_mode="Markdown",
            reply_markup=rooms_inline(rooms)
        )
        await call.answer()

    @dp.callback_query(F.data == "back:time", S.choosing_date)
    async def back_to_time(call: types.CallbackQuery, state: FSMContext):
        data = await state.get_data()
        await state.set_state(S.choosing_time)
        await call.message.edit_text(
            f"✅ *{data.get('room_name', '')}*\n\n🕐 *Qachon kelmoqchisiz?*",
            parse_mode="Markdown",
            reply_markup=time_choice_inline()
        )
        await call.answer()

    @dp.callback_query(F.data == "back:date", S.choosing_hour)
    async def back_to_date(call: types.CallbackQuery, state: FSMContext):
        await state.set_state(S.choosing_date)
        await call.message.edit_text(
            "📅 *Sanani tanlang:*",
            parse_mode="Markdown",
            reply_markup=dates_inline()
        )
        await call.answer()

    @dp.callback_query(F.data == "cancel:all")
    async def cancel_all(call: types.CallbackQuery, state: FSMContext):
        await state.clear()
        try:
            await call.message.edit_text("❌ Bekor qilindi.")
        except Exception:
            pass
        await call.message.answer("Asosiy menyu:", reply_markup=main_kb())
        await call.answer()

    # ════════════════════════════════════════════
    # OLDINDAN BAND — telefon, izoh, tasdiq (mahsulotsiz)
    # ════════════════════════════════════════════
    @dp.message(F.contact, S.reserve_phone)
    async def reserve_phone_contact(msg: types.Message, state: FSMContext):
        phone = msg.contact.phone_number
        if not phone.startswith("+"):
            phone = "+" + phone
        await state.update_data(phone=phone)
        await state.set_state(S.reserve_note)
        await msg.answer("💬 *Izoh qoldiring* yoki tugmani bosing:", parse_mode="Markdown", reply_markup=note_kb())

    @dp.message(S.reserve_phone)
    async def reserve_phone_text(msg: types.Message, state: FSMContext):
        if msg.text == "❌ Bekor qilish":
            await state.clear()
            await msg.answer("❌ Bekor qilindi.", reply_markup=main_kb())
            return
        phone = (msg.text or "").strip().replace(" ", "")
        if len(phone) < 9:
            await msg.answer("❌ Noto'g'ri raqam. Qaytadan kiriting:")
            return
        await state.update_data(phone=phone)
        await state.set_state(S.reserve_note)
        await msg.answer("💬 *Izoh qoldiring* yoki tugmani bosing:", parse_mode="Markdown", reply_markup=note_kb())

    @dp.message(F.text == "⏭ Izohsiz davom etish", S.reserve_note)
    async def reserve_skip_note(msg: types.Message, state: FSMContext):
        await state.update_data(note="")
        await _show_reserve_confirm(msg, state)

    @dp.message(F.text == "❌ Bekor qilish", S.reserve_note)
    async def reserve_cancel_note(msg: types.Message, state: FSMContext):
        await state.clear()
        await msg.answer("❌ Bekor qilindi.", reply_markup=main_kb())

    @dp.message(S.reserve_note)
    async def reserve_enter_note(msg: types.Message, state: FSMContext):
        await state.update_data(note=(msg.text or "").strip())
        await _show_reserve_confirm(msg, state)

    async def _show_reserve_confirm(msg, state):
        data = await state.get_data()
        room_name = data.get("room_name", "")
        date_iso = data.get("reserve_date", "")
        hour = data.get("reserve_hour", 0)
        phone = data.get("phone", "")
        note = data.get("note", "")
        note_text = f"\n💬 Izoh: {note}" if note else ""
        await state.set_state(S.reserve_confirm)
        await msg.answer(
            f"📋 *Tasdiqlang:*\n\n"
            f"🗓 *Oldindan band qilish*\n"
            f"🪑 {room_name}\n"
            f"📅 {fmt_date_uz(date_iso)} • 🕐 {hour:02d}:00\n"
            f"📱 {phone}{note_text}\n\n"
            f"Hammasi to'g'rimi?",
            parse_mode="Markdown", reply_markup=confirm_kb()
        )

    @dp.message(F.text == "✅ Tasdiqlash", S.reserve_confirm)
    async def reserve_confirm_yes(msg: types.Message, state: FSMContext):
        data = await state.get_data()
        await msg.answer("⏳ Yuborilmoqda...", reply_markup=ReplyKeyboardRemove())

        date_iso = data.get("reserve_date", "")
        hour = data.get("reserve_hour", 0)
        room_name = data.get("room_name", "")
        try:
            reservation_dt = datetime.fromisoformat(date_iso).replace(hour=hour, minute=0)
            reservation_str = reservation_dt.strftime("%Y-%m-%d %H:%M:%S")
        except Exception:
            reservation_str = ""

        note_full = f"🗓 Oldindan band: {room_name}, {fmt_date_uz(date_iso)} {hour:02d}:00"
        if data.get("note"):
            note_full += f" | {data['note']}"

        result = await api_post("/public/bot-order", {
            "tenant_id": tenant_id,
            "order_type": "bot",
            "room_id": data.get("room_id"),
            "customer_name": msg.from_user.full_name or "",
            "customer_phone": data.get("phone", ""),
            "note": note_full,
            "reservation_time": reservation_str,
            "cart": {},  # mahsulot yo'q
            "bot_chat_id": msg.from_user.id,
            "username": msg.from_user.username or ""
        })

        if result:
            order_id = result.get("id", 0)
            await msg.answer(
                f"✅ *Joy band qilindi!*\n\n"
                f"📋 #{str(order_id).zfill(4)}\n"
                f"🪑 {room_name}\n"
                f"📅 {fmt_date_uz(date_iso)} • 🕐 {hour:02d}:00\n\n"
                f"⏳ Admin ko'rib chiqmoqda. Tasdiqlangach xabar beramiz.",
                parse_mode="Markdown", reply_markup=main_kb()
            )
            # Admin xabari
            await _notify_admin(
                order_id=order_id,
                kind_label="🗓 Oldindan band qilish",
                room_label=f"🪑 Xona: {room_name}",
                time_label=f"📅 {fmt_date_uz(date_iso)} • 🕐 {hour:02d}:00",
                cart={},
                phone=data.get("phone", ""),
                note=data.get("note", ""),
                customer=msg.from_user.full_name or ""
            )
        else:
            await msg.answer("❌ Xato yuz berdi.", reply_markup=main_kb())
        await state.clear()

    @dp.message(F.text == "❌ Bekor qilish", S.reserve_confirm)
    async def reserve_confirm_cancel(msg: types.Message, state: FSMContext):
        await state.clear()
        await msg.answer("❌ Bekor qilindi.", reply_markup=main_kb())

    # ════════════════════════════════════════════
    # MENYU OQIMI (browsing) — olib ketish va joy-hozir
    # ════════════════════════════════════════════
    @dp.callback_query(F.data.startswith("cat:"), S.browsing)
    async def category_callback(call: types.CallbackQuery, state: FSMContext):
        action = call.data.split(":", 1)[1]
        data = await state.get_data()
        cart = data.get("cart", {})

        if action == "back":
            cats = data.get("cats") or await api_get(f"/public/categories?tenant_id={tenant_id}") or []
            await call.message.edit_text(
                "📂 *Kategoriyani tanlang:*",
                parse_mode="Markdown",
                reply_markup=categories_inline(cats, cart)
            )
            await call.answer()
            return

        try:
            cat_id = int(action)
        except ValueError:
            await call.answer("Xato")
            return

        prods = await api_get(f"/public/products?tenant_id={tenant_id}&category_id={cat_id}") or []
        if not prods:
            await call.answer("😔 Mahsulot yo'q", show_alert=True)
            return

        prod_map = {str(p["id"]): {"name": p["name"].strip(), "price": p["price"]} for p in prods}
        all_products = data.get("all_products", {})
        all_products.update(prod_map)
        await state.update_data(all_products=all_products, cur_cat=cat_id)

        await call.message.edit_text(
            f"🍽 *Mahsulotlar:*",
            parse_mode="Markdown",
            reply_markup=products_inline(prods, cart)
        )
        await call.answer()

    @dp.callback_query(F.data.startswith("add:"), S.browsing)
    async def add_product(call: types.CallbackQuery, state: FSMContext):
        pid = call.data.split(":", 1)[1]
        data = await state.get_data()
        all_products = data.get("all_products", {})
        if pid not in all_products:
            await call.answer("Mahsulot topilmadi")
            return
        cart = data.get("cart", {})
        if pid in cart:
            cart[pid]["qty"] += 1
        else:
            cart[pid] = {
                "name": all_products[pid]["name"],
                "price": all_products[pid]["price"],
                "qty": 1
            }
        await state.update_data(cart=cart)

        cur_cat = data.get("cur_cat")
        msg_text = call.message.text or ""
        try:
            if "Savat" in msg_text:
                await call.message.edit_text(
                    fmt_cart(cart), parse_mode="Markdown",
                    reply_markup=cart_inline(cart)
                )
            elif cur_cat:
                prods = await api_get(f"/public/products?tenant_id={tenant_id}&category_id={cur_cat}") or []
                await call.message.edit_reply_markup(reply_markup=products_inline(prods, cart))
        except Exception:
            pass
        await call.answer(f"✅ {all_products[pid]['name']}")

    @dp.callback_query(F.data.startswith("sub:"), S.browsing)
    async def sub_product(call: types.CallbackQuery, state: FSMContext):
        pid = call.data.split(":", 1)[1]
        data = await state.get_data()
        cart = data.get("cart", {})
        if pid not in cart:
            await call.answer("Savatda yo'q")
            return
        cart[pid]["qty"] -= 1
        name = cart[pid]["name"]
        if cart[pid]["qty"] <= 0:
            del cart[pid]
        await state.update_data(cart=cart)

        msg_text = call.message.text or ""
        cur_cat = data.get("cur_cat")
        try:
            if "Savat" in msg_text and not cart:
                cats = data.get("cats") or await api_get(f"/public/categories?tenant_id={tenant_id}") or []
                await call.message.edit_text(
                    "🛒 Savat bo'sh.\n\n📂 *Kategoriyani tanlang:*",
                    parse_mode="Markdown",
                    reply_markup=categories_inline(cats, {})
                )
            elif "Savat" in msg_text:
                await call.message.edit_text(
                    fmt_cart(cart), parse_mode="Markdown",
                    reply_markup=cart_inline(cart)
                )
            elif cur_cat:
                prods = await api_get(f"/public/products?tenant_id={tenant_id}&category_id={cur_cat}") or []
                await call.message.edit_reply_markup(reply_markup=products_inline(prods, cart))
        except Exception:
            pass
        await call.answer(f"➖ {name}")

    @dp.callback_query(F.data.startswith("del:"), S.browsing)
    async def del_product(call: types.CallbackQuery, state: FSMContext):
        pid = call.data.split(":", 1)[1]
        data = await state.get_data()
        cart = data.get("cart", {})
        if pid not in cart:
            await call.answer("Savatda yo'q")
            return
        name = cart[pid]["name"]
        del cart[pid]
        await state.update_data(cart=cart)
        if not cart:
            cats = data.get("cats") or await api_get(f"/public/categories?tenant_id={tenant_id}") or []
            await call.message.edit_text(
                "🛒 Savat bo'sh.\n\n📂 *Kategoriyani tanlang:*",
                parse_mode="Markdown",
                reply_markup=categories_inline(cats, {})
            )
        else:
            await call.message.edit_text(
                fmt_cart(cart), parse_mode="Markdown",
                reply_markup=cart_inline(cart)
            )
        await call.answer(f"🗑 {name}")

    @dp.callback_query(F.data == "cart:view", S.browsing)
    async def view_cart(call: types.CallbackQuery, state: FSMContext):
        data = await state.get_data()
        cart = data.get("cart", {})
        if not cart:
            await call.answer("Savat bo'sh", show_alert=True)
            return
        await call.message.edit_text(fmt_cart(cart), parse_mode="Markdown", reply_markup=cart_inline(cart))
        await call.answer()

    @dp.callback_query(F.data == "cart:clear", S.browsing)
    async def clear_cart(call: types.CallbackQuery, state: FSMContext):
        await state.update_data(cart={})
        cats = (await state.get_data()).get("cats") or await api_get(f"/public/categories?tenant_id={tenant_id}") or []
        await call.message.edit_text(
            "🗑 Savat tozalandi.\n\n📂 *Kategoriyani tanlang:*",
            parse_mode="Markdown",
            reply_markup=categories_inline(cats, {})
        )
        await call.answer("Tozalandi")

    @dp.callback_query(F.data == "cart:checkout", S.browsing)
    async def checkout(call: types.CallbackQuery, state: FSMContext):
        data = await state.get_data()
        cart = data.get("cart", {})
        if not cart:
            await call.answer("Savat bo'sh!", show_alert=True)
            return
        await state.set_state(S.entering_phone)
        await call.message.edit_text(
            fmt_cart(cart) + "\n\n📱 *Telefon raqamingizni yuboring*",
            parse_mode="Markdown"
        )
        await call.message.answer("Tugma orqali yuboring yoki qo'lda yozing:", reply_markup=phone_kb())
        await call.answer()

    @dp.callback_query(F.data == "noop")
    async def noop(call: types.CallbackQuery):
        await call.answer()

    # ─── TELEFON, IZOH, TASDIQ (olib ketish va joy-hozir) ───
    @dp.message(F.contact, S.entering_phone)
    async def phone_contact(msg: types.Message, state: FSMContext):
        phone = msg.contact.phone_number
        if not phone.startswith("+"):
            phone = "+" + phone
        await state.update_data(phone=phone)
        await state.set_state(S.entering_note)
        await msg.answer("💬 *Izoh qoldiring* yoki tugmani bosing:", parse_mode="Markdown", reply_markup=note_kb())

    @dp.message(S.entering_phone)
    async def phone_text(msg: types.Message, state: FSMContext):
        if msg.text == "❌ Bekor qilish":
            await state.clear()
            await msg.answer("❌ Bekor qilindi.", reply_markup=main_kb())
            return
        phone = (msg.text or "").strip().replace(" ", "")
        if len(phone) < 9:
            await msg.answer("❌ Noto'g'ri raqam. Qaytadan kiriting:")
            return
        await state.update_data(phone=phone)
        await state.set_state(S.entering_note)
        await msg.answer("💬 *Izoh qoldiring* yoki tugmani bosing:", parse_mode="Markdown", reply_markup=note_kb())

    @dp.message(F.text == "⏭ Izohsiz davom etish", S.entering_note)
    async def skip_note(msg: types.Message, state: FSMContext):
        await state.update_data(note="")
        await _show_confirm(msg, state)

    @dp.message(F.text == "❌ Bekor qilish", S.entering_note)
    async def cancel_at_note(msg: types.Message, state: FSMContext):
        await state.clear()
        await msg.answer("❌ Bekor qilindi.", reply_markup=main_kb())

    @dp.message(S.entering_note)
    async def enter_note(msg: types.Message, state: FSMContext):
        await state.update_data(note=(msg.text or "").strip())
        await _show_confirm(msg, state)

    async def _show_confirm(msg, state):
        data = await state.get_data()
        cart = data.get("cart", {})
        phone = data.get("phone", "")
        note = data.get("note", "")
        mode = data.get("mode", "takeaway")
        room_name = data.get("room_name", "")
        kind_label = data.get("kind_label", "🥡 Olib ketish")

        head = f"{kind_label}"
        if mode == "dine_now" and room_name:
            head += f"\n🪑 Xona: {room_name}"
        note_text = f"\n💬 Izoh: {note}" if note else ""

        await state.set_state(S.confirming)
        await msg.answer(
            f"📋 *Tasdiqlang:*\n\n"
            f"{head}\n"
            f"📱 {phone}{note_text}\n\n"
            f"{fmt_cart(cart)}\n\n"
            f"Hammasi to'g'rimi?",
            parse_mode="Markdown", reply_markup=confirm_kb()
        )

    @dp.message(F.text == "✅ Tasdiqlash", S.confirming)
    async def confirm_order(msg: types.Message, state: FSMContext):
        data = await state.get_data()
        await msg.answer("⏳ Buyurtma yuborilmoqda...", reply_markup=ReplyKeyboardRemove())

        mode = data.get("mode", "takeaway")
        room_name = data.get("room_name", "")
        kind_label = data.get("kind_label", "🥡 Olib ketish")

        # note maydonini boyitamiz
        if mode == "takeaway":
            note_full = "🥡 Olib ketish"
        elif mode == "dine_now":
            note_full = f"🪑 Joy: {room_name}"
        else:
            note_full = kind_label

        if data.get("note"):
            note_full += f" | {data['note']}"

        result = await api_post("/public/bot-order", {
            "tenant_id": tenant_id,
            "order_type": "bot",
            "room_id": data.get("room_id") if mode == "dine_now" else None,
            "customer_name": msg.from_user.full_name or "",
            "customer_phone": data.get("phone", ""),
            "note": note_full,
            "cart": {pid: v["qty"] for pid, v in data.get("cart", {}).items()},
            "bot_chat_id": msg.from_user.id,
            "username": msg.from_user.username or ""
        })

        if result:
            order_id = result.get("id", 0)
            total = result.get("total", 0)
            await msg.answer(
                f"✅ *Buyurtma qabul qilindi!*\n\n"
                f"📋 #{str(order_id).zfill(4)}\n"
                f"{kind_label}"
                + (f"\n🪑 {room_name}" if mode == "dine_now" and room_name else "")
                + f"\n💰 *{int(total):,} so'm*\n\n"
                f"⏳ Admin ko'rib chiqmoqda.",
                parse_mode="Markdown", reply_markup=main_kb()
            )
            await _notify_admin(
                order_id=order_id,
                kind_label=kind_label,
                room_label=f"🪑 Xona: {room_name}" if mode == "dine_now" and room_name else "",
                time_label="🕐 Hozir" if mode == "dine_now" else "",
                cart=data.get("cart", {}),
                phone=data.get("phone", ""),
                note=data.get("note", ""),
                customer=msg.from_user.full_name or ""
            )
        else:
            await msg.answer("❌ Xato yuz berdi.", reply_markup=main_kb())
        await state.clear()

    @dp.message(F.text == "❌ Bekor qilish", S.confirming)
    async def cancel_order(msg: types.Message, state: FSMContext):
        await state.clear()
        await msg.answer("❌ Bekor qilindi.", reply_markup=main_kb())

    # ════════════════════════════════════════════
    # ADMIN GA XABAR
    # ════════════════════════════════════════════
    async def _notify_admin(order_id, kind_label, room_label, time_label, cart, phone, note, customer):
        if not admin_chat_id:
            return
        bot_entry = next(
            (v for v in active_bots.values() if v["tenant_id"] == tenant_id),
            None
        )
        if not bot_entry:
            return
        try:
            await bot_entry["bot"].send_message(
                chat_id=int(admin_chat_id),
                text=fmt_admin_notify(order_id, kind_label, room_label, time_label,
                                      cart, phone, note, customer, tenant_name),
                reply_markup=admin_order_inline(order_id),
                parse_mode="Markdown"
            )
        except Exception as e:
            logger.error(f"Admin xabar: {e}")

    # ════════════════════════════════════════════
    # 📋 BUYURTMALARIM
    # ════════════════════════════════════════════
    @dp.message(F.text == "📋 Buyurtmalarim")
    async def my_orders(msg: types.Message):
        orders = await api_get(f"/public/my-orders?tenant_id={tenant_id}&chat_id={msg.from_user.id}") or []
        if not orders:
            await msg.answer(
                "📭 Sizda hali buyurtma yo'q.",
                reply_markup=main_kb()
            )
            return
        kb = []
        for o in orders[:10]:
            oid = o.get("id", 0)
            status = o.get("status", "")
            total = int(o.get("total", 0))
            label = f"#{str(oid).zfill(4)} • {fmt_status(status)}"
            if total > 0:
                label += f" • {total:,} so'm"
            kb.append([InlineKeyboardButton(text=label, callback_data=f"order:{oid}")])
        await msg.answer(
            "📋 *Buyurtmalaringiz:*\n\n_Batafsil ko'rish uchun bosing 👇_",
            parse_mode="Markdown",
            reply_markup=InlineKeyboardMarkup(inline_keyboard=kb)
        )

    @dp.callback_query(F.data.startswith("order:"))
    async def order_detail(call: types.CallbackQuery):
        try:
            oid = int(call.data.split(":")[1])
        except (ValueError, IndexError):
            await call.answer("Xato")
            return
        order = await api_get(f"/public/order-detail/{oid}")
        if not order:
            await call.answer("Topilmadi", show_alert=True)
            return
        lines = [
            f"📋 *Buyurtma #{str(oid).zfill(4)}*",
            f"━━━━━━━━━━━━━━━━━━",
            f"Holat: {fmt_status(order.get('status', ''))}",
        ]
        if order.get("note"):
            lines.append(f"💬 {order['note']}")
        items = order.get("items") or []
        if items:
            lines.append("")
            for it in items:
                lines.append(
                    f"• {it.get('product_name', '')} × {int(it.get('quantity', 0))} = "
                    f"{int(it.get('total_price', 0)):,} so'm"
                )
            lines.append(f"━━━━━━━━━━━━━━━━━━")
            lines.append(f"💰 *Jami: {int(order.get('total', 0)):,} so'm*")
        else:
            lines.append("")
            lines.append("_(Mahsulotsiz — joy band qilingan)_")
        try:
            await call.message.edit_text("\n".join(lines), parse_mode="Markdown")
        except Exception:
            await call.message.answer("\n".join(lines), parse_mode="Markdown")
        await call.answer()

    # ════════════════════════════════════════════
    # 📖 MENYU
    # ════════════════════════════════════════════
    @dp.message(F.text == "📖 Menyu")
    async def show_menu(msg: types.Message):
        cats = await api_get(f"/public/categories?tenant_id={tenant_id}") or []
        if not cats:
            await msg.answer("😔 Menyu sozlanmagan.", reply_markup=main_kb())
            return
        text = [f"📖 *{tenant_name} — Menyu*\n"]
        for cat in cats:
            prods = await api_get(f"/public/products?tenant_id={tenant_id}&category_id={cat['id']}") or []
            if prods:
                text.append(f"\n*{cat['name'].strip()}:*")
                for p in prods:
                    text.append(f"• {p['name'].strip()} — {int(p['price']):,} so'm")
        full = "\n".join(text)
        if len(full) > 4000:
            full = full[:3990] + "..."
        await msg.answer(full, parse_mode="Markdown", reply_markup=main_kb())

    # ════════════════════════════════════════════
    # ADMIN: QABUL/RAD
    # ════════════════════════════════════════════
    @dp.callback_query(F.data.startswith("accept_"))
    async def admin_accept(call: types.CallbackQuery):
        try:
            order_id = int(call.data.split("_")[1])
        except (ValueError, IndexError):
            await call.answer("Xato")
            return
        result = await api_post("/public/update-order-status", {
            "order_id": order_id, "status": "confirmed", "tenant_id": tenant_id
        })
        if result:
            try:
                await call.message.edit_text(
                    (call.message.text or "") + f"\n\n✅ *Qabul qilindi*",
                    parse_mode="Markdown", reply_markup=None
                )
            except Exception:
                pass
            await call.answer("✅ Qabul qilindi")
        else:
            await call.answer("Xato", show_alert=True)

    @dp.callback_query(F.data.startswith("reject_"))
    async def admin_reject(call: types.CallbackQuery):
        try:
            order_id = int(call.data.split("_")[1])
        except (ValueError, IndexError):
            await call.answer("Xato")
            return
        result = await api_post("/public/update-order-status", {
            "order_id": order_id, "status": "cancelled", "tenant_id": tenant_id
        })
        if result:
            try:
                await call.message.edit_text(
                    (call.message.text or "") + f"\n\n❌ *Rad etildi*",
                    parse_mode="Markdown", reply_markup=None
                )
            except Exception:
                pass
            await call.answer("❌ Rad etildi")
        else:
            await call.answer("Xato", show_alert=True)

    # ════════════════════════════════════════════
    # NOMA'LUM
    # ════════════════════════════════════════════
    @dp.message()
    async def unknown(msg: types.Message, state: FSMContext):
        if await state.get_state() is None:
            await msg.answer("👇 Tugmalardan foydalaning:", reply_markup=main_kb())

    return dp


# ══════════════════════════════════════════════
# BOT MANAGER
# ══════════════════════════════════════════════
async def start_bot_polling(bot_token: str, tenant_id: int, tenant_name: str, admin_chat_id: str = ""):
    if bot_token in active_bots:
        active_bots[bot_token]["admin_chat_id"] = admin_chat_id
        return True
    try:
        bot = Bot(token=bot_token)
        await bot.delete_webhook(drop_pending_updates=True)
        dp = create_dispatcher(tenant_id, tenant_name, admin_chat_id)

        async def polling_task():
            try:
                logger.info(f"✅ Polling: {tenant_name}")
                await dp.start_polling(bot, allowed_updates=["message", "callback_query"])
            except Exception as e:
                logger.error(f"Polling xato {tenant_name}: {e}")
            finally:
                if bot_token in active_bots:
                    del active_bots[bot_token]

        task = asyncio.create_task(polling_task())
        active_bots[bot_token] = {
            "bot": bot, "dp": dp,
            "tenant_id": tenant_id,
            "tenant_name": tenant_name,
            "admin_chat_id": admin_chat_id,
            "task": task
        }
        logger.info(f"✅ Bot ishga tushdi: {tenant_name} (tenant_id={tenant_id})")
        return True
    except Exception as e:
        logger.error(f"Bot ishga tushirish xato: {e}")
        return False


async def stop_bot(bot_token: str):
    if bot_token not in active_bots:
        return False
    try:
        entry = active_bots[bot_token]
        entry["task"].cancel()
        await entry["bot"].session.close()
        del active_bots[bot_token]
        logger.info(f"Bot to'xtatildi")
        return True
    except Exception as e:
        logger.error(f"Bot to'xtatish xato: {e}")
        return False


async def load_bots():
    configs = await api_get("/public/bot-configs")
    if not configs:
        logger.info("Hech qanday aktiv bot topilmadi.")
        return
    for c in configs:
        if c.get("is_active") and c.get("bot_token"):
            await start_bot_polling(
                c["bot_token"],
                c["tenant_id"],
                c.get("tenant_name", "Restoran"),
                c.get("admin_chat_id", "")
            )
    logger.info(f"✅ {len(active_bots)} ta bot yuklandi.")


# ══════════════════════════════════════════════
# HTTP API (internal)
# ══════════════════════════════════════════════
async def handle_register(request: web.Request) -> web.Response:
    try:
        data = await request.json()
        token = data.get("bot_token")
        tenant_id = data.get("tenant_id")
        tenant_name = data.get("tenant_name", "Restoran")
        admin_chat_id = data.get("admin_chat_id", "")
        if not token or not tenant_id:
            return web.json_response({"error": "bot_token va tenant_id kerak"}, status=400)
        ok = await start_bot_polling(token, tenant_id, tenant_name, admin_chat_id)
        return web.json_response({"status": "ok" if ok else "error"})
    except Exception as e:
        return web.json_response({"error": str(e)}, status=500)


async def handle_unregister(request: web.Request) -> web.Response:
    try:
        data = await request.json()
        ok = await stop_bot(data.get("bot_token"))
        return web.json_response({"status": "ok" if ok else "not_found"})
    except Exception as e:
        return web.json_response({"error": str(e)}, status=500)


async def handle_notify(request: web.Request) -> web.Response:
    try:
        data = await request.json()
        token = data.get("bot_token")
        chat_id = data.get("chat_id")
        message = data.get("message", "")
        if token not in active_bots:
            return web.json_response({"error": "Bot topilmadi"}, status=404)
        bot = active_bots[token]["bot"]
        await bot.send_message(chat_id=int(chat_id), text=message, parse_mode="Markdown")
        return web.json_response({"status": "sent"})
    except Exception as e:
        return web.json_response({"error": str(e)}, status=500)


async def handle_receipt(request: web.Request) -> web.Response:
    try:
        data = await request.json()
        token = data.get("bot_token")
        chat_id = data.get("chat_id")
        if token not in active_bots:
            return web.json_response({"error": "Bot topilmadi"}, status=404)
        bot = active_bots[token]["bot"]
        receipt = fmt_receipt(data.get("order", {}), data.get("tenant_name", "Restoran"))
        await bot.send_message(chat_id=int(chat_id), text=receipt, parse_mode="Markdown")
        return web.json_response({"status": "sent"})
    except Exception as e:
        return web.json_response({"error": str(e)}, status=500)


async def handle_health(request: web.Request) -> web.Response:
    return web.json_response({
        "status": "healthy",
        "active_bots": len(active_bots),
        "bots": [{"tenant_id": v["tenant_id"], "tenant_name": v["tenant_name"]} for v in active_bots.values()]
    })


async def on_startup(app):
    logger.info("🚀 CORTEX POS Bot Service ishga tushdi")
    await load_bots()


async def on_shutdown(app):
    for token in list(active_bots.keys()):
        await stop_bot(token)


def main():
    app = web.Application()
    app.router.add_post("/internal/register-bot", handle_register)
    app.router.add_post("/internal/unregister-bot", handle_unregister)
    app.router.add_post("/internal/notify", handle_notify)
    app.router.add_post("/internal/send-receipt", handle_receipt)
    app.router.add_get("/health", handle_health)
    app.router.add_get("/", handle_health)
    app.on_startup.append(on_startup)
    app.on_shutdown.append(on_shutdown)
    port = int(os.getenv("PORT", 8080))
    logger.info(f"🌐 Port: {port}")
    web.run_app(app, host="0.0.0.0", port=port)


if __name__ == "__main__":
    main()
