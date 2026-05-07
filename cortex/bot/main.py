"""
CORTEX POS — Telegram Bot (Multi-tenant Polling)
- Soddalashtirilgan UX
- Admin inline tasdiqlash/rad
- Admin qo'lda xabar yuborish
- Chek yuborish (to'lovdan keyin)
- Mahsulotlar narxi ko'rinib turadi
- Telefon + izoh
"""
import asyncio
import logging
import os
import aiohttp
from aiohttp import web
from dotenv import load_dotenv
from aiogram import Bot, Dispatcher, types, F
from aiogram.filters import CommandStart, Command
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

# { bot_token: { bot, dp, tenant_id, tenant_name, admin_chat_id, task } }
active_bots: dict = {}


# ── FSM ──
class OrderState(StatesGroup):
    choosing_type  = State()
    choosing_room  = State()
    choosing_cat   = State()
    choosing_prod  = State()
    viewing_cart   = State()
    entering_phone = State()
    entering_note  = State()
    confirming     = State()


# ── API ──
async def api_get(path: str):
    try:
        async with aiohttp.ClientSession() as s:
            async with s.get(f"{BACKEND_URL}{path}", timeout=aiohttp.ClientTimeout(total=10)) as r:
                if r.status == 200:
                    return await r.json()
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


# ── KEYBOARDS ──
def main_kb():
    return ReplyKeyboardMarkup(keyboard=[
        [KeyboardButton(text="📦 Buyurtma berish")],
        [KeyboardButton(text="📖 Menyu"), KeyboardButton(text="📋 Buyurtmalarim")],
    ], resize_keyboard=True)


def order_type_kb():
    return ReplyKeyboardMarkup(keyboard=[
        [KeyboardButton(text="🥡 Olib ketish")],
        [KeyboardButton(text="🪑 Joy buyurtma")],
        [KeyboardButton(text="◀️ Orqaga")],
    ], resize_keyboard=True)


def rooms_kb(rooms):
    free = [r for r in rooms if r["status"] == "free"]
    buttons = [[KeyboardButton(text=f"🪑 {r['name']} ({r['capacity']} kishi)")] for r in free]
    buttons.append([KeyboardButton(text="◀️ Orqaga")])
    return ReplyKeyboardMarkup(keyboard=buttons, resize_keyboard=True)


def cats_kb(cats, has_cart=False):
    buttons = [[KeyboardButton(text=f"📂 {c['name'].strip()}")] for c in cats]
    if has_cart:
        total_qty = 0
        buttons.insert(0, [KeyboardButton(text="🛒 Savatim")])
    buttons.append([KeyboardButton(text="◀️ Orqaga")])
    return ReplyKeyboardMarkup(keyboard=buttons, resize_keyboard=True)


def products_kb(products, cart: dict):
    buttons = []
    for p in products:
        pid = str(p["id"])
        qty = cart.get(pid, {}).get("qty", 0)
        name = p["name"].strip()
        price = int(p["price"])
        label = f"➕ {name} — {price:,} so'm"
        if qty > 0:
            label = f"✅ {name} ({qty} ta) — {price:,} so'm"
        row = [KeyboardButton(text=label)]
        if qty > 0:
            row.append(KeyboardButton(text=f"➖ {name}"))
        buttons.append(row)
    if cart:
        total = sum(v["price"] * v["qty"] for v in cart.values())
        buttons.append([KeyboardButton(text=f"🛒 Savatim — {int(total):,} so'm")])
    buttons.append([KeyboardButton(text="◀️ Kategoriyalar")])
    return ReplyKeyboardMarkup(keyboard=buttons, resize_keyboard=True)


def phone_kb():
    return ReplyKeyboardMarkup(keyboard=[
        [KeyboardButton(text="📱 Raqamimni yuborish", request_contact=True)],
        [KeyboardButton(text="◀️ Orqaga")],
    ], resize_keyboard=True)


def note_kb():
    return ReplyKeyboardMarkup(keyboard=[
        [KeyboardButton(text="⏭ Izohsiz davom etish")],
        [KeyboardButton(text="◀️ Orqaga")],
    ], resize_keyboard=True)


def confirm_kb():
    return ReplyKeyboardMarkup(keyboard=[
        [KeyboardButton(text="✅ Tasdiqlash")],
        [KeyboardButton(text="✏️ O'zgartirish"), KeyboardButton(text="❌ Bekor qilish")],
    ], resize_keyboard=True)


def admin_order_inline(order_id: int):
    return InlineKeyboardMarkup(inline_keyboard=[[
        InlineKeyboardButton(text="✅ Qabul", callback_data=f"accept_{order_id}"),
        InlineKeyboardButton(text="❌ Rad", callback_data=f"reject_{order_id}"),
    ]])


# ── FORMATTERS ──
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


def fmt_admin_notify(order_id, cart, order_type, room_name, phone, note, customer_name, tenant_name):
    type_text = f"🪑 Joy: {room_name}" if order_type == "dine_in" else "🥡 Olib ketish"
    _name = customer_name or "Noma'lum"
    lines = [
        f"🔔 *Yangi buyurtma!*",
        f"🏪 {tenant_name}",
        f"━━━━━━━━━━━━━━━━━━",
        f"📋 Buyurtma *#{str(order_id).zfill(4)}*",
        f"📍 {type_text}",
        f"👤 {_name}",
        f"📱 {phone}",
    ]
    if note:
        lines.append(f"💬 Izoh: {note}")
    lines.append("━━━━━━━━━━━━━━━━━━")
    total = 0
    for item in cart.values():
        lt = item["price"] * item["qty"]
        total += lt
        lines.append(f"• {item['name']} × {item['qty']} = {int(lt):,} so'm")
    lines.append(f"━━━━━━━━━━━━━━━━━━")
    lines.append(f"💰 *Jami: {int(total):,} so'm*")
    return "\n".join(lines)


def fmt_receipt(order_data, tenant_name):
    lines = [
        f"🧾 *{tenant_name}*",
        f"━━━━━━━━━━━━━━━━━━",
        f"📋 Buyurtma #{str(order_data.get('id', 0)).zfill(4)}",
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
    lines.append(f"💳 To'lov: {method}")
    lines.append("━━━━━━━━━━━━━━━━━━")
    lines.append("✅ *Xaridingiz uchun rahmat!*")
    return "\n".join(lines)


# ── DISPATCHER ──
def create_dispatcher(tenant_id: int, tenant_name: str, admin_chat_id: str) -> Dispatcher:
    dp = Dispatcher(storage=MemoryStorage())

    # ── /start ──
    @dp.message(CommandStart())
    async def start(msg: types.Message, state: FSMContext):
        await state.clear()
        await api_post("/public/bot-user", {
            "tenant_id": tenant_id,
            "chat_id": str(msg.from_user.id),
            "full_name": msg.from_user.full_name or "",
            "username": msg.from_user.username or ""
        })
        rooms = await api_get(f"/public/rooms?tenant_id={tenant_id}") or []
        free = len([r for r in rooms if r["status"] == "free"])
        await msg.answer(
            f"👋 Xush kelibsiz, *{msg.from_user.full_name or 'Mehmon'}*!\n\n"
            f"🏪 *{tenant_name}*\n"
            f"🪑 Bo'sh joylar: *{free} ta*\n\n"
            f"Buyurtma berish uchun quyidagi tugmani bosing 👇",
            reply_markup=main_kb(), parse_mode="Markdown"
        )

    # ── MENYU ──
    @dp.message(F.text == "📖 Menyu")
    async def show_menu(msg: types.Message):
        cats = await api_get(f"/public/categories?tenant_id={tenant_id}") or []
        if not cats:
            await msg.answer("😔 Menyu hali sozlanmagan.", reply_markup=main_kb())
            return
        text = [f"📖 *{tenant_name} — Menyu*\n"]
        for cat in cats:
            prods = await api_get(f"/public/products?tenant_id={tenant_id}&category_id={cat['id']}") or []
            if prods:
                text.append(f"\n*{cat['name'].strip()}:*")
                for p in prods:
                    unit_map = {"piece": "dona", "kg": "kg", "gram": "gr", "liter": "l", "ml": "ml", "portion": "porsiya"}
                    unit = unit_map.get(p.get("unit", ""), "")
                    text.append(f"• {p['name'].strip()} — {int(p['price']):,} so'm/{unit}")
        await msg.answer("\n".join(text), parse_mode="Markdown", reply_markup=main_kb())

    # ── BUYURTMALARIM ──
    @dp.message(F.text == "📋 Buyurtmalarim")
    async def my_orders(msg: types.Message):
        await msg.answer(
            "Buyurtma holatini ko'rish:\n/status <raqam>\n\nMasalan: /status 42",
            reply_markup=main_kb()
        )

    @dp.message(Command("status"))
    async def check_status(msg: types.Message):
        args = msg.text.split()
        if len(args) < 2:
            await msg.answer("Foydalanish: /status 42")
            return
        try:
            order_id = int(args[1])
        except ValueError:
            await msg.answer("❌ Raqam noto'g'ri.")
            return
        result = await api_get(f"/public/order-status/{order_id}")
        if not result:
            await msg.answer("❌ Buyurtma topilmadi.")
            return
        status_map = {
            "new": "🆕 Yangi — ko'rib chiqilmoqda",
            "confirmed": "✅ Tasdiqlandi",
            "preparing": "👨‍🍳 Tayyorlanmoqda",
            "ready": "🔔 Tayyor!",
            "paid": "💚 To'landi",
            "cancelled": "❌ Bekor qilindi"
        }
        st = status_map.get(result.get("status", ""), result.get("status", ""))
        await msg.answer(
            f"📋 *Buyurtma #{str(order_id).zfill(4)}*\n\n"
            f"Holat: {st}\n"
            f"Summa: *{int(result.get('total', 0)):,} so'm*",
            parse_mode="Markdown"
        )

    # ── BUYURTMA BOSHLASH ──
    @dp.message(F.text == "📦 Buyurtma berish")
    async def order_start(msg: types.Message, state: FSMContext):
        await state.clear()
        await state.set_state(OrderState.choosing_type)
        await msg.answer("Buyurtma turini tanlang:", reply_markup=order_type_kb())

    # ── ORQAGA ──
    @dp.message(F.text == "◀️ Orqaga")
    async def go_back(msg: types.Message, state: FSMContext):
        cur = await state.get_state()
        if cur == OrderState.choosing_type:
            await state.clear()
            await msg.answer("Asosiy menyu:", reply_markup=main_kb())
        elif cur == OrderState.choosing_room:
            await state.set_state(OrderState.choosing_type)
            await msg.answer("Buyurtma turini tanlang:", reply_markup=order_type_kb())
        elif cur == OrderState.choosing_cat:
            data = await state.get_data()
            if data.get("order_type") == "dine_in":
                rooms = await api_get(f"/public/rooms?tenant_id={tenant_id}") or []
                await state.set_state(OrderState.choosing_room)
                await msg.answer("Xona tanlang:", reply_markup=rooms_kb(rooms))
            else:
                await state.set_state(OrderState.choosing_type)
                await msg.answer("Buyurtma turini tanlang:", reply_markup=order_type_kb())
        elif cur == OrderState.choosing_prod:
            data = await state.get_data()
            cats = await api_get(f"/public/categories?tenant_id={tenant_id}") or []
            cart = data.get("cart", {})
            await state.set_state(OrderState.choosing_cat)
            await msg.answer("Kategoriya tanlang:", reply_markup=cats_kb(cats, bool(cart)))
        elif cur == OrderState.viewing_cart:
            data = await state.get_data()
            cats = await api_get(f"/public/categories?tenant_id={tenant_id}") or []
            cart = data.get("cart", {})
            await state.set_state(OrderState.choosing_cat)
            await msg.answer("Kategoriya tanlang:", reply_markup=cats_kb(cats, bool(cart)))
        elif cur == OrderState.entering_phone:
            data = await state.get_data()
            cart = data.get("cart", {})
            await state.set_state(OrderState.viewing_cart)
            await msg.answer(fmt_cart(cart), parse_mode="Markdown",
                             reply_markup=_cart_action_kb())
        elif cur == OrderState.entering_note:
            await state.set_state(OrderState.entering_phone)
            await msg.answer("📱 Telefon raqamingizni yuboring:", reply_markup=phone_kb())
        elif cur == OrderState.confirming:
            await state.set_state(OrderState.entering_note)
            await msg.answer("💬 Izoh qoldiring yoki o'tkazib yuboring:", reply_markup=note_kb())
        else:
            await state.clear()
            await msg.answer("Asosiy menyu:", reply_markup=main_kb())

    def _cart_action_kb():
        return ReplyKeyboardMarkup(keyboard=[
            [KeyboardButton(text="✅ Buyurtmani rasmiylashtirish")],
            [KeyboardButton(text="➕ Mahsulot qo'shish"), KeyboardButton(text="🗑 Tozalash")],
            [KeyboardButton(text="◀️ Orqaga")],
        ], resize_keyboard=True)

    # ── OLIB KETISH ──
    @dp.message(F.text == "🥡 Olib ketish", OrderState.choosing_type)
    async def takeaway(msg: types.Message, state: FSMContext):
        cats = await api_get(f"/public/categories?tenant_id={tenant_id}") or []
        if not cats:
            await msg.answer("😔 Menyu sozlanmagan.", reply_markup=main_kb())
            await state.clear()
            return
        await state.update_data(order_type="takeaway", room_id=None, room_name="", cart={})
        await state.set_state(OrderState.choosing_cat)
        await msg.answer("📂 Kategoriya tanlang:", reply_markup=cats_kb(cats))

    # ── JOY BUYURTMA ──
    @dp.message(F.text == "🪑 Joy buyurtma", OrderState.choosing_type)
    async def dine_in(msg: types.Message, state: FSMContext):
        rooms = await api_get(f"/public/rooms?tenant_id={tenant_id}") or []
        free = [r for r in rooms if r["status"] == "free"]
        if not free:
            await msg.answer("😔 Hozir bo'sh joy yo'q.", reply_markup=order_type_kb())
            return
        await state.update_data(order_type="dine_in", rooms=rooms, cart={})
        await state.set_state(OrderState.choosing_room)
        await msg.answer(
            f"🪑 Bo'sh joylar: *{len(free)} ta*\nXona tanlang:",
            reply_markup=rooms_kb(rooms), parse_mode="Markdown"
        )

    # ── XONA TANLASH ──
    @dp.message(F.text.startswith("🪑 "), OrderState.choosing_room)
    async def room_selected(msg: types.Message, state: FSMContext):
        data = await state.get_data()
        room_name = msg.text.replace("🪑 ", "").split(" (")[0].strip()
        room = next((r for r in data.get("rooms", []) if r["name"] == room_name), None)
        if not room:
            await msg.answer("❌ Xona topilmadi.")
            return
        cats = await api_get(f"/public/categories?tenant_id={tenant_id}") or []
        await state.update_data(room_id=room["id"], room_name=room["name"], cart={})
        await state.set_state(OrderState.choosing_cat)
        await msg.answer(
            f"✅ *{room['name']}* tanlandi.\n📂 Kategoriya tanlang:",
            reply_markup=cats_kb(cats), parse_mode="Markdown"
        )

    # ── KATEGORIYA TANLASH ──
    @dp.message(F.text.startswith("📂 "))
    async def cat_selected(msg: types.Message, state: FSMContext):
        cur = await state.get_state()
        if cur not in [OrderState.choosing_cat, OrderState.choosing_prod]:
            return
        cat_name = msg.text.replace("📂 ", "").strip()
        cats = await api_get(f"/public/categories?tenant_id={tenant_id}") or []
        cat = next((c for c in cats if c["name"].strip() == cat_name), None)
        if not cat:
            await msg.answer("❌ Kategoriya topilmadi. Qaytadan urinib ko'ring.")
            return
        prods = await api_get(f"/public/products?tenant_id={tenant_id}&category_id={cat['id']}") or []
        if not prods:
            data = await state.get_data()
            cart = data.get("cart", {})
            await msg.answer(
                f"😔 *{cat_name}* kategoriyasida mahsulot yo'q.",
                parse_mode="Markdown",
                reply_markup=cats_kb(cats, bool(cart))
            )
            return
        data = await state.get_data()
        cart = data.get("cart", {})
        await state.update_data(products=prods, cur_cat=cat_name)
        await state.set_state(OrderState.choosing_prod)
        await msg.answer(
            f"📂 *{cat_name}*\nMahsulot tanlang:",
            reply_markup=products_kb(prods, cart), parse_mode="Markdown"
        )

    # ── MAHSULOT QO'SHISH ──
    @dp.message(F.text.startswith("➕ "))
    async def prod_add(msg: types.Message, state: FSMContext):
        cur = await state.get_state()
        if cur not in [OrderState.choosing_prod]:
            return
        data = await state.get_data()
        raw = msg.text.replace("➕ ", "").replace("✅ ", "")
        pname = raw.split(" — ")[0].split(" (")[0].strip()
        prod = next((p for p in data.get("products", []) if p["name"].strip() == pname), None)
        if not prod:
            return
        cart = data.get("cart", {})
        pid = str(prod["id"])
        if pid in cart:
            cart[pid]["qty"] += 1
        else:
            cart[pid] = {"name": prod["name"].strip(), "price": prod["price"], "qty": 1}
        await state.update_data(cart=cart)
        total = sum(v["price"] * v["qty"] for v in cart.values())
        await msg.answer(
            f"✅ *{prod['name'].strip()}* qo'shildi!\n"
            f"🛒 {len(cart)} xil mahsulot | 💰 *{int(total):,}* so'm",
            parse_mode="Markdown",
            reply_markup=products_kb(data.get("products", []), cart)
        )

    # ── MAHSULOT OLIB TASHLASH ──
    @dp.message(F.text.startswith("➖ "))
    async def prod_remove(msg: types.Message, state: FSMContext):
        cur = await state.get_state()
        if cur != OrderState.choosing_prod:
            return
        data = await state.get_data()
        pname = msg.text.replace("➖ ", "").strip()
        cart = data.get("cart", {})
        prod = next((p for p in data.get("products", []) if p["name"].strip() == pname), None)
        if not prod:
            return
        pid = str(prod["id"])
        if pid in cart:
            cart[pid]["qty"] -= 1
            if cart[pid]["qty"] <= 0:
                del cart[pid]
        await state.update_data(cart=cart)
        await msg.answer(
            f"➖ *{pname}* kamaytirildi.",
            parse_mode="Markdown",
            reply_markup=products_kb(data.get("products", []), cart)
        )

    # ── KATEGORIYALARGA ORQAGA ──
    @dp.message(F.text == "◀️ Kategoriyalar")
    async def back_to_cats(msg: types.Message, state: FSMContext):
        data = await state.get_data()
        cats = await api_get(f"/public/categories?tenant_id={tenant_id}") or []
        cart = data.get("cart", {})
        await state.set_state(OrderState.choosing_cat)
        await msg.answer("📂 Kategoriya tanlang:", reply_markup=cats_kb(cats, bool(cart)))

    # ── SAVATNI KO'RISH ──
    @dp.message(F.text.startswith("🛒 Savatim"))
    async def view_cart(msg: types.Message, state: FSMContext):
        data = await state.get_data()
        cart = data.get("cart", {})
        await state.set_state(OrderState.viewing_cart)
        await msg.answer(fmt_cart(cart), parse_mode="Markdown", reply_markup=_cart_action_kb())

    # ── MAHSULOT QO'SHISH (savat ekranidan) ──
    @dp.message(F.text == "➕ Mahsulot qo'shish", OrderState.viewing_cart)
    async def add_more(msg: types.Message, state: FSMContext):
        data = await state.get_data()
        cats = await api_get(f"/public/categories?tenant_id={tenant_id}") or []
        cart = data.get("cart", {})
        await state.set_state(OrderState.choosing_cat)
        await msg.answer("📂 Kategoriya tanlang:", reply_markup=cats_kb(cats, bool(cart)))

    # ── SAVATNI TOZALASH ──
    @dp.message(F.text == "🗑 Tozalash", OrderState.viewing_cart)
    async def clear_cart(msg: types.Message, state: FSMContext):
        await state.update_data(cart={})
        cats = await api_get(f"/public/categories?tenant_id={tenant_id}") or []
        await state.set_state(OrderState.choosing_cat)
        await msg.answer("🗑 Savat tozalandi. Kategoriya tanlang:", reply_markup=cats_kb(cats))

    # ── BUYURTMANI RASMIYLASH ──
    @dp.message(F.text == "✅ Buyurtmani rasmiylashtirish", OrderState.viewing_cart)
    async def go_phone(msg: types.Message, state: FSMContext):
        data = await state.get_data()
        if not data.get("cart"):
            await msg.answer("❌ Savat bo'sh! Avval mahsulot tanlang.")
            return
        await state.set_state(OrderState.entering_phone)
        await msg.answer("📱 Telefon raqamingizni yuboring:", reply_markup=phone_kb())

    # ── TELEFON (KONTAKT) ──
    @dp.message(F.contact, OrderState.entering_phone)
    async def phone_contact(msg: types.Message, state: FSMContext):
        phone = msg.contact.phone_number
        if not phone.startswith("+"):
            phone = "+" + phone
        await state.update_data(phone=phone)
        await state.set_state(OrderState.entering_note)
        await msg.answer("💬 Izoh qoldiring yoki o'tkazib yuboring:", reply_markup=note_kb())

    # ── TELEFON (MATN) ──
    @dp.message(OrderState.entering_phone)
    async def phone_text(msg: types.Message, state: FSMContext):
        if msg.text == "◀️ Orqaga":
            data = await state.get_data()
            cart = data.get("cart", {})
            await state.set_state(OrderState.viewing_cart)
            await msg.answer(fmt_cart(cart), parse_mode="Markdown", reply_markup=_cart_action_kb())
            return
        phone = msg.text.strip().replace(" ", "")
        if len(phone) < 9:
            await msg.answer("❌ Noto'g'ri raqam. Qaytadan kiriting:")
            return
        await state.update_data(phone=phone)
        await state.set_state(OrderState.entering_note)
        await msg.answer("💬 Izoh qoldiring yoki o'tkazib yuboring:", reply_markup=note_kb())

    # ── IZOH ──
    @dp.message(F.text == "⏭ Izohsiz davom etish", OrderState.entering_note)
    async def skip_note(msg: types.Message, state: FSMContext):
        await state.update_data(note="")
        await _show_confirm(msg, state)

    @dp.message(OrderState.entering_note)
    async def enter_note(msg: types.Message, state: FSMContext):
        if msg.text == "◀️ Orqaga":
            await state.set_state(OrderState.entering_phone)
            await msg.answer("📱 Telefon raqamingizni yuboring:", reply_markup=phone_kb())
            return
        await state.update_data(note=msg.text.strip())
        await _show_confirm(msg, state)

    async def _show_confirm(msg, state):
        data = await state.get_data()
        cart = data.get("cart", {})
        order_type = data.get("order_type", "takeaway")
        room_name = data.get("room_name", "")
        phone = data.get("phone", "")
        note = data.get("note", "")
        type_text = f"🪑 Joy: {room_name}" if order_type == "dine_in" else "🥡 Olib ketish"
        note_text = f"\n💬 Izoh: {note}" if note else ""
        await state.set_state(OrderState.confirming)
        await msg.answer(
            f"📋 *Buyurtmangizni tasdiqlang:*\n\n"
            f"📍 {type_text}\n"
            f"📱 {phone}{note_text}\n\n"
            f"{fmt_cart(cart)}\n\n"
            f"Tasdiqlaysizmi?",
            parse_mode="Markdown", reply_markup=confirm_kb()
        )

    # ── TASDIQLASH ──
    @dp.message(F.text == "✅ Tasdiqlash", OrderState.confirming)
    async def confirm_order(msg: types.Message, state: FSMContext):
        data = await state.get_data()
        await msg.answer("⏳ Buyurtma yuborilmoqda...", reply_markup=ReplyKeyboardRemove())
        result = await api_post("/public/bot-order", {
            "tenant_id": tenant_id,
            "order_type": data.get("order_type", "bot"),
            "room_id": data.get("room_id"),
            "customer_name": msg.from_user.full_name or "",
            "customer_phone": data.get("phone", ""),
            "note": data.get("note", ""),
            "cart": {pid: v["qty"] for pid, v in data.get("cart", {}).items()},
            "bot_chat_id": msg.from_user.id,
            "username": msg.from_user.username or ""
        })
        if result:
            order_id = result.get("id", 0)
            total = result.get("total", 0)
            type_text = f"🪑 {data.get('room_name', '')}" if data.get("order_type") == "dine_in" else "🥡 Olib ketish"
            await msg.answer(
                f"✅ *Buyurtma qabul qilindi!*\n\n"
                f"📋 Buyurtma *#{str(order_id).zfill(4)}*\n"
                f"📍 {type_text}\n"
                f"💰 *{int(total):,} so'm*\n\n"
                f"⏳ Admin ko'rib chiqmoqda...\n"
                f"Holat: /status {order_id}",
                parse_mode="Markdown", reply_markup=main_kb()
            )
            # Admin ga xabar
            if admin_chat_id:
                from aiogram import Bot as AiogramBot
                bot_entry = active_bots.get(
                    next((t for t in active_bots if active_bots[t]["tenant_id"] == tenant_id), None)
                )
                if bot_entry:
                    try:
                        await bot_entry["bot"].send_message(
                            chat_id=int(admin_chat_id),
                            text=fmt_admin_notify(
                                order_id,
                                data.get("cart", {}),
                                data.get("order_type", ""),
                                data.get("room_name", ""),
                                data.get("phone", ""),
                                data.get("note", ""),
                                msg.from_user.full_name or "",
                                tenant_name
                            ),
                            reply_markup=admin_order_inline(order_id),
                            parse_mode="Markdown"
                        )
                    except Exception as e:
                        logger.error(f"Admin ga xabar yuborishda xato: {e}")
        else:
            await msg.answer("❌ Xato yuz berdi. Qaytadan urinib ko'ring.", reply_markup=main_kb())
        await state.clear()

    # ── O'ZGARTIRISH ──
    @dp.message(F.text == "✏️ O'zgartirish", OrderState.confirming)
    async def edit_order(msg: types.Message, state: FSMContext):
        data = await state.get_data()
        cart = data.get("cart", {})
        await state.set_state(OrderState.viewing_cart)
        await msg.answer(fmt_cart(cart), parse_mode="Markdown", reply_markup=_cart_action_kb())

    # ── BEKOR QILISH ──
    @dp.message(F.text == "❌ Bekor qilish", OrderState.confirming)
    async def cancel_order(msg: types.Message, state: FSMContext):
        await state.clear()
        await msg.answer("❌ Buyurtma bekor qilindi.", reply_markup=main_kb())

    # ── ADMIN INLINE: QABUL / RAD ──
    @dp.callback_query(F.data.startswith("accept_"))
    async def admin_accept(call: types.CallbackQuery):
        order_id = int(call.data.split("_")[1])
        result = await api_post(f"/public/update-order-status", {
            "order_id": order_id,
            "status": "confirmed",
            "tenant_id": tenant_id
        })
        if result:
            await call.message.edit_text(
                call.message.text + f"\n\n✅ *Qabul qilindi* — {call.from_user.full_name}",
                parse_mode="Markdown", reply_markup=None
            )
            await call.answer("✅ Buyurtma qabul qilindi!")
        else:
            await call.answer("❌ Xato yuz berdi!")

    @dp.callback_query(F.data.startswith("reject_"))
    async def admin_reject(call: types.CallbackQuery):
        order_id = int(call.data.split("_")[1])
        result = await api_post(f"/public/update-order-status", {
            "order_id": order_id,
            "status": "cancelled",
            "tenant_id": tenant_id
        })
        if result:
            await call.message.edit_text(
                call.message.text + f"\n\n❌ *Rad etildi* — {call.from_user.full_name}",
                parse_mode="Markdown", reply_markup=None
            )
            await call.answer("❌ Buyurtma rad etildi!")
        else:
            await call.answer("❌ Xato yuz berdi!")

    # ── NOMA'LUM XABAR ──
    @dp.message()
    async def unknown(msg: types.Message, state: FSMContext):
        if await state.get_state() is None:
            await msg.answer("Buyurtma berish uchun tugmani bosing 👇", reply_markup=main_kb())

    return dp


# ══════════════════════════════════════════════
# BOT MANAGER
# ══════════════════════════════════════════════

async def start_bot_polling(bot_token: str, tenant_id: int, tenant_name: str, admin_chat_id: str = ""):
    if bot_token in active_bots:
        # admin_chat_id yangilash
        active_bots[bot_token]["admin_chat_id"] = admin_chat_id
        return True
    try:
        bot = Bot(token=bot_token)
        await bot.delete_webhook(drop_pending_updates=True)
        dp = create_dispatcher(tenant_id, tenant_name, admin_chat_id)

        async def polling_task():
            try:
                logger.info(f"✅ Bot polling: {tenant_name}")
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
        logger.info(f"✅ Bot ro'yxatdan o'tdi: {tenant_name} (tenant_id={tenant_id})")
        return True
    except Exception as e:
        logger.error(f"Bot ishga tushirishda xato: {e}")
        return False


async def stop_bot(bot_token: str):
    if bot_token not in active_bots:
        return False
    try:
        entry = active_bots[bot_token]
        entry["task"].cancel()
        await entry["bot"].session.close()
        del active_bots[bot_token]
        logger.info(f"Bot to'xtatildi: {bot_token[:10]}...")
        return True
    except Exception as e:
        logger.error(f"Bot to'xtatishda xato: {e}")
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
