from sqlalchemy import Column, Integer, String, Float, Boolean, DateTime, ForeignKey, Enum, Text
from sqlalchemy.orm import relationship
from sqlalchemy.sql import func
import enum
from database import Base

# ── ENUMS ──
class UserRole(str, enum.Enum):
    super_admin = "super_admin"
    admin = "admin"
    cashier = "cashier"
    waiter = "waiter"

class OrderType(str, enum.Enum):
    dine_in = "dine_in"
    takeaway = "takeaway"
    bot = "bot"

class OrderStatus(str, enum.Enum):
    new = "new"
    confirmed = "confirmed"
    preparing = "preparing"
    ready = "ready"
    paid = "paid"
    closed = "closed"
    cancelled = "cancelled"

class RoomStatus(str, enum.Enum):
    free = "free"
    busy = "busy"
    reserved = "reserved"

class PaymentMethod(str, enum.Enum):
    cash = "cash"
    card = "card"
    click = "click"
    payme = "payme"

class UnitType(str, enum.Enum):
    piece = "piece"
    kg = "kg"
    gram = "gram"
    liter = "liter"
    ml = "ml"
    portion = "portion"

# ── TENANTS ──
class Tenant(Base):
    __tablename__ = "tenants"
    id = Column(Integer, primary_key=True)
    name = Column(String(100), nullable=False)
    type = Column(String(50))
    address = Column(String(200))
    phone = Column(String(20))
    logo_url = Column(String(300))
    is_active = Column(Boolean, default=True)
    created_at = Column(DateTime, server_default=func.now())

    users = relationship("User", back_populates="tenant")
    rooms = relationship("Room", back_populates="tenant")
    categories = relationship("Category", back_populates="tenant")
    products = relationship("Product", back_populates="tenant")
    orders = relationship("Order", back_populates="tenant")
    bot_config = relationship("BotConfig", back_populates="tenant", uselist=False)

# ── USERS ──
class User(Base):
    __tablename__ = "users"
    id = Column(Integer, primary_key=True)
    tenant_id = Column(Integer, ForeignKey("tenants.id"), nullable=True)
    full_name = Column(String(100))
    login = Column(String(50), unique=True)
    hashed_password = Column(String(200))
    role = Column(Enum(UserRole))
    is_active = Column(Boolean, default=True)
    created_at = Column(DateTime, server_default=func.now())

    tenant = relationship("Tenant", back_populates="users")

# ── BOT CONFIG ──
class BotConfig(Base):
    __tablename__ = "bot_configs"
    id = Column(Integer, primary_key=True)
    tenant_id = Column(Integer, ForeignKey("tenants.id"), unique=True)
    bot_token = Column(String(200))
    bot_username = Column(String(100))
    welcome_message = Column(Text, default="Xush kelibsiz!")
    work_start = Column(String(5), default="09:00")
    work_end = Column(String(5), default="23:00")
    min_order_amount = Column(Float, default=0)
    language = Column(String(5), default="uz")
    is_active = Column(Boolean, default=False)
    admin_chat_id = Column(String(50), nullable=True)  # Admin Telegram chat_id
    created_at = Column(DateTime, server_default=func.now())

    tenant = relationship("Tenant", back_populates="bot_config")

# ── ROOMS ──
class Room(Base):
    __tablename__ = "rooms"
    id = Column(Integer, primary_key=True)
    tenant_id = Column(Integer, ForeignKey("tenants.id"))
    name = Column(String(100))
    capacity = Column(Integer)
    room_type = Column(String(50), default="standard")
    status = Column(Enum(RoomStatus), default=RoomStatus.free)
    is_active = Column(Boolean, default=True)

    tenant = relationship("Tenant", back_populates="rooms")
    orders = relationship("Order", back_populates="room")

# ── CATEGORIES ──
class Category(Base):
    __tablename__ = "categories"
    id = Column(Integer, primary_key=True)
    tenant_id = Column(Integer, ForeignKey("tenants.id"))
    name = Column(String(100))
    sort_order = Column(Integer, default=0)
    is_active = Column(Boolean, default=True)

    tenant = relationship("Tenant", back_populates="categories")
    products = relationship("Product", back_populates="category")

# ── PRODUCTS ──
class Product(Base):
    __tablename__ = "products"
    id = Column(Integer, primary_key=True)
    tenant_id = Column(Integer, ForeignKey("tenants.id"))
    category_id = Column(Integer, ForeignKey("categories.id"))
    name = Column(String(100))
    unit = Column(Enum(UnitType))
    price = Column(Float)
    image_url = Column(String(300))
    deduct_inventory = Column(Boolean, default=True)
    is_active = Column(Boolean, default=True)
    created_at = Column(DateTime, server_default=func.now())

    tenant = relationship("Tenant", back_populates="products")
    category = relationship("Category", back_populates="products")
    inventory = relationship("Inventory", back_populates="product")
    order_items = relationship("OrderItem", back_populates="product")

# ── INVENTORY ──
class Inventory(Base):
    __tablename__ = "inventory"
    id = Column(Integer, primary_key=True)
    tenant_id = Column(Integer, ForeignKey("tenants.id"))
    product_id = Column(Integer, ForeignKey("products.id"))
    quantity = Column(Float, default=0)
    min_quantity = Column(Float, default=0)
    unit_cost = Column(Float, default=0)
    supplier = Column(String(100))
    updated_at = Column(DateTime, server_default=func.now(), onupdate=func.now())

    product = relationship("Product", back_populates="inventory")

# ── ORDERS ──
class Order(Base):
    __tablename__ = "orders"
    id = Column(Integer, primary_key=True)
    tenant_id = Column(Integer, ForeignKey("tenants.id"))
    room_id = Column(Integer, ForeignKey("rooms.id"), nullable=True)
    order_type = Column(Enum(OrderType))
    status = Column(Enum(OrderStatus), default=OrderStatus.new)
    customer_phone = Column(String(20))
    customer_name = Column(String(100))
    note = Column(Text)
    persons_count = Column(Integer, default=1)
    subtotal = Column(Float, default=0)
    discount = Column(Float, default=0)
    total = Column(Float, default=0)
    reservation_time = Column(DateTime, nullable=True)
    created_at = Column(DateTime, server_default=func.now())
    bot_user_id = Column(Integer, ForeignKey("bot_users.id"), nullable=True)

    tenant = relationship("Tenant", back_populates="orders")
    room = relationship("Room", back_populates="orders")
    items = relationship("OrderItem", back_populates="order")
    payment = relationship("Payment", back_populates="order", uselist=False)

# ── ORDER ITEMS ──
class OrderItem(Base):
    __tablename__ = "order_items"
    id = Column(Integer, primary_key=True)
    order_id = Column(Integer, ForeignKey("orders.id"))
    product_id = Column(Integer, ForeignKey("products.id"))
    quantity = Column(Float)
    unit_price = Column(Float)
    total_price = Column(Float)

    order = relationship("Order", back_populates="items")
    product = relationship("Product", back_populates="order_items")

# ── PAYMENTS ──
class Payment(Base):
    __tablename__ = "payments"
    id = Column(Integer, primary_key=True)
    order_id = Column(Integer, ForeignKey("orders.id"), unique=True)
    method = Column(Enum(PaymentMethod))
    amount = Column(Float)
    paid_at = Column(DateTime, server_default=func.now())

    order = relationship("Order", back_populates="payment")

# ── BOT USERS ──
class BotUser(Base):
    __tablename__ = "bot_users"
    id = Column(Integer, primary_key=True)
    tenant_id = Column(Integer, ForeignKey("tenants.id"))
    chat_id = Column(String(50))
    full_name = Column(String(100))
    phone = Column(String(20))
    username = Column(String(100))
    created_at = Column(DateTime, server_default=func.now())
