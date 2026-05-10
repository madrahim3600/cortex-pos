from fastapi import APIRouter, Depends, HTTPException
from sqlalchemy.orm import Session
from database import get_db
from models import *

router = APIRouter(prefix="/public", tags=["public"])


@router.get("/tenant-by-bot")
def get_tenant_by_bot(bot_token: str = None, db: Session = Depends(get_db)):
    if not bot_token:
        raise HTTPException(status_code=400, detail="bot_token kerak")
    config = db.query(BotConfig).filter(
        BotConfig.bot_token == bot_token, BotConfig.is_active == True
    ).first()
    if not config:
        raise HTTPException(status_code=404, detail="Bot konfiguratsiyasi topilmadi")
    return {"tenant_id": config.tenant_id, "welcome_message": config.welcome_message}


@router.get("/tenant-info/{tenant_id}")
def get_tenant_info(tenant_id: int, db: Session = Depends(get_db)):
    tenant = db.query(Tenant).filter(Tenant.id == tenant_id, Tenant.is_active == True).first()
    if not tenant:
        raise HTTPException(status_code=404, detail="Topilmadi")
    return {"id": tenant.id, "name": tenant.name, "type": tenant.type}


@router.get("/bot-configs")
def get_all_bot_configs(db: Session = Depends(get_db)):
    configs = db.query(BotConfig).filter(BotConfig.is_active == True).all()
    result = []
    for c in configs:
        tenant = db.query(Tenant).filter(Tenant.id == c.tenant_id, Tenant.is_active == True).first()
        if tenant and c.bot_token:
            result.append({
                "tenant_id": c.tenant_id,
                "tenant_name": tenant.name,
                "bot_token": c.bot_token,
                "is_active": c.is_active,
                "welcome_message": c.welcome_message,
                "admin_chat_id": c.admin_chat_id or ""
            })
    return result


@router.get("/rooms")
def get_public_rooms(tenant_id: int, db: Session = Depends(get_db)):
    rooms = db.query(Room).filter(Room.tenant_id == tenant_id, Room.is_active == True).all()
    return [{"id": r.id, "name": r.name, "capacity": r.capacity,
             "status": r.status.value if hasattr(r.status, 'value') else r.status} for r in rooms]


@router.get("/categories")
def get_public_categories(tenant_id: int, db: Session = Depends(get_db)):
    cats = db.query(Category).filter(
        Category.tenant_id == tenant_id, Category.is_active == True
    ).order_by(Category.sort_order).all()
    return [{"id": c.id, "name": c.name} for c in cats]


@router.get("/products")
def get_public_products(tenant_id: int, category_id: int = None, db: Session = Depends(get_db)):
    q = db.query(Product).filter(Product.tenant_id == tenant_id, Product.is_active == True)
    if category_id:
        q = q.filter(Product.category_id == category_id)
    products = q.all()
    return [{"id": p.id, "name": p.name, "price": p.price,
             "unit": p.unit.value if hasattr(p.unit, 'value') else p.unit,
             "category_id": p.category_id} for p in products]


@router.post("/bot-order")
def create_bot_order(data: dict, db: Session = Depends(get_db)):
    tenant_id = data.get("tenant_id", 1)
    tenant = db.query(Tenant).filter(Tenant.id == tenant_id, Tenant.is_active == True).first()
    if not tenant:
        raise HTTPException(status_code=404, detail="Biznes topilmadi")

    # reservation_time (oldindan band qilish uchun)
    reservation_time = None
    if data.get("reservation_time"):
        try:
            from datetime import datetime as _dt
            rt = data["reservation_time"]
            # ISO format yoki "YYYY-MM-DD HH:MM:SS"
            try:
                reservation_time = _dt.fromisoformat(rt)
            except ValueError:
                reservation_time = _dt.strptime(rt, "%Y-%m-%d %H:%M:%S")
        except Exception:
            reservation_time = None

    order = Order(
        tenant_id=tenant_id,
        order_type=data.get("order_type", "bot"),
        room_id=data.get("room_id"),
        customer_phone=data.get("customer_phone", ""),
        customer_name=data.get("customer_name", ""),
        note=data.get("note", ""),
        persons_count=data.get("persons_count", 1),
        reservation_time=reservation_time,
        status=OrderStatus.new,
        subtotal=0, total=0
    )
    db.add(order)
    db.flush()

    if data.get("bot_chat_id"):
        bot_user = db.query(BotUser).filter(
            BotUser.chat_id == str(data["bot_chat_id"]),
            BotUser.tenant_id == tenant_id
        ).first()
        if not bot_user:
            bot_user = BotUser(
                tenant_id=tenant_id,
                chat_id=str(data["bot_chat_id"]),
                full_name=data.get("customer_name", ""),
                phone=data.get("customer_phone", ""),
                username=data.get("username", "")
            )
            db.add(bot_user)
            db.flush()
        else:
            if data.get("customer_name"): bot_user.full_name = data["customer_name"]
            if data.get("customer_phone"): bot_user.phone = data["customer_phone"]
        order.bot_user_id = bot_user.id

    cart = data.get("cart", {})
    total = 0.0
    for product_id_str, qty in cart.items():
        try:
            product = db.query(Product).filter(
                Product.id == int(product_id_str),
                Product.tenant_id == tenant_id,
                Product.is_active == True
            ).first()
            if product and float(qty) > 0:
                q = float(qty)
                item_total = product.price * q
                total += item_total
                db.add(OrderItem(
                    order_id=order.id, product_id=product.id,
                    quantity=q, unit_price=product.price, total_price=item_total
                ))
                if product.deduct_inventory:
                    inv = db.query(Inventory).filter(
                        Inventory.product_id == product.id,
                        Inventory.tenant_id == tenant_id
                    ).first()
                    if inv and inv.quantity >= q:
                        inv.quantity -= q
        except Exception:
            continue

    order.subtotal = total
    order.total = total
    if order.room_id:
        room = db.query(Room).filter(Room.id == order.room_id).first()
        if room:
            # Agar oldindan band bo'lsa — reserved, hozirgi bo'lsa — busy
            if reservation_time:
                room.status = RoomStatus.reserved
            else:
                room.status = RoomStatus.busy
    db.commit()
    return {"id": order.id, "total": order.total, "status": "new"}


@router.post("/update-order-status")
def public_update_order_status(data: dict, db: Session = Depends(get_db)):
    """Bot admin inline tugmasi uchun — tasdiqlash yoki rad etish"""
    order_id = data.get("order_id")
    status = data.get("status")
    tenant_id = data.get("tenant_id")
    if not order_id or not status:
        raise HTTPException(status_code=400, detail="order_id va status kerak")
    allowed = ["confirmed", "cancelled"]
    if status not in allowed:
        raise HTTPException(status_code=400, detail=f"Status faqat: {allowed}")
    order = db.query(Order).filter(Order.id == order_id).first()
    if not order:
        raise HTTPException(status_code=404, detail="Buyurtma topilmadi")
    if tenant_id and order.tenant_id != tenant_id:
        raise HTTPException(status_code=403, detail="Ruxsat yo'q")
    order.status = status
    if status == "cancelled" and order.room_id:
        room = db.query(Room).filter(Room.id == order.room_id).first()
        if room: room.status = RoomStatus.free
    db.commit()
    return {"id": order.id, "status": status}


@router.get("/order-status/{order_id}")
def get_order_status(order_id: int, db: Session = Depends(get_db)):
    order = db.query(Order).filter(Order.id == order_id).first()
    if not order:
        raise HTTPException(status_code=404, detail="Topilmadi")
    return {
        "id": order.id,
        "status": order.status.value if hasattr(order.status, 'value') else order.status,
        "total": order.total
    }


@router.post("/bot-user")
def register_bot_user(data: dict, db: Session = Depends(get_db)):
    tenant_id = data.get("tenant_id", 1)
    chat_id = str(data.get("chat_id", ""))
    if not chat_id:
        raise HTTPException(status_code=400, detail="chat_id kerak")
    bot_user = db.query(BotUser).filter(
        BotUser.chat_id == chat_id, BotUser.tenant_id == tenant_id
    ).first()
    if not bot_user:
        bot_user = BotUser(
            tenant_id=tenant_id, chat_id=chat_id,
            full_name=data.get("full_name", ""),
            phone=data.get("phone", ""),
            username=data.get("username", "")
        )
        db.add(bot_user)
        db.commit()
        db.refresh(bot_user)
    return {"id": bot_user.id, "chat_id": bot_user.chat_id}


@router.get("/my-orders")
def get_my_orders(tenant_id: int, chat_id: str, db: Session = Depends(get_db)):
    """Botdagi foydalanuvchining oxirgi buyurtmalari"""
    bot_user = db.query(BotUser).filter(
        BotUser.chat_id == str(chat_id), BotUser.tenant_id == tenant_id
    ).first()
    if not bot_user:
        return []
    orders = db.query(Order).filter(
        Order.tenant_id == tenant_id,
        Order.bot_user_id == bot_user.id
    ).order_by(Order.id.desc()).limit(10).all()
    return [{
        "id": o.id,
        "status": o.status.value if hasattr(o.status, 'value') else o.status,
        "total": o.total,
        "created_at": str(o.created_at)
    } for o in orders]


@router.get("/order-detail/{order_id}")
def get_order_detail(order_id: int, db: Session = Depends(get_db)):
    """Buyurtma haqida batafsil"""
    order = db.query(Order).filter(Order.id == order_id).first()
    if not order:
        raise HTTPException(status_code=404, detail="Topilmadi")
    items = []
    for item in order.items:
        items.append({
            "product_name": item.product.name if item.product else "O'chirilgan mahsulot",
            "quantity": item.quantity,
            "unit_price": item.unit_price,
            "total_price": item.total_price
        })
    return {
        "id": order.id,
        "status": order.status.value if hasattr(order.status, 'value') else order.status,
        "total": order.total,
        "subtotal": order.subtotal,
        "discount": order.discount,
        "note": order.note or "",
        "items": items,
        "created_at": str(order.created_at)
    }
