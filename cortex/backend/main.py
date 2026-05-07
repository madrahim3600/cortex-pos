from fastapi import FastAPI, Depends, HTTPException, status, BackgroundTasks
from fastapi.middleware.cors import CORSMiddleware
from fastapi.security import OAuth2PasswordBearer, OAuth2PasswordRequestForm
from sqlalchemy.orm import Session
from datetime import datetime, timedelta
from jose import JWTError, jwt
from passlib.context import CryptContext
import os
import aiohttp
from dotenv import load_dotenv

from database import engine, get_db, Base, SessionLocal
from models import *

load_dotenv()
Base.metadata.create_all(bind=engine)

app = FastAPI(title="CORTEX POS API", version="1.0.0")

app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],
    allow_credentials=True,
    allow_methods=["*"],
    allow_headers=["*"],
)

SECRET_KEY = os.getenv("SECRET_KEY", "cortex-secret-key")
ALGORITHM = os.getenv("ALGORITHM", "HS256")
ACCESS_TOKEN_EXPIRE_MINUTES = int(os.getenv("ACCESS_TOKEN_EXPIRE_MINUTES", 10080))
BOT_SERVICE_URL = os.getenv("BOT_SERVICE_URL", "")

pwd_context = CryptContext(schemes=["bcrypt"], deprecated="auto")
oauth2_scheme = OAuth2PasswordBearer(tokenUrl="auth/login")


def verify_password(plain, hashed):
    return pwd_context.verify(plain, hashed)


def hash_password(password):
    return pwd_context.hash(password)


def create_token(data: dict):
    to_encode = data.copy()
    expire = datetime.utcnow() + timedelta(minutes=ACCESS_TOKEN_EXPIRE_MINUTES)
    to_encode.update({"exp": expire})
    return jwt.encode(to_encode, SECRET_KEY, algorithm=ALGORITHM)


def get_current_user(token: str = Depends(oauth2_scheme), db: Session = Depends(get_db)):
    try:
        payload = jwt.decode(token, SECRET_KEY, algorithms=[ALGORITHM])
        user_id: int = payload.get("sub")
        if user_id is None:
            raise HTTPException(status_code=401, detail="Token noto'g'ri")
    except JWTError:
        raise HTTPException(status_code=401, detail="Token noto'g'ri")
    user = db.query(User).filter(User.id == int(user_id)).first()
    if user is None or not user.is_active:
        raise HTTPException(status_code=401, detail="Foydalanuvchi topilmadi")
    return user


def require_role(*roles):
    def checker(current_user: User = Depends(get_current_user)):
        if current_user.role not in roles:
            raise HTTPException(status_code=403, detail="Ruxsat yo'q")
        return current_user
    return checker


async def notify_bot_service(endpoint: str, data: dict):
    if not BOT_SERVICE_URL:
        return
    try:
        async with aiohttp.ClientSession() as session:
            async with session.post(
                f"{BOT_SERVICE_URL}{endpoint}",
                json=data,
                timeout=aiohttp.ClientTimeout(total=5)
            ) as resp:
                if resp.status != 200:
                    text = await resp.text()
                    print(f"Bot service xato [{endpoint}]: {resp.status} - {text}")
    except Exception as e:
        print(f"Bot service ulanishda xato: {e}")


from routers.public import router as public_router
app.include_router(public_router)


@app.on_event("startup")
def create_super_admin():
    db = SessionLocal()
    try:
        existing = db.query(User).filter(
            User.login == os.getenv("SUPER_ADMIN_LOGIN", "superadmin")
        ).first()
        if not existing:
            super_admin = User(
                full_name="Super Admin",
                login=os.getenv("SUPER_ADMIN_LOGIN", "superadmin"),
                hashed_password=hash_password(os.getenv("SUPER_ADMIN_PASSWORD", "Cortex2025!")),
                role=UserRole.super_admin,
                tenant_id=None,
                is_active=True
            )
            db.add(super_admin)
            db.commit()
    finally:
        db.close()


@app.get("/")
def root():
    return {"status": "ok", "app": "CORTEX POS", "version": "1.0.0"}


@app.get("/health")
def health():
    return {"status": "healthy"}


# ══════════════════════════════════════════════
# AUTH
# ══════════════════════════════════════════════

@app.post("/auth/login")
def login(form: OAuth2PasswordRequestForm = Depends(), db: Session = Depends(get_db)):
    user = db.query(User).filter(User.login == form.username).first()
    if not user or not verify_password(form.password, user.hashed_password):
        raise HTTPException(status_code=400, detail="Login yoki parol xato")
    if not user.is_active:
        raise HTTPException(status_code=400, detail="Hisob bloklangan")
    token = create_token({"sub": str(user.id), "role": user.role})
    return {
        "access_token": token,
        "token_type": "bearer",
        "role": user.role,
        "full_name": user.full_name,
        "tenant_id": user.tenant_id
    }


@app.get("/auth/me")
def get_me(current_user: User = Depends(get_current_user)):
    return {
        "id": current_user.id,
        "full_name": current_user.full_name,
        "login": current_user.login,
        "role": current_user.role,
        "tenant_id": current_user.tenant_id
    }


@app.post("/auth/change-password")
def change_password(
    old_password: str,
    new_password: str,
    db: Session = Depends(get_db),
    current_user: User = Depends(get_current_user)
):
    if not verify_password(old_password, current_user.hashed_password):
        raise HTTPException(status_code=400, detail="Eski parol noto'g'ri")
    if len(new_password) < 4:
        raise HTTPException(status_code=400, detail="Parol kamida 4 belgi")
    current_user.hashed_password = hash_password(new_password)
    db.commit()
    return {"message": "Parol o'zgartirildi"}


# ══════════════════════════════════════════════
# SUPER ADMIN
# ══════════════════════════════════════════════

@app.get("/superadmin/stats")
def get_super_stats(
    db: Session = Depends(get_db),
    current_user: User = Depends(require_role(UserRole.super_admin))
):
    tenants = db.query(Tenant).all()
    result = []
    for tenant in tenants:
        orders = db.query(Order).filter(Order.tenant_id == tenant.id, Order.status == OrderStatus.paid).all()
        result.append({
            "id": tenant.id,
            "name": tenant.name,
            "type": tenant.type,
            "phone": tenant.phone,
            "is_active": tenant.is_active,
            "created_at": str(tenant.created_at),
            "total_revenue": sum(o.total for o in orders),
            "total_orders": len(orders),
            "users_count": db.query(User).filter(User.tenant_id == tenant.id).count()
        })
    return {
        "total_tenants": len(tenants),
        "active_tenants": len([t for t in tenants if t.is_active]),
        "tenants": result
    }


@app.post("/superadmin/tenants")
def create_tenant(
    name: str, type: str, address: str = "", phone: str = "",
    admin_name: str = "", admin_login: str = "", admin_password: str = "",
    db: Session = Depends(get_db),
    current_user: User = Depends(require_role(UserRole.super_admin))
):
    if db.query(User).filter(User.login == admin_login).first():
        raise HTTPException(status_code=400, detail="Bu admin login allaqachon band")
    tenant = Tenant(name=name, type=type, address=address, phone=phone)
    db.add(tenant)
    db.flush()
    admin = User(
        tenant_id=tenant.id, full_name=admin_name, login=admin_login,
        hashed_password=hash_password(admin_password), role=UserRole.admin
    )
    db.add(admin)
    db.commit()
    return {"id": tenant.id, "name": tenant.name, "message": "Yaratildi"}


@app.put("/superadmin/tenants/{tenant_id}")
def update_tenant(
    tenant_id: int, name: str = None, type: str = None, phone: str = None,
    db: Session = Depends(get_db),
    current_user: User = Depends(require_role(UserRole.super_admin))
):
    tenant = db.query(Tenant).filter(Tenant.id == tenant_id).first()
    if not tenant:
        raise HTTPException(status_code=404, detail="Topilmadi")
    if name: tenant.name = name
    if type: tenant.type = type
    if phone is not None: tenant.phone = phone
    db.commit()
    return {"message": "Yangilandi"}


@app.patch("/superadmin/tenants/{tenant_id}/toggle")
def toggle_tenant(
    tenant_id: int,
    db: Session = Depends(get_db),
    current_user: User = Depends(require_role(UserRole.super_admin))
):
    tenant = db.query(Tenant).filter(Tenant.id == tenant_id).first()
    if not tenant:
        raise HTTPException(status_code=404, detail="Topilmadi")
    tenant.is_active = not tenant.is_active
    db.commit()
    return {"is_active": tenant.is_active}


@app.delete("/superadmin/tenants/{tenant_id}")
def delete_tenant(
    tenant_id: int,
    db: Session = Depends(get_db),
    current_user: User = Depends(require_role(UserRole.super_admin))
):
    tenant = db.query(Tenant).filter(Tenant.id == tenant_id).first()
    if not tenant:
        raise HTTPException(status_code=404, detail="Topilmadi")
    db.delete(tenant)
    db.commit()
    return {"message": "O'chirildi"}


# ══════════════════════════════════════════════
# ROOMS
# ══════════════════════════════════════════════

@app.get("/rooms")
def get_rooms(db: Session = Depends(get_db), current_user: User = Depends(get_current_user)):
    return db.query(Room).filter(
        Room.tenant_id == current_user.tenant_id, Room.is_active == True
    ).all()


@app.post("/rooms")
def create_room(
    name: str, capacity: int, room_type: str = "standard",
    db: Session = Depends(get_db),
    current_user: User = Depends(require_role(UserRole.admin))
):
    room = Room(tenant_id=current_user.tenant_id, name=name, capacity=capacity, room_type=room_type)
    db.add(room)
    db.commit()
    db.refresh(room)
    return room


@app.patch("/rooms/{room_id}")
def update_room(
    room_id: int, name: str = None, capacity: int = None, status: str = None,
    db: Session = Depends(get_db),
    current_user: User = Depends(require_role(UserRole.admin, UserRole.cashier))
):
    room = db.query(Room).filter(Room.id == room_id, Room.tenant_id == current_user.tenant_id).first()
    if not room:
        raise HTTPException(status_code=404, detail="Topilmadi")
    if name: room.name = name
    if capacity: room.capacity = capacity
    if status: room.status = status
    db.commit()
    return room


@app.delete("/rooms/{room_id}")
def delete_room(
    room_id: int,
    db: Session = Depends(get_db),
    current_user: User = Depends(require_role(UserRole.admin))
):
    room = db.query(Room).filter(Room.id == room_id, Room.tenant_id == current_user.tenant_id).first()
    if not room:
        raise HTTPException(status_code=404, detail="Topilmadi")
    db.delete(room)
    db.commit()
    return {"message": "O'chirildi"}


# ══════════════════════════════════════════════
# CATEGORIES
# ══════════════════════════════════════════════

@app.get("/categories")
def get_categories(db: Session = Depends(get_db), current_user: User = Depends(get_current_user)):
    return db.query(Category).filter(
        Category.tenant_id == current_user.tenant_id, Category.is_active == True
    ).order_by(Category.sort_order).all()


@app.post("/categories")
def create_category(
    name: str, sort_order: int = 0,
    db: Session = Depends(get_db),
    current_user: User = Depends(require_role(UserRole.admin))
):
    cat = Category(tenant_id=current_user.tenant_id, name=name, sort_order=sort_order)
    db.add(cat)
    db.commit()
    db.refresh(cat)
    return cat


@app.patch("/categories/{category_id}")
def update_category(
    category_id: int, name: str = None, sort_order: int = None,
    db: Session = Depends(get_db),
    current_user: User = Depends(require_role(UserRole.admin))
):
    cat = db.query(Category).filter(
        Category.id == category_id, Category.tenant_id == current_user.tenant_id
    ).first()
    if not cat:
        raise HTTPException(status_code=404, detail="Topilmadi")
    if name: cat.name = name
    if sort_order is not None: cat.sort_order = sort_order
    db.commit()
    return cat


@app.delete("/categories/{category_id}")
def delete_category(
    category_id: int,
    db: Session = Depends(get_db),
    current_user: User = Depends(require_role(UserRole.admin))
):
    cat = db.query(Category).filter(
        Category.id == category_id, Category.tenant_id == current_user.tenant_id
    ).first()
    if not cat:
        raise HTTPException(status_code=404, detail="Topilmadi")
    db.delete(cat)
    db.commit()
    return {"message": "O'chirildi"}


# ══════════════════════════════════════════════
# PRODUCTS
# ══════════════════════════════════════════════

@app.get("/products")
def get_products(
    category_id: int = None,
    db: Session = Depends(get_db),
    current_user: User = Depends(get_current_user)
):
    q = db.query(Product).filter(
        Product.tenant_id == current_user.tenant_id, Product.is_active == True
    )
    if category_id:
        q = q.filter(Product.category_id == category_id)
    return q.all()


@app.post("/products")
def create_product(
    name: str, category_id: int, unit: str, price: float, deduct_inventory: bool = True,
    db: Session = Depends(get_db),
    current_user: User = Depends(require_role(UserRole.admin))
):
    cat = db.query(Category).filter(
        Category.id == category_id, Category.tenant_id == current_user.tenant_id
    ).first()
    if not cat:
        raise HTTPException(status_code=404, detail="Kategoriya topilmadi")
    valid_units = [u.value for u in UnitType]
    if unit not in valid_units:
        unit = "piece"
    product = Product(
        tenant_id=current_user.tenant_id, category_id=category_id,
        name=name, unit=unit, price=price, deduct_inventory=deduct_inventory
    )
    db.add(product)
    db.flush()
    if deduct_inventory:
        inv = Inventory(tenant_id=current_user.tenant_id, product_id=product.id, quantity=0)
        db.add(inv)
    db.commit()
    db.refresh(product)
    return product


@app.patch("/products/{product_id}")
def update_product(
    product_id: int, name: str = None, price: float = None,
    unit: str = None, category_id: int = None,
    db: Session = Depends(get_db),
    current_user: User = Depends(require_role(UserRole.admin))
):
    product = db.query(Product).filter(
        Product.id == product_id, Product.tenant_id == current_user.tenant_id
    ).first()
    if not product:
        raise HTTPException(status_code=404, detail="Topilmadi")
    if name: product.name = name
    if price is not None and price > 0: product.price = price
    if unit and unit in [u.value for u in UnitType]: product.unit = unit
    if category_id is not None: product.category_id = category_id
    db.commit()
    return product


@app.delete("/products/{product_id}")
def delete_product(
    product_id: int,
    db: Session = Depends(get_db),
    current_user: User = Depends(require_role(UserRole.admin))
):
    product = db.query(Product).filter(
        Product.id == product_id, Product.tenant_id == current_user.tenant_id
    ).first()
    if not product:
        raise HTTPException(status_code=404, detail="Topilmadi")
    product.is_active = False
    db.commit()
    return {"message": "O'chirildi"}


# ══════════════════════════════════════════════
# INVENTORY
# ══════════════════════════════════════════════

@app.get("/inventory")
def get_inventory(db: Session = Depends(get_db), current_user: User = Depends(get_current_user)):
    items = db.query(Inventory).join(Product).filter(
        Inventory.tenant_id == current_user.tenant_id, Product.is_active == True
    ).all()
    result = []
    for item in items:
        inv_status = "ok"
        if item.quantity <= 0:
            inv_status = "empty"
        elif item.quantity <= item.min_quantity:
            inv_status = "low"
        result.append({
            "id": item.id,
            "product_id": item.product_id,
            "product_name": item.product.name,
            "quantity": item.quantity,
            "min_quantity": item.min_quantity,
            "unit_cost": item.unit_cost,
            "supplier": item.supplier or "",
            "status": inv_status
        })
    return result


@app.post("/inventory/{product_id}/add")
def add_inventory(
    product_id: int, quantity: float, unit_cost: float = 0, supplier: str = "",
    db: Session = Depends(get_db),
    current_user: User = Depends(require_role(UserRole.admin, UserRole.cashier))
):
    inv = db.query(Inventory).filter(
        Inventory.product_id == product_id, Inventory.tenant_id == current_user.tenant_id
    ).first()
    if not inv:
        raise HTTPException(status_code=404, detail="Topilmadi")
    inv.quantity += quantity
    if unit_cost > 0: inv.unit_cost = unit_cost
    if supplier: inv.supplier = supplier
    db.commit()
    return {"message": f"{quantity} qo'shildi", "new_quantity": inv.quantity}


@app.patch("/inventory/{inventory_id}")
def update_inventory(
    inventory_id: int, quantity: float = None, min_quantity: float = None,
    db: Session = Depends(get_db),
    current_user: User = Depends(require_role(UserRole.admin, UserRole.cashier))
):
    inv = db.query(Inventory).filter(
        Inventory.id == inventory_id, Inventory.tenant_id == current_user.tenant_id
    ).first()
    if not inv:
        raise HTTPException(status_code=404, detail="Topilmadi")
    if quantity is not None: inv.quantity = quantity
    if min_quantity is not None: inv.min_quantity = min_quantity
    db.commit()
    return {"message": "Yangilandi", "quantity": inv.quantity}


# ══════════════════════════════════════════════
# ORDERS
# ══════════════════════════════════════════════

def order_to_dict(order):
    items = []
    for item in order.items:
        items.append({
            "id": item.id,
            "product_id": item.product_id,
            "product_name": item.product.name if item.product else "",
            "quantity": item.quantity,
            "unit_price": item.unit_price,
            "total_price": item.total_price
        })
    return {
        "id": order.id,
        "order_type": order.order_type,
        "status": order.status,
        "customer_name": order.customer_name or "",
        "customer_phone": order.customer_phone or "",
        "note": order.note or "",
        "subtotal": order.subtotal,
        "discount": order.discount,
        "total": order.total,
        "room_id": order.room_id,
        "created_at": str(order.created_at),
        "items": items
    }


@app.get("/orders")
def get_orders(
    status: str = None,
    order_type: str = None,
    db: Session = Depends(get_db),
    current_user: User = Depends(get_current_user)
):
    q = db.query(Order).filter(Order.tenant_id == current_user.tenant_id)
    if status:
        q = q.filter(Order.status == status)
    if order_type:
        q = q.filter(Order.order_type == order_type)
    orders = q.order_by(Order.created_at.desc()).limit(200).all()
    return [order_to_dict(o) for o in orders]


@app.get("/orders/{order_id}")
def get_order(
    order_id: int,
    db: Session = Depends(get_db),
    current_user: User = Depends(get_current_user)
):
    order = db.query(Order).filter(
        Order.id == order_id, Order.tenant_id == current_user.tenant_id
    ).first()
    if not order:
        raise HTTPException(status_code=404, detail="Topilmadi")
    return order_to_dict(order)


@app.post("/orders")
def create_order(
    order_type: str, room_id: int = None,
    customer_phone: str = "", customer_name: str = "",
    note: str = "", persons_count: int = 1,
    db: Session = Depends(get_db),
    current_user: User = Depends(get_current_user)
):
    order = Order(
        tenant_id=current_user.tenant_id, order_type=order_type,
        room_id=room_id, customer_phone=customer_phone,
        customer_name=customer_name, note=note,
        persons_count=persons_count, status=OrderStatus.new
    )
    if room_id:
        room = db.query(Room).filter(Room.id == room_id).first()
        if room:
            room.status = RoomStatus.busy
    db.add(order)
    db.commit()
    db.refresh(order)
    return {"id": order.id, "status": order.status, "total": order.total}


@app.post("/orders/{order_id}/items")
def add_order_item(
    order_id: int, product_id: int, quantity: float,
    db: Session = Depends(get_db),
    current_user: User = Depends(get_current_user)
):
    order = db.query(Order).filter(
        Order.id == order_id, Order.tenant_id == current_user.tenant_id
    ).first()
    if not order:
        raise HTTPException(status_code=404, detail="Topilmadi")
    product = db.query(Product).filter(
        Product.id == product_id, Product.tenant_id == current_user.tenant_id
    ).first()
    if not product:
        raise HTTPException(status_code=404, detail="Mahsulot topilmadi")
    total = product.price * quantity
    item = OrderItem(
        order_id=order_id, product_id=product_id,
        quantity=quantity, unit_price=product.price, total_price=total
    )
    db.add(item)
    order.subtotal += total
    order.total = order.subtotal - order.discount
    if product.deduct_inventory:
        inv = db.query(Inventory).filter(
            Inventory.product_id == product_id,
            Inventory.tenant_id == current_user.tenant_id
        ).first()
        if inv and inv.quantity >= quantity:
            inv.quantity -= quantity
    db.commit()
    return {"item_id": item.id, "order_total": order.total}


@app.delete("/orders/{order_id}/items/{item_id}")
def remove_order_item(
    order_id: int, item_id: int,
    db: Session = Depends(get_db),
    current_user: User = Depends(get_current_user)
):
    order = db.query(Order).filter(
        Order.id == order_id, Order.tenant_id == current_user.tenant_id
    ).first()
    if not order:
        raise HTTPException(status_code=404, detail="Topilmadi")
    item = db.query(OrderItem).filter(
        OrderItem.id == item_id, OrderItem.order_id == order_id
    ).first()
    if not item:
        raise HTTPException(status_code=404, detail="Element topilmadi")
    if item.product and item.product.deduct_inventory:
        inv = db.query(Inventory).filter(
            Inventory.product_id == item.product_id,
            Inventory.tenant_id == current_user.tenant_id
        ).first()
        if inv:
            inv.quantity += item.quantity
    order.subtotal -= item.total_price
    if order.subtotal < 0:
        order.subtotal = 0
    order.total = order.subtotal - order.discount
    db.delete(item)
    db.commit()
    return {"message": "O'chirildi", "order_total": order.total}


@app.patch("/orders/{order_id}/status")
async def update_order_status(
    order_id: int, new_status: str,
    background_tasks: BackgroundTasks,
    db: Session = Depends(get_db),
    current_user: User = Depends(get_current_user)
):
    order = db.query(Order).filter(
        Order.id == order_id, Order.tenant_id == current_user.tenant_id
    ).first()
    if not order:
        raise HTTPException(status_code=404, detail="Topilmadi")
    order.status = new_status
    if new_status in ["paid", "cancelled"] and order.room_id:
        room = db.query(Room).filter(Room.id == order.room_id).first()
        if room:
            room.status = RoomStatus.free
    db.commit()

    if order.bot_user_id and BOT_SERVICE_URL:
        bot_user = db.query(BotUser).filter(BotUser.id == order.bot_user_id).first()
        bot_config = db.query(BotConfig).filter(
            BotConfig.tenant_id == current_user.tenant_id
        ).first()
        if bot_user and bot_config and bot_config.bot_token:
            status_messages = {
                "confirmed": f"✅ Buyurtmangiz #{str(order_id).zfill(4)} tasdiqlandi! Tayyorlanmoqda...",
                "preparing": f"👨‍🍳 Buyurtmangiz #{str(order_id).zfill(4)} tayyorlanmoqda...",
                "ready": f"🔔 Buyurtmangiz #{str(order_id).zfill(4)} tayyor! Kelishingiz mumkin.",
                "cancelled": f"❌ Buyurtmangiz #{str(order_id).zfill(4)} bekor qilindi."
            }
            msg = status_messages.get(new_status)
            if msg:
                background_tasks.add_task(
                    notify_bot_service,
                    "/internal/notify",
                    {
                        "bot_token": bot_config.bot_token,
                        "chat_id": bot_user.chat_id,
                        "message": msg
                    }
                )
    return {"id": order.id, "status": order.status}


@app.post("/orders/{order_id}/pay")
async def pay_order(
    order_id: int, method: str, amount: float, discount: float = 0,
    background_tasks: BackgroundTasks = None,
    db: Session = Depends(get_db),
    current_user: User = Depends(get_current_user)
):
    order = db.query(Order).filter(
        Order.id == order_id, Order.tenant_id == current_user.tenant_id
    ).first()
    if not order:
        raise HTTPException(status_code=404, detail="Topilmadi")
    if db.query(Payment).filter(Payment.order_id == order_id).first():
        raise HTTPException(status_code=400, detail="Allaqachon to'langan")

    order.discount = discount
    order.total = order.subtotal - discount
    if order.total < 0: order.total = 0
    order.status = OrderStatus.paid

    valid_methods = [m.value for m in PaymentMethod]
    if method not in valid_methods: method = "cash"

    payment = Payment(order_id=order_id, method=method, amount=amount)
    db.add(payment)
    if order.room_id:
        room = db.query(Room).filter(Room.id == order.room_id).first()
        if room: room.status = RoomStatus.free
    db.commit()

    items = [{
        "product_name": item.product.name if item.product else "",
        "quantity": item.quantity,
        "unit_price": item.unit_price,
        "total_price": item.total_price
    } for item in order.items]

    # Bot orqali mijozga chek yuborish
    if order.bot_user_id and BOT_SERVICE_URL and background_tasks:
        bot_user = db.query(BotUser).filter(BotUser.id == order.bot_user_id).first()
        bot_config = db.query(BotConfig).filter(
            BotConfig.tenant_id == current_user.tenant_id
        ).first()
        tenant = db.query(Tenant).filter(Tenant.id == current_user.tenant_id).first()
        if bot_user and bot_config and bot_config.bot_token:
            receipt_order = {
                "id": order.id,
                "total": order.total,
                "discount": order.discount,
                "method": method,
                "items": items
            }
            background_tasks.add_task(
                notify_bot_service,
                "/internal/send-receipt",
                {
                    "bot_token": bot_config.bot_token,
                    "chat_id": bot_user.chat_id,
                    "order": receipt_order,
                    "tenant_name": tenant.name if tenant else "Restoran"
                }
            )

    return {
        "message": "To'lov qabul qilindi",
        "order_id": order.id,
        "total": order.total,
        "discount": order.discount,
        "method": method,
        "items": items,
        "paid_at": str(datetime.utcnow())
    }


# ── ADMIN → MIJOZGA QO'LDA XABAR ──
@app.post("/orders/{order_id}/send-message")
async def send_message_to_customer(
    order_id: int,
    message: str,
    background_tasks: BackgroundTasks,
    db: Session = Depends(get_db),
    current_user: User = Depends(require_role(UserRole.admin, UserRole.cashier))
):
    """Admin bot orqali mijozga qo'lda xabar yuboradi"""
    order = db.query(Order).filter(
        Order.id == order_id,
        Order.tenant_id == current_user.tenant_id
    ).first()
    if not order:
        raise HTTPException(status_code=404, detail="Buyurtma topilmadi")
    if not order.bot_user_id:
        raise HTTPException(status_code=400, detail="Bu buyurtma bot orqali kelmagan")

    bot_user = db.query(BotUser).filter(BotUser.id == order.bot_user_id).first()
    bot_config = db.query(BotConfig).filter(
        BotConfig.tenant_id == current_user.tenant_id
    ).first()

    if not bot_user or not bot_config or not bot_config.bot_token:
        raise HTTPException(status_code=400, detail="Bot sozlanmagan")

    if not BOT_SERVICE_URL:
        raise HTTPException(status_code=503, detail="Bot servisi ulanmagan")

    background_tasks.add_task(
        notify_bot_service,
        "/internal/notify",
        {
            "bot_token": bot_config.bot_token,
            "chat_id": bot_user.chat_id,
            "message": f"💬 *Admin xabari:*\n{message}"
        }
    )
    return {"message": "Xabar yuborildi"}



@app.post("/orders/{order_id}/extra")
def add_extra_charge(
    order_id: int,
    description: str,
    amount: float,
    db: Session = Depends(get_db),
    current_user: User = Depends(get_current_user)
):
    """Qo'shimcha xizmat haqi qo'shish (servis haqi, stol haqi va h.k.)"""
    order = db.query(Order).filter(
        Order.id == order_id,
        Order.tenant_id == current_user.tenant_id
    ).first()
    if not order:
        raise HTTPException(status_code=404, detail="Buyurtma topilmadi")
    
    # Maxsus "extra" mahsulot qo'shamiz
    item = OrderItem(
        order_id=order_id,
        product_id=None,
        quantity=1,
        unit_price=amount,
        total_price=amount
    )
    # Note ga qo'shamiz
    extra_note = f"{description}: {amount:,.0f} so'm"
    if order.note:
        order.note = order.note + " | " + extra_note
    else:
        order.note = extra_note
    
    order.subtotal += amount
    order.total = order.subtotal - order.discount
    db.commit()
    return {"message": "Qo'shildi", "order_total": order.total}

# ══════════════════════════════════════════════
# STATISTICS
# ══════════════════════════════════════════════

@app.get("/statistics")
def get_statistics(
    period: str = "today",
    db: Session = Depends(get_db),
    current_user: User = Depends(get_current_user)
):
    now = datetime.utcnow()
    if period == "today":
        start = now.replace(hour=0, minute=0, second=0, microsecond=0)
    elif period == "week":
        start = now - timedelta(days=7)
    elif period == "month":
        start = now - timedelta(days=30)
    else:
        start = now.replace(hour=0, minute=0, second=0, microsecond=0)

    orders = db.query(Order).filter(
        Order.tenant_id == current_user.tenant_id,
        Order.status == OrderStatus.paid,
        Order.created_at >= start
    ).all()

    total_revenue = sum(o.total for o in orders)
    cash = card = online = 0.0
    for order in orders:
        if order.payment:
            if order.payment.method == PaymentMethod.cash:
                cash += order.total
            elif order.payment.method == PaymentMethod.card:
                card += order.total
            else:
                online += order.total

    return {
        "period": period,
        "total_revenue": total_revenue,
        "total_orders": len(orders),
        "dine_in_orders": len([o for o in orders if o.order_type == OrderType.dine_in]),
        "takeaway_orders": len([o for o in orders if o.order_type == OrderType.takeaway]),
        "bot_orders": len([o for o in orders if o.order_type == OrderType.bot]),
        "avg_order": total_revenue / len(orders) if orders else 0,
        "cash": cash,
        "card": card,
        "online": online
    }


# ══════════════════════════════════════════════
# BOT CONFIG
# ══════════════════════════════════════════════

@app.get("/bot-config")
def get_bot_config(
    db: Session = Depends(get_db),
    current_user: User = Depends(require_role(UserRole.admin))
):
    config = db.query(BotConfig).filter(BotConfig.tenant_id == current_user.tenant_id).first()
    if not config:
        return {"configured": False}
    return {
        "configured": True,
        "bot_token": config.bot_token,
        "welcome_message": config.welcome_message,
        "is_active": config.is_active,
        "admin_chat_id": config.admin_chat_id or ""
    }


@app.post("/bot-config")
async def save_bot_config(
    bot_token: str,
    welcome_message: str = "Xush kelibsiz!",
    admin_chat_id: str = "",
    background_tasks: BackgroundTasks = BackgroundTasks(),
    db: Session = Depends(get_db),
    current_user: User = Depends(require_role(UserRole.admin))
):
    tenant = db.query(Tenant).filter(Tenant.id == current_user.tenant_id).first()
    old_config = db.query(BotConfig).filter(BotConfig.tenant_id == current_user.tenant_id).first()

    if old_config and old_config.bot_token and old_config.bot_token != bot_token and BOT_SERVICE_URL:
        await notify_bot_service("/internal/unregister-bot", {"bot_token": old_config.bot_token})

    if old_config:
        old_config.bot_token = bot_token
        old_config.welcome_message = welcome_message
        old_config.is_active = True
        if admin_chat_id:
            old_config.admin_chat_id = admin_chat_id
    else:
        old_config = BotConfig(
            tenant_id=current_user.tenant_id,
            bot_token=bot_token,
            welcome_message=welcome_message,
            admin_chat_id=admin_chat_id if admin_chat_id else None,
            is_active=True
        )
        db.add(old_config)
    db.commit()

    if BOT_SERVICE_URL:
        await notify_bot_service(
            "/internal/register-bot",
            {
                "bot_token": bot_token,
                "tenant_id": current_user.tenant_id,
                "tenant_name": tenant.name if tenant else "Restoran",
                "admin_chat_id": old_config.admin_chat_id or ""
            }
        )
    return {"message": "Bot sozlamalari saqlandi"}


@app.delete("/bot-config")
async def delete_bot_config(
    background_tasks: BackgroundTasks,
    db: Session = Depends(get_db),
    current_user: User = Depends(require_role(UserRole.admin))
):
    config = db.query(BotConfig).filter(BotConfig.tenant_id == current_user.tenant_id).first()
    if not config:
        raise HTTPException(status_code=404, detail="Bot sozlamasi topilmadi")
    if config.bot_token and BOT_SERVICE_URL:
        background_tasks.add_task(
            notify_bot_service,
            "/internal/unregister-bot",
            {"bot_token": config.bot_token}
        )
    db.delete(config)
    db.commit()
    return {"message": "Bot o'chirildi"}


# ══════════════════════════════════════════════
# WORKERS
# ══════════════════════════════════════════════

@app.get("/workers")
def get_workers(
    db: Session = Depends(get_db),
    current_user: User = Depends(require_role(UserRole.admin))
):
    workers = db.query(User).filter(
        User.tenant_id == current_user.tenant_id,
        User.role != UserRole.admin
    ).all()
    return [{"id": w.id, "full_name": w.full_name, "login": w.login, "role": w.role, "is_active": w.is_active} for w in workers]


@app.post("/workers")
def create_worker(
    full_name: str, login: str, password: str, role: str,
    db: Session = Depends(get_db),
    current_user: User = Depends(require_role(UserRole.admin))
):
    if db.query(User).filter(User.login == login).first():
        raise HTTPException(status_code=400, detail="Bu login band")
    if role not in ["cashier", "waiter"]:
        raise HTTPException(status_code=400, detail="Rol: cashier yoki waiter")
    worker = User(
        tenant_id=current_user.tenant_id, full_name=full_name, login=login,
        hashed_password=hash_password(password), role=role, is_active=True
    )
    db.add(worker)
    db.commit()
    db.refresh(worker)
    return {"id": worker.id, "full_name": worker.full_name, "login": worker.login, "role": worker.role, "is_active": worker.is_active}


@app.patch("/workers/{worker_id}")
def update_worker(
    worker_id: int, full_name: str = None, role: str = None,
    db: Session = Depends(get_db),
    current_user: User = Depends(require_role(UserRole.admin))
):
    worker = db.query(User).filter(
        User.id == worker_id, User.tenant_id == current_user.tenant_id
    ).first()
    if not worker:
        raise HTTPException(status_code=404, detail="Topilmadi")
    if full_name: worker.full_name = full_name
    if role and role in ["cashier", "waiter"]: worker.role = role
    db.commit()
    return {"id": worker.id, "full_name": worker.full_name, "role": worker.role, "is_active": worker.is_active}


@app.delete("/workers/{worker_id}")
def delete_worker(
    worker_id: int,
    db: Session = Depends(get_db),
    current_user: User = Depends(require_role(UserRole.admin))
):
    worker = db.query(User).filter(
        User.id == worker_id, User.tenant_id == current_user.tenant_id
    ).first()
    if not worker:
        raise HTTPException(status_code=404, detail="Topilmadi")
    db.delete(worker)
    db.commit()
    return {"message": "O'chirildi"}
