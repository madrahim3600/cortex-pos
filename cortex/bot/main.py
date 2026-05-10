"""
CORTEX POS — Telegram Bot (qayta yozilgan, sodda)
─────────────────────────────────────────────────
- Barcha buyurtmalar order_type="bot" (online) sifatida saqlanadi
- Mahsulot tanlash inline tugmalar orqali (ishonchli, ID-based)
- Buyurtmalarim — tugmalar orqali oxirgi 5 buyurtmani ko'rsatadi
- Chek to'lovdan keyin avtomatik yetkaziladi (backend tomonidan)
"""
import asyncio
import logging
import os
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

# { bot_token: { bot, dp, tenant_id, tenant_name, admin_chat_id, task } }
active_bots: dict = {}


# ══════════════════════════════════════════════
# FSM
# ══════════════════════════════════════════════
class OrderState(StatesGroup):
    browsing      = State()  # menyu ko'rib chiqish (kategoriya/mahsulot)
    entering_phone = State()
    entering_note  = State()
    confirming     = State()


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
        [KeyboardButton(text="🍽 Buyurtma berish")],
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
# INLINE KEYBOARDS — menyu va savat tugmalar orqali
# ══════════════════════════════════════════════
def categories_inline(cats: list, cart: dict):
    """Kategoriyalar ro'yxati + savat (agar bor bo'lsa)"""
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


def products_inline(products: list, cart: dict, cat_id: int):
    """Mahsulotlar — har biri uchun nomi + qty + +/- tugmalari"""
    kb = []
    for p in products:
        pid = str(p["id"])
        qty = cart.get(pid, {}).get("qty", 0)
        name = p["name"].strip()
        price = int(p["price"])
        # Asosiy qator: nom va narx
        if qty == 0:
            kb.append([InlineKeyboardButton(
                text=f"➕ {name} — {price:,} so'm",
                callback_data=f"add:{p['id']}"
            )])
        else:
            kb.append([
                InlineKeyboardButton(text=f"➖", callback_data=f"sub:{p['id']}"),
                InlineKeyboardButton(text=f"{name} ({qty} ta)", callback_data="noop"),
                InlineKeyboardButton(text=f"➕", callback_data=f"add:{p['id']}"),
            ])
    # Pastdagi navigatsiya
    nav = []
    if cart:
        total = sum(v["price"] * v["qty"] for v in cart.values())
        nav.append(InlineKeyboardButton(
            text=f"🛒 Savat — {int(total):,} so'm",
            callback_data="cart:view"
        ))
    nav.append(InlineKeyboardButton(text="◀️ Kategoriyalar", callback_data="cat:back"))
    kb.append(nav)
    return InlineKeyboardMarkup(inline_keyboard=kb)


def cart_inline(cart: dict):
    """Savat ichida har bir mahsulot uchun +/- va o'chirish"""
    kb = []
    for pid, item in cart.items():
        kb.append([InlineKeyboardButton(
            text=f"{item['name']} ({item['qty']} ta) — {int(item['price'] * item['qty']):,} so'm",
            callback_data="noop"
        )])
        kb.append([
            InlineKeyboardButton(text="➖", callback_data=f"sub:{pid}"),
            InlineKeyboardButton(text="🗑 O'chirish", callback_data=f"del:{pid}"),
            InlineKeyboardButton(text="➕", callback_data=f"add:{pid}"),
        ])
    kb.append([InlineKeyboardButton(text="✅ Buyurtmani rasmiylashtirish", callback_data="cart:checkout")])
    kb.append([
        InlineKeyboardButton(text="◀️ Menyuga qaytish", callback_data="cat:back"),
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
        return "🛒 Savatingiz bo'sh"
    lines = ["🛒 *Savatingiz:*\n"]
    total = 0
    for item in cart.values():
        lt = item["price"] * item["qty"]
        total += lt
        lines.append(f"• {item['name']} × {item['qty']} = *{int(lt):,}* so'm")
    lines.append(f"\n💰 *Jami: {int(total):,} so'm*")
    return "\n".join(lines)


def fmt_admin_notify(order_id, cart, phone, note, customer_name, tenant_name):
    lines = [
        f"🔔 *Yangi buyurtma!*",
        f"🏪 {tenant_name}",
        f"━━━━━━━━━━━━━━━━━━",
        f"📋 Buyurtma *#{str(order_id).zfill(4)}*",
        f"🌐 Online (bot orqali)",
        f"👤 {customer_name or 'Mehmon'}",
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
    if method:
        lines.append(f"💳 To'lov: {method}")
    lines.append("━━━━━━━━━━━━━━━━━━")
    lines.append("✅ *Xaridingiz uchun rahmat!*")
    return "\n".join(lines)


def fmt_status(status: str) -> str:
    m = {
        "new": "🆕 Yangi — ko'rib chiqilmoqda",
        "confirmed": "✅ Tasdiqlandi",
        "preparing": "👨‍🍳 Tayyorlanmoqda",
        "ready": "🔔 Tayyor!",
        "paid": "💚 To'landi",
        "closed": "✔️ Yakunlangan",
        "cancelled": "❌ Bekor qilindi"
    }
    return m.get(status, status)


# ══════════════════════════════════════════════
# DISPATCHER
# ══════════════════════════════════════════════
def create_dispatcher(tenant_id: int, tenant_name: str, admin_chat_id: str) -> Dispatcher:
    dp = Dispatcher(storage=MemoryStorage())

    # ─────── /start ───────
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
            f"Quyidagi tugmalardan foydalaning 👇",
            reply_markup=main_kb(), parse_mode="Markdown"
        )

    # ─────── BUYURTMA BERISH ───────
    @dp.message(F.text == "🍽 Buyurtma berish")
    async def order_start(msg: types.Message, state: FSMContext):
        cats = await api_get(f"/public/categories?tenant_id={tenant_id}") or []
        if not cats:
            await msg.answer("😔 Menyu hali sozlanmagan. Keyinroq qaytib keling.", reply_markup=main_kb())
            return
        await state.clear()
        await state.update_data(cart={}, cats=cats)
        await state.set_state(OrderState.browsing)
        await msg.answer(
            "📂 *Kategoriyani tanlang:*",
            parse_mode="Markdown",
            reply_markup=categories_inline(cats, {})
        )

    # ─────── KATEGORIYA TANLASH (inline) ───────
    @dp.callback_query(F.data.startswith("cat:"), OrderState.browsing)
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
            await call.answer("Noto'g'ri kategoriya")
            return

        prods = await api_get(f"/public/products?tenant_id={tenant_id}&category_id={cat_id}") or []
        if not prods:
            await call.answer("😔 Bu kategoriyada mahsulot yo'q", show_alert=True)
            return

        # Mahsulotlarni state'ga saqlaymiz (keyin ID orqali topish uchun)
        prod_map = {str(p["id"]): {"name": p["name"].strip(), "price": p["price"]} for p in prods}
        all_products = data.get("all_products", {})
        all_products.update(prod_map)
        await state.update_data(all_products=all_products, cur_cat=cat_id)

        await call.message.edit_text(
            f"🍽 *Mahsulotlar:*\n\n_➕ tugmasi orqali qo'shing_",
            parse_mode="Markdown",
            reply_markup=products_inline(prods, cart, cat_id)
        )
        await call.answer()

    # ─────── MAHSULOT QO'SHISH ───────
    @dp.callback_query(F.data.startswith("add:"), OrderState.browsing)
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

        # Joriy kategoriyani qayta ko'rsatish
        cur_cat = data.get("cur_cat")
        if cur_cat:
            prods = await api_get(f"/public/products?tenant_id={tenant_id}&category_id={cur_cat}") or []
            try:
                await call.message.edit_reply_markup(reply_markup=products_inline(prods, cart, cur_cat))
            except Exception:
                pass

        await call.answer(f"✅ {all_products[pid]['name']} qo'shildi")

    # ─────── MAHSULOT KAMAYTIRISH ───────
    @dp.callback_query(F.data.startswith("sub:"), OrderState.browsing)
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

        # Qayerda turganini tekshirish — savatdami yoki mahsulotlarda
        # Ko'rsatilayotgan xabarni yangilash
        cur_cat = data.get("cur_cat")
        # Agar savatda turibmi (call.message tarkibiga qarash kerak)
        msg_text = call.message.text or ""
        try:
            if "Savat" in msg_text and not cart:
                # Savat bo'sh bo'lib qoldi → kategoriyalarga qaytaramiz
                cats = data.get("cats") or await api_get(f"/public/categories?tenant_id={tenant_id}") or []
                await call.message.edit_text(
                    "🛒 Savat bo'sh bo'lib qoldi.\n\n📂 *Kategoriyani tanlang:*",
                    parse_mode="Markdown",
                    reply_markup=categories_inline(cats, {})
                )
            elif "Savat" in msg_text:
                # Savat ekranini yangilash
                await call.message.edit_text(
                    fmt_cart(cart),
                    parse_mode="Markdown",
                    reply_markup=cart_inline(cart)
                )
            elif cur_cat:
                prods = await api_get(f"/public/products?tenant_id={tenant_id}&category_id={cur_cat}") or []
                await call.message.edit_reply_markup(reply_markup=products_inline(prods, cart, cur_cat))
        except Exception as e:
            logger.error(f"sub edit: {e}")

        await call.answer(f"➖ {name}")

    # ─────── MAHSULOTNI BUTUNLAY O'CHIRISH ───────
    @dp.callback_query(F.data.startswith("del:"), OrderState.browsing)
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
                "🛒 Savat bo'sh bo'lib qoldi.\n\n📂 *Kategoriyani tanlang:*",
                parse_mode="Markdown",
                reply_markup=categories_inline(cats, {})
            )
        else:
            await call.message.edit_text(
                fmt_cart(cart),
                parse_mode="Markdown",
                reply_markup=cart_inline(cart)
            )
        await call.answer(f"🗑 {name} o'chirildi")

    # ─────── SAVATNI KO'RISH ───────
    @dp.callback_query(F.data == "cart:view", OrderState.browsing)
    async def view_cart(call: types.CallbackQuery, state: FSMContext):
        data = await state.get_data()
        cart = data.get("cart", {})
        if not cart:
            await call.answer("Savat bo'sh", show_alert=True)
            return
        await call.message.edit_text(
            fmt_cart(cart),
            parse_mode="Markdown",
            reply_markup=cart_inline(cart)
        )
        await call.answer()

    # ─────── SAVATNI TOZALASH ───────
    @dp.callback_query(F.data == "cart:clear", OrderState.browsing)
    async def clear_cart(call: types.CallbackQuery, state: FSMContext):
        await state.update_data(cart={})
        cats = (await state.get_data()).get("cats") or await api_get(f"/public/categories?tenant_id={tenant_id}") or []
        await call.message.edit_text(
            "🗑 Savat tozalandi.\n\n📂 *Kategoriyani tanlang:*",
            parse_mode="Markdown",
            reply_markup=categories_inline(cats, {})
        )
        await call.answer("Savat tozalandi")

    # ─────── BUYURTMANI RASMIYLASH ───────
    @dp.callback_query(F.data == "cart:checkout", OrderState.browsing)
    async def checkout(call: types.CallbackQuery, state: FSMContext):
        data = await state.get_data()
        cart = data.get("cart", {})
        if not cart:
            await call.answer("Savat bo'sh!", show_alert=True)
            return
        await state.set_state(OrderState.entering_phone)
        await call.message.edit_text(
            fmt_cart(cart) + "\n\n📱 *Telefon raqamingizni yuboring*",
            parse_mode="Markdown"
        )
        await call.message.answer(
            "Pastdagi tugma orqali raqamni yuboring yoki qo'lda yozing:",
            reply_markup=phone_kb()
        )
        await call.answer()

    @dp.callback_query(F.data == "noop")
    async def noop(call: types.CallbackQuery):
        await call.answer()

    # ─────── TELEFON (kontakt) ───────
    @dp.message(F.contact, OrderState.entering_phone)
    async def phone_contact(msg: types.Message, state: FSMContext):
        phone = msg.contact.phone_number
        if not phone.startswith("+"):
            phone = "+" + phone
        await state.update_data(phone=phone)
        await state.set_state(OrderState.entering_note)
        await msg.answer("💬 *Izoh qoldiring* yoki tugmani bosing:", parse_mode="Markdown", reply_markup=note_kb())

    # ─────── TELEFON (matn) ───────
    @dp.message(OrderState.entering_phone)
    async def phone_text(msg: types.Message, state: FSMContext):
        if msg.text == "❌ Bekor qilish":
            await state.clear()
            await msg.answer("❌ Buyurtma bekor qilindi.", reply_markup=main_kb())
            return
        phone = (msg.text or "").strip().replace(" ", "")
        if len(phone) < 9:
            await msg.answer("❌ Noto'g'ri raqam. Qaytadan kiriting yoki tugma orqali yuboring:")
            return
        await state.update_data(phone=phone)
        await state.set_state(OrderState.entering_note)
        await msg.answer("💬 *Izoh qoldiring* yoki tugmani bosing:", parse_mode="Markdown", reply_markup=note_kb())

    # ─────── IZOH ───────
    @dp.message(F.text == "⏭ Izohsiz davom etish", OrderState.entering_note)
    async def skip_note(msg: types.Message, state: FSMContext):
        await state.update_data(note="")
        await _show_confirm(msg, state)

    @dp.message(F.text == "❌ Bekor qilish", OrderState.entering_note)
    async def cancel_at_note(msg: types.Message, state: FSMContext):
        await state.clear()
        await msg.answer("❌ Buyurtma bekor qilindi.", reply_markup=main_kb())

    @dp.message(OrderState.entering_note)
    async def enter_note(msg: types.Message, state: FSMContext):
        await state.update_data(note=(msg.text or "").strip())
        await _show_confirm(msg, state)

    async def _show_confirm(msg, state):
        data = await state.get_data()
        cart = data.get("cart", {})
        phone = data.get("phone", "")
        note = data.get("note", "")
        note_text = f"\n💬 Izoh: {note}" if note else ""
        await state.set_state(OrderState.confirming)
        await msg.answer(
            f"📋 *Buyurtmangizni tasdiqlang:*\n\n"
            f"📱 {phone}{note_text}\n\n"
            f"{fmt_cart(cart)}\n\n"
            f"Hammasi to'g'rimi?",
            parse_mode="Markdown", reply_markup=confirm_kb()
        )

    # ─────── TASDIQLASH ───────
    @dp.message(F.text == "✅ Tasdiqlash", OrderState.confirming)
    async def confirm_order(msg: types.Message, state: FSMContext):
        data = await state.get_data()
        await msg.answer("⏳ Buyurtma yuborilmoqda...", reply_markup=ReplyKeyboardRemove())

        # MUHIM: order_type doim "bot" — admin keyin o'zi tartibga soladi
        result = await api_post("/public/bot-order", {
            "tenant_id": tenant_id,
            "order_type": "bot",
            "room_id": None,
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
            await msg.answer(
                f"✅ *Buyurtma qabul qilindi!*\n\n"
                f"📋 Buyurtma raqami: *#{str(order_id).zfill(4)}*\n"
                f"💰 Jami: *{int(total):,} so'm*\n\n"
                f"⏳ Admin ko'rib chiqmoqda. Tasdiqlangach xabar beramiz.\n\n"
                f"📋 *Buyurtmalarim* tugmasi orqali holatni kuzatib turing.",
                parse_mode="Markdown", reply_markup=main_kb()
            )
            # Admin ga xabar
            if admin_chat_id:
                bot_entry = next(
                    (v for v in active_bots.values() if v["tenant_id"] == tenant_id),
                    None
                )
                if bot_entry:
                    try:
                        await bot_entry["bot"].send_message(
                            chat_id=int(admin_chat_id),
                            text=fmt_admin_notify(
                                order_id,
                                data.get("cart", {}),
                                data.get("phone", ""),
                                data.get("note", ""),
                                msg.from_user.full_name or "",
                                tenant_name
                            ),
                            reply_markup=admin_order_inline(order_id),
                            parse_mode="Markdown"
                        )
                    except Exception as e:
                        logger.error(f"Admin ga xabar: {e}")
        else:
            await msg.answer("❌ Xato yuz berdi. Qaytadan urinib ko'ring.", reply_markup=main_kb())
        await state.clear()

    @dp.message(F.text == "❌ Bekor qilish", OrderState.confirming)
    async def cancel_order(msg: types.Message, state: FSMContext):
        await state.clear()
        await msg.answer("❌ Buyurtma bekor qilindi.", reply_markup=main_kb())

    # ─────── BUYURTMALARIM (tugmalar bilan) ───────
    @dp.message(F.text == "📋 Buyurtmalarim")
    async def my_orders(msg: types.Message):
        # Foydalanuvchining oxirgi buyurtmalarini olamiz
        orders = await api_get(f"/public/my-orders?tenant_id={tenant_id}&chat_id={msg.from_user.id}") or []
        if not orders:
            await msg.answer(
                "📭 Sizda hali buyurtma yo'q.\n\n"
                "🍽 *Buyurtma berish* tugmasi orqali yangi buyurtma bera olasiz.",
                parse_mode="Markdown",
                reply_markup=main_kb()
            )
            return

        # Inline tugmalar — har bir buyurtma uchun
        kb = []
        for o in orders[:10]:  # eng oxirgi 10 ta
            oid = o.get("id", 0)
            status = o.get("status", "")
            total = int(o.get("total", 0))
            kb.append([InlineKeyboardButton(
                text=f"#{str(oid).zfill(4)} • {fmt_status(status)} • {total:,} so'm",
                callback_data=f"order:{oid}"
            )])

        await msg.answer(
            "📋 *Sizning buyurtmalaringiz:*\n\n_Batafsil ko'rish uchun bosing 👇_",
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
            await call.answer("Buyurtma topilmadi", show_alert=True)
            return

        lines = [
            f"📋 *Buyurtma #{str(oid).zfill(4)}*",
            f"━━━━━━━━━━━━━━━━━━",
            f"Holat: {fmt_status(order.get('status', ''))}",
        ]
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
        if order.get("note"):
            lines.append(f"💬 Izoh: {order['note']}")

        try:
            await call.message.edit_text("\n".join(lines), parse_mode="Markdown")
        except Exception:
            await call.message.answer("\n".join(lines), parse_mode="Markdown")
        await call.answer()

    # ─────── MENYU (umumiy ko'rish) ───────
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
                    text.append(f"• {p['name'].strip()} — {int(p['price']):,} so'm")
        # Telegram limit
        full = "\n".join(text)
        if len(full) > 4000:
            full = full[:3990] + "...\n\n_Davomi uchun buyurtma berish tugmasini bosing._"
        await msg.answer(full, parse_mode="Markdown", reply_markup=main_kb())

    # ─────── ADMIN: QABUL QILISH ───────
    @dp.callback_query(F.data.startswith("accept_"))
    async def admin_accept(call: types.CallbackQuery):
        try:
            order_id = int(call.data.split("_")[1])
        except (ValueError, IndexError):
            await call.answer("Xato")
            return
        result = await api_post("/public/update-order-status", {
            "order_id": order_id,
            "status": "confirmed",
            "tenant_id": tenant_id
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
            await call.answer("❌ Xato yuz berdi", show_alert=True)

    # ─────── ADMIN: RAD ETISH ───────
    @dp.callback_query(F.data.startswith("reject_"))
    async def admin_reject(call: types.CallbackQuery):
        try:
            order_id = int(call.data.split("_")[1])
        except (ValueError, IndexError):
            await call.answer("Xato")
            return
        result = await api_post("/public/update-order-status", {
            "order_id": order_id,
            "status": "cancelled",
            "tenant_id": tenant_id
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
            await call.answer("❌ Xato yuz berdi", show_alert=True)

    # ─────── NOMA'LUM ───────
    @dp.message()
    async def unknown(msg: types.Message, state: FSMContext):
        if await state.get_state() is None:
            await msg.answer("👇 Quyidagi tugmalardan foydalaning:", reply_markup=main_kb())

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
