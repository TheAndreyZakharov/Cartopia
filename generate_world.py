import json
import os
import math
import random
from shapely.geometry import Polygon, Point, LineString
from shapely.ops import polygonize
from amulet import load_level
from amulet.api.block import Block
import requests
import rasterio
import math

# === Настройки ===
Y_BASE = -60
BUILDING_HEIGHT = 5
BLOCK_VERSION = ("java", (1, 20, 1))
DIMENSION = "minecraft:overworld"

ROAD_MATERIALS = {
    "motorway": ("gray_concrete", 15),
    "trunk": ("gray_concrete", 15),
    "primary": ("gray_concrete", 15),
    "secondary": ("gray_concrete", 15),
    "tertiary": ("gray_concrete", 15),
    "residential": ("gray_concrete", 15),
    "unclassified": ("stone", 6),
    "service": ("stone", 5),
    "footway": ("cobblestone", 4),
    "path": ("cobblestone", 4),
    "cycleway": ("cobblestone", 4),
    "pedestrian": ("cobblestone", 4),
    "track": ("dirt", 2),
    "rail": ("rail", 1),
}

ZONE_MATERIALS = {
    "natural=water": "water",
    "natural=sand": "sandstone",
    "natural=beach": "sandstone",
    "natural=bare_rock": "stone",
    "natural=grassland": "grass_block",
    "landuse=forest": "grass_block",
    "natural=wood": "grass_block",
    "landuse=residential": "grass_block",
    "landuse=industrial": "stone",
    "landuse=farmland": "farmland",
    "landuse=meadow": "grass_block",
    "leisure=park": "grass_block",
    "natural=desert": "sandstone",
    "natural=jungle": "grass_block",
    "natural=swamp": "muddy_mangrove_roots",
    "natural=savanna": "grass_block",
    "natural=snow": "snow_block",

    "amenity=school": "grass_block",
    "amenity=kindergarten": "grass_block",
    "amenity=hospital": "stone",
    "amenity=university": "grass_block",
    "amenity=college": "grass_block",
    "landuse=cemetery": "grass_block",
    "amenity=prison": "stone",
    "amenity=fire_station": "grass_block",
    "amenity=police": "grass_block",
    "leisure=playground": "grass_block",
    "amenity=sports_centre": "grass_block",
    "leisure=sports_centre": "grass_block",
    "leisure=stadium": "grass_block",
    "leisure=pitch": "grass_block",
    "leisure=garden": "grass_block",
    "amenity=parking": "stone",
    "amenity=grave_yard": "grass_block",
    "landuse=recreation_ground": "grass_block",
    "amenity=marketplace": "grass_block",

}

MC_TREES = [
    "oak", "birch", "spruce", "jungle", "acacia", "dark_oak", "cherry", "mangrove", "bamboo", "cactus"
]

ZONE_TREES = {
    "natural=wood": ["oak", "birch", "spruce", "dark_oak", "cherry"],
    "natural=wood+leaf_type=needleleaved": ["spruce"],
    "natural=wood+leaf_type=broadleaved": ["oak", "birch", "cherry"],
    "natural=wood+leaf_type=mixed": ["oak", "birch", "spruce", "cherry", "dark_oak"],
    "natural=desert": ["cactus"],
    "natural=beach": ["bamboo"],
    "natural=jungle": ["jungle", "bamboo"],
    "natural=swamp": ["mangrove"],
    "natural=savanna": ["acacia"],
    "leisure=park": ["oak", "birch", "cherry"],
    "landuse=meadow": ["oak", "birch"],
}

GRASS_PLANTS = ["grass", "fern", "tall_grass", "large_fern"]
FLOWERS = [
    "dandelion", "poppy", "blue_orchid", "allium", "azure_bluet", "red_tulip", "orange_tulip",
    "white_tulip", "pink_tulip", "oxeye_daisy", "cornflower", "lily_of_the_valley",
    "sunflower", "rose_bush", "peony", "lilac"
]
BUSHES = ["sweet_berry_bush"]

FENCED_ZONE_PREFIXES = [
    "leisure=park",
    "amenity=school",
    "amenity=kindergarten",
    "amenity=hospital",
    "amenity=university",
    "amenity=college",
    "landuse=cemetery",
    "amenity=prison",
    "amenity=fire_station",
    "amenity=police",
    "leisure=playground",
    "amenity=sports_centre",
    "leisure=sports_centre",
    "leisure=stadium",
    "leisure=pitch",
    "leisure=garden",
    "amenity=parking",
    "amenity=grave_yard",
    "landuse=recreation_ground",
    "amenity=marketplace",
]


def get_height_map_from_dem_tif(
    tif_path, bbox_south, bbox_west, bbox_north, bbox_east,
    min_x, max_x, min_z, max_z, latlng_to_block_coords, block_coords_to_latlng
):
    height_map = {}
    with rasterio.open(tif_path) as src:
        band = src.read(1)
        for x in range(min_x, max_x + 1):
            for z in range(min_z, max_z + 1):
                lat, lon = block_coords_to_latlng(x, z)
                try:
                    row, col = src.index(lon, lat)
                    value = band[row, col]
                    if value == src.nodata or math.isnan(value):
                        # тут вместо value=0 ищем среднее не-NaN из соседей
                        neigh = []
                        for dx, dz in [(-1,0),(1,0),(0,-1),(0,1)]:
                            nx, nz = x+dx, z+dz
                            try:
                                nlat, nlon = block_coords_to_latlng(nx, nz)
                                nrow, ncol = src.index(nlon, nlat)
                                nval = band[nrow, ncol]
                                if not math.isnan(nval) and nval != src.nodata:
                                    neigh.append(nval)
                            except Exception:
                                continue
                        if neigh:
                            value = sum(neigh)/len(neigh)
                        else:
                            value = 0
                except Exception:
                    value = 0
                height_map[(x, z)] = value
    return height_map


def set_block(x, y, z, block):
    global error_count
    try:
        level.set_version_block(x, y, z, DIMENSION, BLOCK_VERSION, block)
    except Exception as e:
        error_count += 1
        if error_count < 10:
            print(f"⚠️ Ошибка при установке блока ({x},{y},{z}):", str(e))

# ... все импорты и глобальные переменные

print("📄 Загрузка координат...")
with open("coords.json") as f:
    data = json.load(f)

center_lat = data["center"]["lat"]
center_lng = data["center"]["lng"]
size = data["sizeMeters"]
player = data.get("player")
features = data.get("features", {}).get("elements", [])

print(f"📍 Центр: ({center_lat}, {center_lng}), Размер: {size} м")
if player:
    print(f"👤 Игрок в мире: x={player['x']}, z={player['z']}")

possible_paths = [
    os.path.abspath("run/saves/Cartopia"),
    os.path.expanduser("~/Library/Application Support/minecraft/saves/Cartopia")
]
world_path = next((p for p in possible_paths if os.path.exists(p)), None)
if not world_path:
    print("❌ Мир 'Cartopia' не найден.")
    exit(1)

print("🗺️ Открытие мира:", world_path)
try:
    level = load_level(world_path)
    print("✅ Мир загружен.")
except Exception as e:
    print("❌ Ошибка при загрузке:", e)
    exit(1)

bbox = data.get("bbox")
if bbox:
    bbox_south = bbox["south"]
    bbox_north = bbox["north"]
    bbox_west = bbox["west"]
    bbox_east = bbox["east"]
else:
    bbox_south = center_lat - (size / 2) / 111320
    bbox_north = center_lat + (size / 2) / 111320
    bbox_west = center_lng - (size / 2) / (111320 * math.cos(math.radians(center_lat)))
    bbox_east = center_lng + (size / 2) / (111320 * math.cos(math.radians(center_lat)))

if player:
    center_x = int(player["x"])
    center_z = int(player["z"])
else:
    center_x, center_z = 0, 0

print(f"🧭 Центр в Minecraft координатах: x={center_x}, z={center_z}")

def latlng_to_block_coords(lat, lng):
    dx = (lng - center_lng) / (bbox_east - bbox_west) * size
    dz = (lat - center_lat) / (bbox_south - bbox_north) * size
    x = int(round(center_x + dx))
    z = int(round(center_z + dz))
    return x, z

def block_coords_to_latlng(x, z):
    dx = (x - center_x) / size * (bbox_east - bbox_west)
    dz = (z - center_z) / size * (bbox_south - bbox_north)
    lng = center_lng + dx
    lat = center_lat + dz
    return lat, lng

node_coords = {}
for el in features:
    if el["type"] == "node":
        node_coords[el["id"]] = latlng_to_block_coords(el["lat"], el["lon"])

error_count = 0

# --- ДОБАВЛЯЕМ ВЫЧИСЛЕНИЕ bbox_min_x... ДО того как использовать get_y_for_block ---

bbox_min_x, bbox_min_z = latlng_to_block_coords(bbox_south, bbox_west)
bbox_max_x, bbox_max_z = latlng_to_block_coords(bbox_north, bbox_east)
min_x, max_x = sorted([bbox_min_x, bbox_max_x])
min_z, max_z = sorted([bbox_min_z, bbox_max_z])

# ---- ВОТ ТУТ ВЫЧИСЛЯЕМ height_map и min_elevation ----
print("🗺️ Загружаем рельеф из DEM (dem.tif)...")
if not os.path.exists("dem.tif"):
    print("❌ DEM файл не найден!")
    exit(1)
height_map = get_height_map_from_dem_tif(
    "dem.tif",
    bbox_south, bbox_west, bbox_north, bbox_east,
    min_x, max_x, min_z, max_z,
    latlng_to_block_coords, block_coords_to_latlng
)


min_elevation = min(height_map.values())
print(f"Минимальная высота на участке: {min_elevation} м")

# ---- ТОЛЬКО ПОСЛЕ ЭТОГО объявляем функцию! ----
def get_y_for_block(x, z):
    if (x, z) in height_map:
        elev = height_map[(x, z)]
    else:
        # Собираем высоты ближайших известных соседей (по квадрату 1–3 блока вокруг)
        neighbors = []
        for r in range(1, 4):  # ищем радиусом до 3
            for dx in range(-r, r+1):
                for dz in range(-r, r+1):
                    nx, nz = x + dx, z + dz
                    if (nx, nz) in height_map:
                        neighbors.append(height_map[(nx, nz)])
            if neighbors:
                break  # нашли хоть что-то — хватит расширять радиус
        if neighbors:
            elev = sum(neighbors) / len(neighbors)
        else:
            elev = min_elevation  # если не нашли никого вообще
    y = Y_BASE + int(round(elev - min_elevation))
    return y

# --- 1. Подложка: всё травой, но по зоне! ---

surface_material_map = {}
for x in range(min_x, max_x+1):
    for z in range(min_z, max_z+1):
        surface_material_map[(x, z)] = "grass_block"

for feature in features:
    tags = feature.get("tags", {})
    key = None
    if "natural" in tags:
        key = f"natural={tags['natural']}"
        if "leaf_type" in tags:
            key += f"+leaf_type={tags['leaf_type']}"
    elif "landuse" in tags:
        key = f"landuse={tags['landuse']}"
    elif "leisure" in tags:
        key = f"leisure={tags['leisure']}"
    elif "amenity" in tags:
        key = f"amenity={tags['amenity']}"
    if key not in ZONE_MATERIALS:
        continue
    nodes = [node_coords.get(nid) for nid in feature.get("nodes", []) if nid in node_coords]
    if not nodes or nodes[0] != nodes[-1]:
        continue
    polygon = Polygon(nodes)
    block_name = ZONE_MATERIALS[key]
    min_xx, min_zz, max_xx, max_zz = map(int, map(round, polygon.bounds))
    for x in range(min_xx, max_xx+1):
        for z in range(min_zz, max_zz+1):
            if polygon.contains(Point(x, z)):
                surface_material_map[(x, z)] = block_name

print("⛰️ Формируем карту высот...")
terrain_y = {}
for x in range(min_x, max_x + 1):
    for z in range(min_z, max_z + 1):
        terrain_y[(x, z)] = get_y_for_block(x, z)

print("🔧 Ограничиваем перепады высот (не больше 3 блоков между соседями)...")

max_height_diff = 3  # МАКСИМАЛЬНО ДОПУСТИМАЯ разница между соседями

changed = True
while changed:
    changed = False
    for x in range(min_x, max_x + 1):
        for z in range(min_z, max_z + 1):
            y = terrain_y[(x, z)]
            for dx, dz in [(-1,0), (1,0), (0,-1), (0,1)]:
                nx, nz = x + dx, z + dz
                if (nx, nz) not in terrain_y:
                    continue
                ny = terrain_y[(nx, nz)]
                if abs(y - ny) > max_height_diff:
                    if y > ny:
                        terrain_y[(x, z)] = ny + max_height_diff
                    else:
                        terrain_y[(nx, nz)] = y + max_height_diff
                    changed = True

print("🪜 Делаем лесенку для ЛЮБОЙ поверхности (ступенька до 3 блоков)")
dxz = []
for d in range(1, 4):  # до 3 блоков
    dxz += [(-d,0), (d,0), (0,-d), (0,d)]

changed = True
while changed:
    changed = False
    for x in range(min_x, max_x + 1):
        for z in range(min_z, max_z + 1):
            y = terrain_y[(x, z)]
            for dx, dz in dxz:
                nx, nz = x + dx, z + dz
                if (nx, nz) not in terrain_y:
                    continue
                ny = terrain_y[(nx, nz)]
                dist = max(abs(dx), abs(dz))
                # Проверяем разницу между соседями на расстоянии 1,2,3
                max_allowed_diff = dist  # Можно сделать и просто 1 — тогда будет ступенька шириной 3
                if abs(y - ny) > 1:
                    # Чтобы перепад между (x,z) и (nx,nz) был не больше 1 на 3 блока!
                    # Нужно "выравнивать" средние клетки между ними
                    for i in range(1, dist):
                        mx = x + (dx // dist) * i
                        mz = z + (dz // dist) * i
                        if (mx, mz) not in terrain_y:
                            continue
                        avg = (y * (dist - i) + ny * i) // dist
                        if abs(terrain_y[(mx, mz)] - avg) > 1:
                            terrain_y[(mx, mz)] = avg
                            changed = True
                    # И можно "обрезать" края если сильно выпирают
                    if y - ny > 1:
                        terrain_y[(x, z)] = ny + 1
                        changed = True
                    elif ny - y > 1:
                        terrain_y[(nx, nz)] = y + 1
                        changed = True


print("🌎 Ставим блоки с нужным материалом строго по выровненной поверхности...")
for x in range(min_x, max_x + 1):
    for z in range(min_z, max_z + 1):
        y = terrain_y[(x, z)]
        blockname = surface_material_map.get((x, z), "grass_block")
        # 1. Ставим нужный блок на правильной высоте
        set_block(x, y, z, Block(namespace="minecraft", base_name=blockname))
        # 2. Всё что выше — чистим (если вдруг что-то там осталось)
        for y_above in range(y+1, Y_BASE+100):  # 100 — запас по высоте мира
            set_block(x, y_above, z, Block(namespace="minecraft", base_name="air"))
        # 3. Всё что ниже — тоже чистим (опционально)
        for y_below in range(Y_BASE, y):
            set_block(x, y_below, z, Block(namespace="minecraft", base_name="air"))


def set_plant(x, y, z, plant):
    if plant in GRASS_PLANTS + FLOWERS + BUSHES:
        set_block(x, y, z, Block(namespace="minecraft", base_name=plant))
    elif plant == "sugar_cane":
        for h in range(random.randint(1, 3)):
            set_block(x, y+h, z, Block(namespace="minecraft", base_name="sugar_cane"))
    elif plant == "bamboo":
        for h in range(random.randint(4, 8)):
            set_block(x, y+h, z, Block(namespace="minecraft", base_name="bamboo"))

def set_tree(x, y, z, tree_type):
    log = f"{tree_type}_log"
    leaves = f"{tree_type}_leaves"
    height = random.randint(3, 8)
    if tree_type == "cactus":
        for i in range(height):
            set_block(x, y+i, z, Block(namespace="minecraft", base_name="cactus"))
    elif tree_type == "bamboo":
        set_plant(x, y, z, "bamboo")
    elif tree_type == "mangrove":
        set_block(x, y, z, Block(namespace="minecraft", base_name="mangrove_log"))
        for dx in [-1, 0, 1]:
            for dz in [-1, 0, 1]:
                set_block(x+dx, y+2, z+dz, Block(namespace="minecraft", base_name="mangrove_leaves"))
        set_block(x, y+3, z, Block(namespace="minecraft", base_name="mangrove_leaves"))
    elif tree_type in MC_TREES:
        # Ставим ствол высотой height
        for i in range(height):
            set_block(x, y+i, z, Block(namespace="minecraft", base_name=log))
        # Простая крона (можно усложнить)
        for dx in [-2, -1, 0, 1, 2]:
            for dz in [-2, -1, 0, 1, 2]:
                dist = abs(dx) + abs(dz)
                if dist <= 3:
                    set_block(x+dx, y+height, z+dz, Block(namespace="minecraft", base_name=leaves))
        set_block(x, y+height+1, z, Block(namespace="minecraft", base_name=leaves))
    elif isinstance(tree_type, list):
        set_tree(x, y, z, random.choice(tree_type))

def get_building_height(tags):
    """
    Если есть 'building:levels', возвращает этажность*3 (1 этаж = 3 блока), иначе — 6 блоков.
    """
    if "building:levels" in tags:
        try:
            levels = float(tags["building:levels"])
            return max(3, int(round(levels * 3)))
        except Exception:
            pass
    return 6  # по умолчанию 2 этажа, если не указано


# --- 2. Все зоны поверх подложки (Y_BASE)
print("🌱 Заливаем зоны...")
zone_polygons = []
for feature in features:
    tags = feature.get("tags", {})
    key = None
    if "natural" in tags:
        key = f"natural={tags['natural']}"
        if "leaf_type" in tags:
            key += f"+leaf_type={tags['leaf_type']}"
    elif "landuse" in tags:
        key = f"landuse={tags['landuse']}"
    elif "leisure" in tags:
        key = f"leisure={tags['leisure']}"
    elif "amenity" in tags:
        key = f"amenity={tags['amenity']}"
    if key not in ZONE_MATERIALS:
        continue
    nodes = [node_coords.get(nid) for nid in feature.get("nodes", []) if nid in node_coords]
    if not nodes or nodes[0] != nodes[-1]:
        continue
    polygon = Polygon(nodes)
    zone_polygons.append((polygon, key))
    block_name = ZONE_MATERIALS[key]
    block = Block(namespace="minecraft", base_name=block_name)
    min_x, min_z, max_x, max_z = map(int, map(round, polygon.bounds))
    for x in range(min_x, max_x+1):
        for z in range(min_z, max_z+1):
            if polygon.contains(Point(x, z)):
                y = terrain_y.get((x, z), Y_BASE)
                set_block(x, y, z, block)

# --- 3. Дороги и рельсы (тоже на Y_BASE)
print("🛣️ Генерация дорог и рельсов...")
def bresenham_line(x0, z0, x1, z1):
    """Генерирует все координаты по линии между (x0, z0) и (x1, z1)"""
    points = []
    dx = abs(x1 - x0)
    dz = abs(z1 - z0)
    x, z = x0, z0
    sx = 1 if x0 < x1 else -1
    sz = 1 if z0 < z1 else -1
    if dx > dz:
        err = dx // 2
        while x != x1:
            points.append((x, z))
            err -= dz
            if err < 0:
                z += sz
                err += dx
            x += sx
        points.append((x, z))
    else:
        err = dz // 2
        while z != z1:
            points.append((x, z))
            err -= dx
            if err < 0:
                x += sx
                err += dz
            z += sz
        points.append((x, z))
    return points

def get_rail_shape(x1, z1, x2, z2, x3, z3):
    # определяем форму рельсы по соседям (x2,z2 - это наш блок)
    dx1, dz1 = x2-x1, z2-z1
    dx2, dz2 = x3-x2, z3-z2
    dirs = {(0,1):'south', (0,-1):'north', (1,0):'east', (-1,0):'west'}
    if (dx1, dz1) == (dx2, dz2):  # прямая
        if abs(dx1) > abs(dz1):
            return 'east_west'
        else:
            return 'north_south'
    # Повороты: 4 возможных угла
    if (dx1, dz1) == (0,1) and (dx2, dz2) == (1,0):
        return 'south_east'
    if (dx1, dz1) == (1,0) and (dx2, dz2) == (0,1):
        return 'south_east'
    if (dx1, dz1) == (0,1) and (dx2, dz2) == (-1,0):
        return 'south_west'
    if (dx1, dz1) == (-1,0) and (dx2, dz2) == (0,1):
        return 'south_west'
    if (dx1, dz1) == (0,-1) and (dx2, dz2) == (1,0):
        return 'north_east'
    if (dx1, dz1) == (1,0) and (dx2, dz2) == (0,-1):
        return 'north_east'
    if (dx1, dz1) == (0,-1) and (dx2, dz2) == (-1,0):
        return 'north_west'
    if (dx1, dz1) == (-1,0) and (dx2, dz2) == (0,-1):
        return 'north_west'
    # fallback
    if abs(dx1) > abs(dz1):
        return 'east_west'
    else:
        return 'north_south'

road_blocks = set()
rail_blocks = set()
for feature in features:
    tags = feature.get("tags", {})
    nodes = [node_coords.get(nid) for nid in feature.get("nodes", []) if nid in node_coords]
    if not nodes or len(nodes) < 2:
        continue
    if "highway" in tags or "railway" in tags:
        material, width = ROAD_MATERIALS.get(tags.get("highway") or tags.get("railway"), ("stone", 3))
        block = Block(namespace="minecraft", base_name=material)
        if tags.get("railway") in ("rail", "tram", "light_rail"):
            # Класть подложку (гравий/каменку) — как у тебя было
            for i in range(1, len(nodes)):
                (x1, z1), (x2, z2) = nodes[i-1], nodes[i]
                line = bresenham_line(x1, z1, x2, z2)
                for (x, z) in line:
                    y = terrain_y.get((x, z), Y_BASE)
                    set_block(x, y, z, Block(namespace="minecraft", base_name="cobblestone"))
                    rail_blocks.add((x, z))
            # Вторым проходом — класть рельсы ПОВЕРХ всей линии
            for i in range(1, len(nodes)):
                (x1, z1), (x2, z2) = nodes[i-1], nodes[i]
                line = bresenham_line(x1, z1, x2, z2)
                for (x, z) in line:
                    y = terrain_y.get((x, z), Y_BASE)
                    set_block(x, y+1, z, Block(namespace="minecraft", base_name="rail"))
        elif tags.get("railway") == "subway":
            # Subway — не строим!
            continue
        else:
            # Все обычные дороги
            for (x1, z1), (x2, z2) in zip(nodes, nodes[1:]):
                dx = x2 - x1
                dz = z2 - z1
                dist = max(abs(dx), abs(dz))
                if dist == 0:
                    continue
                for i in range(dist + 1):
                    x = round(x1 + dx * i / dist)
                    z = round(z1 + dz * i / dist)
                    for w in range(-width // 2, width // 2 + 1):
                        if abs(dx) > abs(dz):
                            xx, zz = x, z + w
                        else:
                            xx, zz = x + w, z
                        y = terrain_y.get((xx, zz), Y_BASE)
                        set_block(xx, y, zz, block)
                        road_blocks.add((xx, zz))



# --- 4. Растения и деревья (Y_BASE+1)
print("🌳 Генерация растительности и декора...")
for polygon, key in zone_polygons:
    block_name = ZONE_MATERIALS[key]
    min_x, min_z, max_x, max_z = map(int, map(round, polygon.bounds))
    for x in range(min_x, max_x+1):
        for z in range(min_z, max_z+1):
            if polygon.contains(Point(x, z)):
                if key in ["leisure=park", "landuse=meadow", "natural=grassland"]:
                    if random.random() < 0.13:
                        y = terrain_y.get((x, z), Y_BASE)
                        set_plant(x, y+1, z, random.choice(GRASS_PLANTS + FLOWERS))
                if key in ["leisure=park", "landuse=meadow", "natural=wood", "natural=jungle"]:
                    if random.random() < 0.03:
                        set_plant(x, y+1, z, "sweet_berry_bush")
                if key == "natural=jungle" and random.random() < 0.08:
                    set_plant(x, y+1, z, "bamboo")
                if block_name == "water":
                    for dx, dz in [(-1,0),(1,0),(0,-1),(0,1)]:
                        nx, nz = x+dx, z+dz
                        if random.random() < 0.005:
                            y = terrain_y.get((x, z), Y_BASE)
                            set_plant(nx, y+1, nz, "sugar_cane")
    tree_types = ZONE_TREES.get(key)
    if tree_types:
        for tx in range(min_x, max_x+1, 3):  # плотнее сетка
            for tz in range(min_z, max_z+1, 3):
                if polygon.contains(Point(tx, tz)):
                    ttype = random.choice(tree_types)
                    y = get_y_for_block(tx, tz)
                    # вишня — крайне редкая
                    if ttype == "cherry":
                        if random.random() < 0.07:
                            set_tree(tx, y+1, tz, ttype)
                    else:
                        if random.random() < 0.45:
                            set_tree(tx, y+1, tz, ttype)

# --- 5. Здания (Y_BASE+1 и выше, двери на Y_BASE+1)
print("🏠 Генерация зданий и дверей...")
relations = []
ways = []
for feature in features:
    if feature["type"] == "way":
        ways.append(feature)
    elif feature["type"] == "relation":
        relations.append(feature)
        continue

    tags = feature.get("tags", {})
    nodes = [node_coords.get(nid) for nid in feature.get("nodes", []) if nid in node_coords]

    if not nodes or len(nodes) < 2:
        continue

    if "building" in tags and feature.get("nodes"):
        if nodes[0] != nodes[-1]:
            nodes.append(nodes[0])
        try:
            polygon = Polygon(nodes)
            if not polygon.is_valid or polygon.is_empty:
                polygon = polygon.buffer(0)
            if not polygon.is_valid or polygon.is_empty:
                poly_list = list(polygonize(LineString(nodes)))
                if poly_list:
                    polygon = poly_list[0]
            if not polygon.is_valid or polygon.is_empty:
                print("❌ Не удалось построить валидный полигон для здания, пропускаем.")
                continue
            min_x, min_z, max_x, max_z = map(int, map(round, polygon.bounds))
            height = get_building_height(tags)
            building_ground_heights = []
            for x in range(min_x, max_x + 1):
                for z in range(min_z, max_z + 1):
                    if polygon.contains(Point(x, z)):
                        y = terrain_y.get((x, z), Y_BASE)
                        building_ground_heights.append(y)

            if not building_ground_heights:
                continue  # пропускаем пустое

            max_ground_y = max(building_ground_heights)

            for x in range(min_x, max_x + 1):
                for z in range(min_z, max_z + 1):
                    if polygon.contains(Point(x, z)):
                        ground_y = terrain_y.get((x, z), Y_BASE)
                        # Достраиваем "фундамент" до земли (чтобы не висело)
                        for fy in range(ground_y + 1, max_ground_y + 1):
                            set_block(x, fy, z, Block(namespace="minecraft", base_name="bricks"))
                        # Теперь строим само здание (от max_ground_y вверх)
                        for dy in range(1, 1 + height):
                            set_block(x, max_ground_y + dy, z, Block(namespace="minecraft", base_name="bricks"))
                        # Крыша
                        set_block(x, max_ground_y + 1 + height, z, Block(namespace="minecraft", base_name="stone_slab"))
            entrances = []
            for node_id in feature.get("nodes", []):
                node = next((n for n in features if n.get("id") == node_id and n["type"] == "node"), None)
                if node and "entrance" in node.get("tags", {}):
                    entrances.append(node_coords[node_id])
            for ex, ez in entrances:
                y = terrain_y.get((ex, ez), Y_BASE)
                set_block(ex, y+1, ez, Block(namespace="minecraft", base_name="spruce_door"))
        except Exception as e:
            error_count += 1
            if error_count < 5:
                print(f"⚠️ Ошибка при генерации здания: {e}")


# --- 6. Ограждение зон по периметру (Y_BASE+1)
def is_fenced_zone(key):
    return any(key.startswith(prefix) for prefix in FENCED_ZONE_PREFIXES)

print("🚧 Генерация заборов...")

# 1. Собираем все fence-точки:
fence_points = set()
for polygon, key in zone_polygons:
    if not is_fenced_zone(key):
        continue
    coords = [(int(round(x)), int(round(z))) for (x, z) in polygon.exterior.coords]
    for i in range(len(coords)-1):
        x0, z0 = coords[i]
        x1, z1 = coords[i+1]
        for x, z in bresenham_line(x0, z0, x1, z1):
            if (x, z) not in road_blocks and (x, z) not in rail_blocks:
                fence_points.add((x, z))

# 2. Ставим fence с нужными свойствами
for x, z in fence_points:
    y = terrain_y.get((x, z), Y_BASE)
    set_block(
        x, y+1, z,
        Block(namespace="minecraft", base_name="oak_fence")
    )



# --- 7. Мультиполигоны (relation building)
for rel in relations:
    tags = rel.get("tags", {})
    if "building" not in tags:
        continue
    if "members" not in rel:
        continue
    outers = []
    inners = []
    for member in rel["members"]:
        if member["type"] != "way":
            continue
        way_id = member["ref"]
        way = next((w for w in ways if w["id"] == way_id), None)
        if not way:
            continue
        coords = [node_coords.get(nid) for nid in way.get("nodes", []) if nid in node_coords]
        if not coords or any(c is None for c in coords):
            continue
        if coords[0] != coords[-1]:
            coords.append(coords[0])
        if member.get("role") == "outer":
            outers.append(coords)
        elif member.get("role") == "inner":
            inners.append(coords)
    for outer in outers:
        try:
            holes = []
            for inner in inners:
                inner_poly = Polygon(inner)
                outer_poly = Polygon(outer)
                if outer_poly.contains(inner_poly):
                    holes.append(inner)
            polygon = Polygon(outer, holes)
            if not polygon.is_valid:
                continue
            min_x, min_z, max_x, max_z = map(int, map(round, polygon.bounds))
            # Выбираем высоту по тегу (relation может быть не только residential, так что тоже проверяем)
            height = get_building_height(tags)


            building_ground_heights = []
            for x in range(min_x, max_x + 1):
                for z in range(min_z, max_z + 1):
                    if polygon.contains(Point(x, z)):
                        y = terrain_y.get((x, z), Y_BASE)
                        building_ground_heights.append(y)

            if not building_ground_heights:
                continue  # пропускаем пустое

            max_ground_y = max(building_ground_heights)

            for x in range(min_x, max_x + 1):
                for z in range(min_z, max_z + 1):
                    if polygon.contains(Point(x, z)):
                        ground_y = terrain_y.get((x, z), Y_BASE)
                        # Достраиваем "фундамент" до земли (чтобы не висело)
                        for fy in range(ground_y + 1, max_ground_y + 1):
                            set_block(x, fy, z, Block(namespace="minecraft", base_name="bricks"))
                        # Теперь строим само здание (от max_ground_y вверх)
                        for dy in range(1, 1 + height):
                            set_block(x, max_ground_y + dy, z, Block(namespace="minecraft", base_name="bricks"))
                        # Крыша
                        set_block(x, max_ground_y + 1 + height, z, Block(namespace="minecraft", base_name="stone_slab"))
        except Exception as e:
            error_count += 1
            if error_count < 5:
                print(f"⚠️ Ошибка при генерации здания с дырой (relation): {e}")


building_blocks = set()
beach_blocks = set()
for polygon, key in zone_polygons:
    block_name = ZONE_MATERIALS[key]
    min_xx, min_zz, max_xx, max_zz = map(int, map(round, polygon.bounds))
    for x in range(min_xx, max_xx + 1):
        for z in range(min_zz, max_zz + 1):
            if polygon.contains(Point(x, z)):
                # ищем здания
                if "building" in key:
                    building_blocks.add((x, z))
                # ищем пляжи/пески
                if key in ["natural=beach", "natural=sand", "natural=desert"]:
                    beach_blocks.add((x, z))


residential_blocks = set()
park_forest_blocks = set()

for polygon, key in zone_polygons:
    min_x, min_z, max_x, max_z = map(int, map(round, polygon.bounds))
    for x in range(min_x, max_x+1):
        for z in range(min_z, max_z+1):
            if not polygon.contains(Point(x, z)):
                continue
            if key in [
                "leisure=park", "landuse=meadow", "landuse=cemetery",
                "natural=wood", "landuse=forest", "natural=wood+leaf_type=needleleaved",
                "natural=wood+leaf_type=broadleaved", "natural=wood+leaf_type=mixed"
            ]:
                park_forest_blocks.add((x, z))
            elif key == "landuse=residential":
                residential_blocks.add((x, z))

empty_blocks = set()

# 1. Сбор всех клеток, которые ни в одной зоне:
global_min_x, global_min_z = latlng_to_block_coords(bbox_south, bbox_west)
global_max_x, global_max_z = latlng_to_block_coords(bbox_north, bbox_east)
global_min_x, global_max_x = sorted([global_min_x, global_max_x])
global_min_z, global_max_z = sorted([global_min_z, global_max_z])
for x in range(global_min_x, global_max_x+1):
    for z in range(global_min_z, global_max_z+1):
        if (x, z) not in park_forest_blocks and (x, z) not in residential_blocks:
            empty_blocks.add((x, z))

print("🌱 Сажаем траву, папоротники, цветы в лесах и парках...")
for (x, z) in park_forest_blocks:
    y = terrain_y.get((x, z), Y_BASE)
    block_below = level.get_block(x, y, z, DIMENSION)
    block_here = level.get_block(x, y+1, z, DIMENSION)
    if block_below.base_name != "grass_block" or block_here.base_name != "air":
        continue
    if (x, z) in building_blocks or (x, z) in road_blocks or (x, z) in rail_blocks or (x, z) in beach_blocks:
        continue

    # В лесах и лесопарках (все кроме чисто парка)
    if (x, z) in park_forest_blocks and (x, z) not in residential_blocks:
        # 50% — низкая трава, 20% — высокая трава/папоротник, 15% — цветы, 5% — куст
        r = random.random()
        if r < 0.50:
            y = terrain_y.get((x, z), Y_BASE)
            set_plant(x, y+1, z, random.choice(["grass", "fern"]))
        elif r < 0.70:
            y = terrain_y.get((x, z), Y_BASE)
            set_plant(x, y+1, z, random.choice(["tall_grass", "large_fern"]))
        elif r < 0.85:
            y = terrain_y.get((x, z), Y_BASE)
            set_plant(x, y+1, z, random.choice(FLOWERS))
        elif r < 0.90:
            y = terrain_y.get((x, z), Y_BASE)
            set_plant(x, y+1, z, "sweet_berry_bush")
    # В парках — только цветы (и редко кустики)
    elif (x, z) in park_forest_blocks:
        r = random.random()
        if r < 0.18:
            y = terrain_y.get((x, z), Y_BASE)
            set_plant(x, y+1, z, random.choice(FLOWERS))
        elif r < 0.22:
            y = terrain_y.get((x, z), Y_BASE)
            set_plant(x, y+1, z, "sweet_berry_bush")

print("🌳 Сажаем деревья в парках и лесах")
for (x, z) in park_forest_blocks:
    y = terrain_y.get((x, z), Y_BASE)
    block_below = level.get_block(x, y, z, DIMENSION)
    block_here = level.get_block(x, y+1, z, DIMENSION)
    if block_below.base_name != "grass_block" or block_here.base_name != "air":
        continue
    if (x, z) in building_blocks or (x, z) in road_blocks or (x, z) in rail_blocks or (x, z) in beach_blocks:
        continue
    if random.random() < 0.10:
        y = terrain_y.get((x, z), Y_BASE)
        set_tree(x, y+1, z, random.choice(["oak", "birch", "spruce", "acacia"]))

print("🌲 Сажаем деревья в жилых районах")
for (x, z) in residential_blocks:
    if (x, z) in park_forest_blocks:
        continue
    y = terrain_y.get((x, z), Y_BASE)
    block_below = level.get_block(x, y, z, DIMENSION)
    block_here = level.get_block(x, y+1, z, DIMENSION)
    if block_below.base_name != "grass_block" or block_here.base_name != "air":
        continue
    if (x, z) in building_blocks or (x, z) in road_blocks or (x, z) in rail_blocks or (x, z) in beach_blocks:
        continue
    if random.random() < 0.05:
        y = terrain_y.get((x, z), Y_BASE)
        set_tree(x, y+1, z, random.choice(["oak", "birch", "acacia"]))

print("🌿 Сажаем деревья вне всех зон")
for (x, z) in empty_blocks:
    y = terrain_y.get((x, z), Y_BASE)
    block_below = level.get_block(x, y, z, DIMENSION)
    block_here = level.get_block(x, y+1, z, DIMENSION)
    if block_below.base_name != "grass_block" or block_here.base_name != "air":
        continue
    if (x, z) in building_blocks or (x, z) in road_blocks or (x, z) in rail_blocks or (x, z) in beach_blocks:
        continue
    if random.random() < 0.05:
        y = terrain_y.get((x, z), Y_BASE)
        set_tree(x, y+1, z, random.choice(["oak", "birch"]))


if error_count:
    print(f"⚠️ Всего ошибок: {error_count}")

print("💾 Сохраняем...")
level.save()
level.close()
print("🎉 Генерация завершена.")
