import json
import os
import math
import random
from shapely.geometry import Polygon, Point, LineString
from shapely.ops import polygonize
from amulet import load_level
from amulet.api.level import World
from amulet.api.block import Block
from amulet.api.block_entity import BlockEntity
from amulet_nbt import CompoundTag, ListTag, IntTag, StringTag, NamedTag
import requests
import rasterio
from rasterio.windows import from_bounds
from collections import Counter
import copy
import geopandas as gpd
import time

# === Настройки ===
Y_BASE = -60
BUILDING_HEIGHT = 5
FLOOR_HEIGHT = 4
BLOCK_VERSION = ("java", (1, 20, 1))
DIMENSION = "minecraft:overworld"

start_time = time.time()
placed_blocks_count = 0

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

# --- LANDCOVER LEGEND.   ---
LANDCOVER_CLASS_TO_BLOCK = {
    210: "water",        # Water body
    220: "snow_block",   # Permanent ice and snow
    200: "sandstone",    # Bare areas (можно sandstone или sand)
    201: "grass_block",  # Consolidated bare areas
    202: "sandstone",    # Unconsolidated bare areas (опять песок/пыль)
    130: "grass_block",  # Grassland
    120: "grass_block",  # Shrubland
    121: "grass_block",  # Evergreen shrubland
    122: "grass_block",  # Deciduous shrubland
    10: "grass_block",   # Rainfed cropland
    11: "grass_block",
    12: "grass_block",
    20: "grass_block",   # Irrigated cropland
    51: "grass_block",   # Open evergreen broadleaved forest
    52: "grass_block",   # Closed evergreen broadleaved forest
    61: "grass_block",   # Open deciduous broadleaved forest
    62: "grass_block",
    71: "grass_block",   # Open evergreen needle-leaved forest
    72: "grass_block",
    81: "grass_block",   # Open deciduous needle-leaved forest
    82: "grass_block",
    91: "grass_block",   # Open mixed leaf forest
    92: "grass_block",
    150: "grass_block",  # Sparse vegetation
    152: "grass_block",
    153: "grass_block",
    181: "muddy_mangrove_roots",  # Swamp
    182: "muddy_mangrove_roots",  # Marsh
    183: "muddy_mangrove_roots",  # Flooded flat
    184: "sandstone",    # Saline
    185: "mangrove_log", # Mangrove
    186: "muddy_mangrove_roots",  # Salt marsh
    187: "sandstone",    # Tidal flat
    190: "grass_block",  # Impervious surfaces (города)
    140: "snow_block",   # Lichens and mosses
    0: "grass_block",    # Filled value, ставим траву
    250: "grass_block",  # Filled value, ставим траву
    # ... 
}

def get_landcover_map_from_tif(
    tif_path, bbox_south, bbox_west, bbox_north, bbox_east,
    min_x, max_x, min_z, max_z, latlng_to_block_coords, block_coords_to_latlng
):
    landcover_map = {}
    with rasterio.open(tif_path) as src:
        for x in range(min_x, max_x + 1):
            for z in range(min_z, max_z + 1):
                lat, lon = block_coords_to_latlng(x, z)
                try:
                    row, col = src.index(lon, lat)
                    value = int(src.read(1, window=((row, row+1), (col, col+1)))[0, 0])
                except Exception:
                    value = None
                landcover_map[(x, z)] = value
    return landcover_map

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

def ensure_chunk(level, x, z, dimension):
    cx, cz = x // 16, z // 16
    try:
        chunk = level.get_chunk(cx, cz, dimension)
    except Exception:
        # Чанк не существует — создаём новый пустой
        level.create_chunk(cx, cz, dimension)
        chunk = level.get_chunk(cx, cz, dimension)
    return chunk

def set_block(x, y, z, block):
    global error_count, placed_blocks_count
    try:
        level.set_version_block(x, y, z, DIMENSION, BLOCK_VERSION, block)
        placed_blocks_count += 1  # Считаем каждый поставленный блок!
    except Exception as e:
        error_count += 1
        if error_count < 10:
            print(f"⚠️ Ошибка при установке блока ({x},{y},{z}):", str(e))

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
    os.path.expanduser("~/Library/Application Support/minecraft/saves/Cartopia"),
    os.path.expanduser("~/.minecraft/saves/Cartopia"),
    os.path.expanduser("~/AppData/Roaming/.minecraft/saves/Cartopia"),
    os.path.expanduser("~\\AppData\\Roaming\\.minecraft\\saves\\Cartopia"),
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

bbox_min_x, bbox_min_z = latlng_to_block_coords(bbox_south, bbox_west)
bbox_max_x, bbox_max_z = latlng_to_block_coords(bbox_north, bbox_east)
min_x, max_x = sorted([bbox_min_x, bbox_max_x])
min_z, max_z = sorted([bbox_min_z, bbox_max_z])

# ТУТ ВЫЧИСЛЯЕМ height_map и min_elevation
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


# Используем landcover напрямую через S3 без скачивания!
landcover_url = "/vsicurl/https://s3.openlandmap.org/arco/lc_glc.fcs30d_c_30m_s_20220101_20221231_go_epsg.4326_v20231026.tif"
print("🗺️ Загружаем landcover напрямую из OpenLandMap через интернет...")
landcover_map = get_landcover_map_from_tif(
    landcover_url,
    bbox_south, bbox_west, bbox_north, bbox_east,
    min_x, max_x, min_z, max_z,
    latlng_to_block_coords, block_coords_to_latlng
)


min_elevation = min(height_map.values())
print(f"Минимальная высота на участке: {min_elevation} м")

# ТОЛЬКО ПОСЛЕ ЭТОГО объявляем функцию
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

def get_dominant_block_around(x, z, surface_map, radius=20):
    # Игнорируем воду, песок, дороги, пляжи
    from collections import Counter
    values = []
    for dx in range(-radius, radius+1):
        for dz in range(-radius, radius+1):
            if dx == 0 and dz == 0: continue
            b = surface_map.get((x+dx, z+dz))
            if b in ("grass_block", "snow_block", "sandstone"):
                values.append(b)
    if not values:
        return "grass_block"
    return Counter(values).most_common(1)[0][0]


olm_water_blocks = set()
for (x, z), lcover_val in landcover_map.items():
    if LANDCOVER_CLASS_TO_BLOCK.get(lcover_val) == "water":
        olm_water_blocks.add((x, z))

all_water_blocks =  olm_water_blocks



surface_material_map = {}
for x in range(min_x, max_x+1):
    for z in range(min_z, max_z+1):
        if (x, z) in all_water_blocks:
            blockname = "water"
        else:
            lcover_val = landcover_map.get((x, z))
            blockname = "grass_block"
            if lcover_val is not None:
                blockname = LANDCOVER_CLASS_TO_BLOCK.get(lcover_val, "grass_block")
        surface_material_map[(x, z)] = blockname

# --- OSM полигоны всегда выше landcover ---
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
                # Город — определяем "по окружению", если просто built-up!
                if key == "landuse=residential":
                    dom = get_dominant_block_around(x, z, surface_material_map)
                    if dom == "grass_block" or dom == "snow_block":
                        surface_material_map[(x, z)] = dom
                    elif dom == "sandstone":
                        surface_material_map[(x, z)] = "sandstone"
                    else:
                        surface_material_map[(x, z)] = "grass_block"
                # Любой песок, пляж, пустыня = sandstone
                elif key in ["natural=sand", "natural=beach", "natural=desert"]:
                    surface_material_map[(x, z)] = "sandstone"
                # Всё остальное по маппингу
                else:
                    surface_material_map[(x, z)] = block_name


def blur_surface(surface_map, iterations=15):
    for _ in range(iterations):
        new_map = copy.deepcopy(surface_map)
        for (x, z), mat in surface_map.items():
            # Все 8 соседей (крест + диагонали)
            neighbors = [
                surface_map.get((x+1, z)), surface_map.get((x-1, z)),
                surface_map.get((x, z+1)), surface_map.get((x, z-1)),
                surface_map.get((x+1, z+1)), surface_map.get((x-1, z-1)),
                surface_map.get((x+1, z-1)), surface_map.get((x-1, z+1)),
            ]
            count = Counter([n for n in neighbors if n is not None])
            if count:
                # Если больше соседей другого типа — сменить материал
                most_common, freq = count.most_common(1)[0]
                if most_common != mat and freq >= 4:  # Подбирать
                    new_map[(x, z)] = most_common
        surface_map = new_map
    return surface_map

surface_material_map = blur_surface(surface_material_map, iterations=15)



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


def blur_height_map(height_map, iterations=20):
    for _ in range(iterations):
        new_map = copy.deepcopy(height_map)
        for (x, z), y in height_map.items():
            neighbors = [
                height_map.get((x+1, z)), height_map.get((x-1, z)),
                height_map.get((x, z+1)), height_map.get((x, z-1)),
                height_map.get((x+1, z+1)), height_map.get((x-1, z-1)),
                height_map.get((x+1, z-1)), height_map.get((x-1, z+1)),
            ]
            valid_neighbors = [v for v in neighbors if v is not None]
            if valid_neighbors:
                # Медианное сглаживание:
                new_y = int(round(sorted(valid_neighbors + [y])[len(valid_neighbors)//2]))
                new_map[(x, z)] = new_y
        height_map = new_map
    return height_map

terrain_y = blur_height_map(terrain_y, iterations=20)


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


def correct_water_blocks(
    features, node_coords, terrain_y, surface_material_map,
    latlng_to_block_coords, block_coords_to_latlng,
    min_x, max_x, min_z, max_z,
    hydrolakes_shp, hydrorivers_shp
):
    print("🌊 Корректируем воду: OSM + HydroLAKES + HydroRIVERS ...")
    from shapely.geometry import Polygon, LineString, Point
    import geopandas as gpd

    # 1. OSM: реки (way) и озёра (полигон natural=water или waterway=riverbank/lake)
    osm_water_polygons = []
    osm_river_lines = []
    for feat in features:
        tags = feat.get("tags", {})
        if feat["type"] == "way":
            nodes = [node_coords.get(nid) for nid in feat.get("nodes", []) if nid in node_coords]
            if not nodes or len(nodes) < 2:
                continue
            # Полигоны воды (natural=water, water=lake, waterway=riverbank и тд)
            is_water_poly = False
            if nodes[0] == nodes[-1] and len(nodes) > 3:
                if (
                    tags.get("natural") == "water" or
                    tags.get("waterway") == "riverbank" or
                    tags.get("water") in ["lake", "pond", "basin", "reservoir"]
                ):
                    is_water_poly = True
            if is_water_poly:
                poly = Polygon(nodes)
                if poly.is_valid and not poly.is_empty:
                    osm_water_polygons.append(poly)
            # Линии рек
            elif tags.get("waterway") in ("river", "stream", "canal", "drain", "ditch"):
                line = LineString(nodes)
                width = None
                if "width" in tags:
                    try:
                        width = float(tags["width"])
                    except Exception:
                        pass
                if not width:
                    width = 20  # по умолчанию
                osm_river_lines.append((line, width))
    # 2. OSM multipolygon воды (relations)
    for feat in features:
        if feat["type"] != "relation":
            continue
        tags = feat.get("tags", {})
        if (
            tags.get("type") == "multipolygon" and (
                tags.get("natural") == "water" or tags.get("waterway") == "riverbank"
            )
        ):
            lines = []
            for member in feat.get("members", []):
                if member.get("role") == "outer" and member["type"] == "way":
                    way_id = member["ref"]
                    way = next((w for w in features if w.get("id") == way_id and w["type"] == "way"), None)
                    if not way:
                        continue
                    nodes = [node_coords.get(nid) for nid in way.get("nodes", []) if nid in node_coords]
                    if len(nodes) < 2:
                        continue
                    lines.append(LineString(nodes))
            # Сборка полигона из всех линий мультиполигона
            for poly in polygonize(lines):
                if poly.is_valid and not poly.is_empty:
                    osm_water_polygons.append(poly)

    # 3. HydroLAKES: полигоны озёр
    print("  Загрузка HydroLAKES...")
    lakes_gdf = gpd.read_file(hydrolakes_shp, bbox=(bbox_west, bbox_south, bbox_east, bbox_north))
    lake_polygons = []
    for geom in lakes_gdf.geometry:
        if geom.is_valid and not geom.is_empty:
            lake_polygons.append(geom)

    # 4. HydroRIVERS: линии рек с шириной
    print("  Загрузка HydroRIVERS...")
    rivers_gdf = gpd.read_file(hydrorivers_shp, bbox=(bbox_west, bbox_south, bbox_east, bbox_north))
    river_lines = []
    for idx, row in rivers_gdf.iterrows():
        geom = row.geometry
        if not geom or not geom.is_valid:
            continue
        width = row.get("WIDTH_M", 20)
        if not width or width < 1:
            width = 20
        river_lines.append((geom, width))

    # 5. Генерируем water mask
    print("  Формируем маску воды...")
    water_blocks = set()
    for poly in osm_water_polygons + lake_polygons:
        min_xx, min_zz, max_xx, max_zz = map(int, map(round, poly.bounds))
        for x in range(max(min_x, min_xx), min(max_x, max_xx)+1):
            for z in range(max(min_z, min_zz), min(max_z, max_zz)+1):
                p = Point(x, z)
                if poly.contains(p):
                    water_blocks.add((x, z))
    for line, width in osm_river_lines + river_lines:
        # Для каждой точки линии рисуем буфер
        buf = line.buffer(width / 2, cap_style=2)  # cap_style=2 — квадратные концы
        min_xx, min_zz, max_xx, max_zz = map(int, map(round, buf.bounds))
        for x in range(max(min_x, min_xx), min(max_x, max_xx)+1):
            for z in range(max(min_z, min_zz), min(max_z, max_zz)+1):
                p = Point(x, z)
                if buf.contains(p):
                    water_blocks.add((x, z))

    # 6. Корректируем блоки
    print("  Корректируем блоки мира: ставим воду...")
    for (x, z) in water_blocks:
        y = terrain_y.get((x, z), Y_BASE)
        # Не трогать если тут уже вода
        if surface_material_map.get((x, z)) == "water":
            continue
        set_block(x, y, z, Block(namespace="minecraft", base_name="air"))
        set_block(x, y-1, z, Block(namespace="minecraft", base_name="water"))
        surface_material_map[(x, z)] = "water"


correct_water_blocks(
    features, node_coords, terrain_y, surface_material_map,
    latlng_to_block_coords, block_coords_to_latlng,
    min_x, max_x, min_z, max_z,
    "HydroLAKES_polys_v10_shp/HydroLAKES_polys_v10.shp",
    "HydroRIVERS_v10_shp/HydroRIVERS_v10.shp"
)





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
    Сначала смотрит 'height' (в метрах), потом 'building:levels'.
    Если ничего нет — возвращает 2 этажа стандартной высоты.
    """
    # 1. Сначала пробуем height в метрах
    if "height" in tags:
        try:
            h = float(tags["height"])
            return max(FLOOR_HEIGHT, int(round(h)))
        except Exception:
            pass
    # 2. Если нет — пробуем этажи
    if "building:levels" in tags:
        try:
            levels = float(tags["building:levels"])
            return max(FLOOR_HEIGHT, int(round(levels * FLOOR_HEIGHT)))
        except Exception:
            pass
    # 3. Если ничего нет — 2 этажа
    return 2 * FLOOR_HEIGHT


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

level.save()

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

# --- Получаем актуальную карту высот и материалов (поверхность уже построенного мира) ---
def get_actual_surface_y_and_material_map(level, min_x, max_x, min_z, max_z, terrain_y, DIMENSION):
    actual_y_map = {}
    actual_mat_map = {}
    for x in range(min_x, max_x + 1):
        for z in range(min_z, max_z + 1):
            y = terrain_y.get((x, z), Y_BASE)
            # Пробуем найти первый НЕ air блок сверху вниз (например, в пределах 32 блоков)
            found = False
            for yy in range(y+16, y-16, -1):
                try:
                    blk = level.get_block(x, yy, z, DIMENSION)
                    if blk.base_name != "air":
                        actual_y_map[(x, z)] = yy
                        actual_mat_map[(x, z)] = blk.base_name
                        found = True
                        break
                except Exception:
                    continue
            if not found:
                actual_y_map[(x, z)] = y
                actual_mat_map[(x, z)] = "air"
    return actual_y_map, actual_mat_map

actual_surface_y_map, actual_surface_material_map = get_actual_surface_y_and_material_map(
    level, min_x, max_x, min_z, max_z, terrain_y, DIMENSION
)

def generate_bridge_profiles_and_pillars(
    features, node_coords, terrain_y, road_materials, set_block, surface_material_map,
    get_y_for_block, Y_BASE, road_blocks, min_x, max_x, min_z, max_z
):
    print("🌉 Генерация мостов")
    bridge_profiles = {}

    N_STEPS = 7       # Число ступеней на каждом въезде
    STEP_WIDTH = 1    # Ширина (по длине) каждой ступени — 1 блок!
    MIN_CLEARANCE = 7

    for feature in features:
        tags = feature.get("tags", {})
        if tags.get("railway") == "subway":
            continue  # Мосты для метро не строим!
        layer = int(tags.get("layer", "0"))
        is_bridge = tags.get("bridge") and not tags.get("tunnel")
        if not is_bridge:
            continue

        kind = tags.get("highway") or tags.get("railway")
        material, width = road_materials.get(kind, ("stone", 3))
        width = max(1, width)

        nodes = [node_coords.get(nid) for nid in feature.get("nodes", []) if nid in node_coords]
        if not nodes or len(nodes) < 2:
            continue

        # Въездные точки
        entry1 = nodes[0]
        entry2 = nodes[-1]
        y_entry1 = terrain_y.get(entry1, get_y_for_block(*entry1))
        y_entry2 = terrain_y.get(entry2, get_y_for_block(*entry2))
        base_y = max(y_entry1, y_entry2) + N_STEPS * layer

        # Построим линию моста
        bridge_line = []
        for i in range(1, len(nodes)):
            bridge_line += bresenham_line(nodes[i-1][0], nodes[i-1][1], nodes[i][0], nodes[i][1])
        bridge_line = [pt for i, pt in enumerate(bridge_line) if i == 0 or pt != bridge_line[i-1]]
        L = len(bridge_line)
        # --- Кладём блоки моста с этим профилем ---
        if L < 20:
            for (x, z) in bridge_line:
                y = terrain_y.get((x, z), get_y_for_block(x, z)) + 1
                for w in range(-width // 2, width // 2 + 1):
                    xx, zz = x, z
                    if width > 1:
                        xx, zz = x + w, z
                    bridge_profiles[(xx, zz)] = y
                    terrain_level = terrain_y.get((xx, zz), get_y_for_block(xx, zz))
                    if y <= terrain_level:
                        continue  # Не ставим на рельеф!
                    set_block(xx, y, zz, Block(namespace="minecraft", base_name=material))
            # --- ОГРАЖДЕНИЯ по краям для коротких мостов ---
            for (x, z) in bridge_line:
                y = terrain_y.get((x, z), get_y_for_block(x, z)) + 1
                if width == 1:
                    # Для ширины 1 просто ставим по бокам
                    set_block(x, y + 1, z - 1, Block(namespace="minecraft", base_name="stone_brick_wall"))
                    set_block(x, y + 1, z + 1, Block(namespace="minecraft", base_name="stone_brick_wall"))
                else:
                    # Для ширины >1 ставим по краям ширины
                    left = z - (width // 2)
                    right = z + (width // 2)
                    set_block(x, y + 1, left, Block(namespace="minecraft", base_name="stone_brick_wall"))
                    set_block(x, y + 1, right, Block(namespace="minecraft", base_name="stone_brick_wall"))
            continue  # сразу к следующему feature!        
        if L >= 20:
            STEP = N_STEPS  # = 7

            # Центральный "ровный" участок
            flat_start = STEP
            flat_end = L - STEP

            for idx in range(flat_start, flat_end):
                x, z = bridge_line[idx]
                y = terrain_y.get((x, z), get_y_for_block(x, z)) + MIN_CLEARANCE
                for idx in range(flat_start, flat_end):
                    x, z = bridge_line[idx]
                    y = terrain_y.get((x, z), get_y_for_block(x, z)) + MIN_CLEARANCE
                    for w in range(-width // 2, width // 2 + 1):
                        xx, zz = x, z
                        if width > 1:
                            xx, zz = x + w, z
                        bridge_profiles[(xx, zz)] = y
                        terrain_level = terrain_y.get((xx, zz), get_y_for_block(xx, zz))
                        if y <= terrain_level:
                            continue  # Не ставим на рельеф!
                        set_block(xx, y, zz, Block(namespace="minecraft", base_name=material))

        
        # Для каждого entry point (въезд или выезд)
        for entry_idx, entry in enumerate([bridge_line[0], bridge_line[-1]]):
            # Выбираем 3-4 точки в начале/конце линии моста для усреднения направления
            N_DIRECTION_POINTS = 4
            if entry_idx == 0:
                direction_points = bridge_line[:N_DIRECTION_POINTS]
            else:
                direction_points = bridge_line[-N_DIRECTION_POINTS:][::-1]  # переворачиваем для правильного направления

            # Вычисляем средний вектор направления
            dx = 0
            dz = 0
            for i in range(1, len(direction_points)):
                dx += direction_points[i][0] - direction_points[i - 1][0]
                dz += direction_points[i][1] - direction_points[i - 1][1]
            count = max(1, len(direction_points) - 1)
            dir_dx = dx / count
            dir_dz = dz / count
            norm = math.hypot(dir_dx, dir_dz)
            if norm == 0:
                continue
            step_dx = dir_dx / norm
            step_dz = dir_dz / norm

            # Начальная точка (вход)
            x, z = entry
            y = terrain_y.get((x, z), get_y_for_block(x, z))
            curr_y = y
            # Ортогональный вектор для ширины
            ortho_x = -step_dz
            ortho_z = step_dx

            for step in range(N_STEPS):
                curr_y += 1
                ix = int(round(x))
                iz = int(round(z))
                for w in range(-width // 2, width // 2 + 1):
                    wx = ix + int(round(ortho_x * w))
                    wz = iz + int(round(ortho_z * w))
                    set_block(wx, curr_y, wz, Block(namespace="minecraft", base_name=material))
                # Двигаемся по направлению моста
                x += step_dx
                z += step_dz

        if L < 2 * N_STEPS + 3:
            # Короткий мост — сжимаем ступени
            left_steps = right_steps = min(N_STEPS, L // 2)
            flat_len = max(1, L - left_steps - right_steps)
        else:
            left_steps = right_steps = N_STEPS
            flat_len = L - left_steps - right_steps

        # Соберём профиль высот по всей длине
        profile = []
        curr_y = y_entry1
        # Подъём (ступени)
        for i in range(left_steps):
            curr_y += 1
            profile.append(curr_y)
        # Центральная часть (ровно, на base_y, но с clearance)
        for i in range(flat_len):
            # По рельефу!
            x, z = bridge_line[left_steps + i]
            ground_y = terrain_y.get((x, z), get_y_for_block(x, z))
            y = ground_y + MIN_CLEARANCE
            profile.append(y)
        # Спуск (ступени)
        curr_y = profile[-1]
        for i in range(right_steps):
            curr_y -= 1
            profile.append(curr_y)
        # Подрезать (если вдруг вышло длиннее линии)
        profile = profile[:L]

        # Кладём блоки моста с этим профилем!
        for idx, (x, z) in enumerate(bridge_line):
            y = profile[idx]
            # Ортогональный вектор к направлению линии
            if idx < len(bridge_line) - 1:
                next_x, next_z = bridge_line[idx + 1]
                dir_x = next_x - x
                dir_z = next_z - z
            else:
                prev_x, prev_z = bridge_line[idx - 1]
                dir_x = x - prev_x
                dir_z = z - prev_z
            norm = math.hypot(dir_x, dir_z)
            if norm == 0: norm = 1
            dir_x /= norm
            dir_z /= norm
            ortho_x = -dir_z
            ortho_z = dir_x
            # Строим ширину по ортогонали
            for w in range(-width // 2, width // 2 + 1):
                xx = int(round(x + ortho_x * w))
                zz = int(round(z + ortho_z * w))
                bridge_profiles[(xx, zz)] = y
                terrain_level = terrain_y.get((xx, zz), get_y_for_block(xx, zz))
                if y <= terrain_level:
                    continue  # НЕ ставим блок на уровне рельефа
                set_block(xx, y, zz, Block(namespace="minecraft", base_name=material))

        # --- ОГРАЖДЕНИЯ по краям моста --- 
        for idx, (x, z) in enumerate(bridge_line):
            y = profile[idx]
            if idx < len(bridge_line) - 1:
                dx = bridge_line[idx+1][0] - x
                dz = bridge_line[idx+1][1] - z
            else:
                dx = x - bridge_line[idx-1][0]
                dz = z - bridge_line[idx-1][1]
            if abs(dx) > abs(dz):
                left = z - (width // 2)
                right = z + (width // 2)
                set_block(x, y + 1, left, Block(namespace="minecraft", base_name="stone_brick_wall"))
                set_block(x, y + 1, right, Block(namespace="minecraft", base_name="stone_brick_wall"))
            else:
                left = x - (width // 2)
                right = x + (width // 2)
                set_block(left, y + 1, z, Block(namespace="minecraft", base_name="stone_brick_wall"))
                set_block(right, y + 1, z, Block(namespace="minecraft", base_name="stone_brick_wall"))

    return bridge_profiles


def generate_tunnel_hole_mask(features, node_coords, terrain_y, get_y_for_block, HOLE_SIZE=4, HOLE_HEIGHT=4):
    tunnel_holes = set()
    for feature in features:
        tags = feature.get("tags", {})
        if not tags.get("tunnel"): continue
        if tags.get("railway") == "subway": continue
        nodes = [node_coords.get(nid) for nid in feature.get("nodes", []) if nid in node_coords]
        if not nodes or len(nodes) < 2: continue
        tunnel_line = []
        for i in range(1, len(nodes)):
            tunnel_line += bresenham_line(nodes[i-1][0], nodes[i-1][1], nodes[i][0], nodes[i][1])
        tunnel_line = [pt for i, pt in enumerate(tunnel_line) if i == 0 or pt != tunnel_line[i-1]]
        L = len(tunnel_line)
        if L < 25: continue
        for end_idx in [0, -1]:
            x0, z0 = tunnel_line[end_idx]
            for dx in range(-(HOLE_SIZE // 2), HOLE_SIZE // 2):
                for dz in range(-(HOLE_SIZE // 2), HOLE_SIZE // 2):
                    xx = x0 + dx
                    zz = z0 + dz
                    ground_y = terrain_y.get((xx, zz), get_y_for_block(xx, zz))
                    for dy in range(HOLE_HEIGHT):
                        tunnel_holes.add((xx, ground_y + dy, zz))
    return tunnel_holes

short_tunnel_cells = set()
for feature in features:
    tags = feature.get("tags", {})
    if tags.get("tunnel") and tags.get("railway") != "subway":
        nodes = [node_coords.get(nid) for nid in feature.get("nodes", []) if nid in node_coords]
        if nodes and len(nodes) >= 2:
            tunnel_line = []
            for i in range(1, len(nodes)):
                tunnel_line += bresenham_line(nodes[i-1][0], nodes[i-1][1], nodes[i][0], nodes[i][1])
            tunnel_line = [pt for i, pt in enumerate(tunnel_line) if i == 0 or pt != tunnel_line[i-1]]
            if len(tunnel_line) < 25:
                kind = tags.get("highway") or tags.get("railway")
                width = max(3, ROAD_MATERIALS.get(kind, ("stone", 3))[1])
                for (x, z) in tunnel_line:
                    for w in range(-width//2, width//2+1):
                        if abs(nodes[-1][0] - nodes[0][0]) > abs(nodes[-1][1] - nodes[0][1]):
                            xx, zz = x, z + w
                        else:
                            xx, zz = x + w, z
                        ground_y = terrain_y.get((xx, zz), get_y_for_block(xx, zz))
                        short_tunnel_cells.add((xx, ground_y, zz))

def get_2d_perimeter(points):
    """points - set of (x, z), возвращает set периметральных (x, z)"""
    dirs = [(-1,0),(1,0),(0,-1),(0,1)]
    perim = set()
    for (x, z) in points:
        for dx, dz in dirs:
            if (x+dx, z+dz) not in points:
                perim.add((x, z))
                break
    return perim

def generate_tunnel_profiles(
    features, node_coords, terrain_y, road_materials, set_block, surface_material_map,
    get_y_for_block, Y_BASE, road_blocks, min_x, max_x, min_z, max_z
):
    print("🚇 Генерация тоннелей")
    tunnel_profiles = {}

    MIN_CLEARANCE = 7
    HOLE_SIZE = 4      # Квадрат 4x4
    HOLE_HEIGHT = 4    # 1 слой рельефа + 3 вверх

    for feature in features:
        tags = feature.get("tags", {})
        if not tags.get("tunnel"): continue
        if tags.get("railway") == "subway": continue

        kind = tags.get("highway") or tags.get("railway")
        material, width0 = road_materials.get(kind, ("stone", 3))
        width = max(width0, 5)

        nodes = [node_coords.get(nid) for nid in feature.get("nodes", []) if nid in node_coords]
        if not nodes or len(nodes) < 2: continue

        tunnel_line = []
        for i in range(1, len(nodes)):
            tunnel_line += bresenham_line(nodes[i-1][0], nodes[i-1][1], nodes[i][0], nodes[i][1])
        tunnel_line = [pt for i, pt in enumerate(tunnel_line) if i == 0 or pt != tunnel_line[i-1]]
        L = len(tunnel_line)

        if L < 25:
            # КОРОТКИЙ ТОННЕЛЬ: дорога на рельефе, чистим только потолок (3 вверх)
            for (x, z) in tunnel_line:
                ground_y = terrain_y.get((x, z), get_y_for_block(x, z))
                for w in range(-width // 2, width // 2 + 1):
                    if abs(nodes[-1][0] - nodes[0][0]) > abs(nodes[-1][1] - nodes[0][1]):
                        xx, zz = x, z + w
                    else:
                        xx, zz = x + w, z
                    set_block(xx, ground_y, zz, Block(namespace="minecraft", base_name=material))
                    tunnel_profiles[(xx, zz)] = ground_y
                    for dy in range(1, 4):
                        set_block(xx, ground_y + dy, zz, Block(namespace="minecraft", base_name="air"))
            continue

        # ДЛИННЫЙ ТОННЕЛЬ: две квадратные дырки (вход+выход), УЧИТЫВАЕМ рельеф в каждой точке!
        for end_idx in [0, -1]:
            x0, z0 = tunnel_line[end_idx]
            for dx in range(-(HOLE_SIZE // 2), HOLE_SIZE // 2):
                for dz in range(-(HOLE_SIZE // 2), HOLE_SIZE // 2):
                    xx = x0 + dx
                    zz = z0 + dz
                    ground_y = terrain_y.get((xx, zz), get_y_for_block(xx, zz))
                    for dy in range(HOLE_HEIGHT):
                        set_block(xx, ground_y + dy, zz, Block(namespace="minecraft", base_name="air"))
                        
        # --- Дорога ниже рельефа + сбор клеток пола ---
        tunnel_floor_cells = set()
        for (x, z) in tunnel_line:
            ground_y = terrain_y.get((x, z), get_y_for_block(x, z))
            y = ground_y - MIN_CLEARANCE
            for w in range(-width // 2, width // 2 + 1):
                if abs(nodes[-1][0] - nodes[0][0]) > abs(nodes[-1][1] - nodes[0][1]):
                    xx, zz = x, z + w
                else:
                    xx, zz = x + w, z
                set_block(xx, y, zz, Block(namespace="minecraft", base_name=material))
                tunnel_profiles[(xx, zz)] = y
                tunnel_floor_cells.add((xx, y, zz))

        # --- Убираем всё лишнее в проходе и ставим крышу ---
        for (x, y, z) in tunnel_floor_cells:
            for dy in range(1, 4):
                set_block(x, y + dy, z, Block(namespace="minecraft", base_name="air"))
            set_block(x, y + 3, z, Block(namespace="minecraft", base_name="stone_bricks"))

        # --- Строим стенки по периметру пола ---
        tunnel_floor_2d = set((x, z) for (x, y, z) in tunnel_floor_cells)
        perimeter = get_2d_perimeter(tunnel_floor_2d)
        for (x, z) in perimeter:
            ys = [y for (xx, y, zz) in tunnel_floor_cells if xx == x and zz == z]
            if not ys:
                continue
            min_y = min(ys)
            for dy in range(0, 3):
                set_block(x, min_y + dy, z, Block(namespace="minecraft", base_name="stone_bricks"))
                
        # --- ПОСЛЕ генерации рукава: строим лестницы и чистим рукав под ними ---
        for end_idx in [0, -1]:
            x0, z0 = tunnel_line[end_idx]
            for dx in range(-(HOLE_SIZE // 2), HOLE_SIZE // 2):
                for dz in range(-(HOLE_SIZE // 2), HOLE_SIZE // 2):
                    xx = x0 + dx
                    zz = z0 + dz
                    ground_y = terrain_y.get((xx, zz), get_y_for_block(xx, zz))
                    tunnel_y = ground_y - MIN_CLEARANCE

                    N_STEPS = abs(ground_y - tunnel_y)
                    if N_STEPS < 1:
                        continue

                    # Вектор направления лестницы (в сторону туннеля)
                    if end_idx == 0 and len(tunnel_line) >= 2:
                        next_x, next_z = tunnel_line[1]
                    elif end_idx == -1 and len(tunnel_line) >= 2:
                        next_x, next_z = tunnel_line[-2]
                    else:
                        continue
                    dir_x = next_x - x0
                    dir_z = next_z - z0
                    norm = math.hypot(dir_x, dir_z)
                    if norm == 0: continue
                    step_dx = dir_x / norm
                    step_dz = dir_z / norm

                    for step in range(N_STEPS + 1):
                        cur_x = int(round(xx + step_dx * step))
                        cur_z = int(round(zz + step_dz * step))
                        cur_y = ground_y - step
                        for w in range(-width // 2, width // 2 + 1):
                            wx, wz = cur_x, cur_z
                            if width > 1:
                                ortho_x = -step_dz
                                ortho_z = step_dx
                                wx = cur_x + int(round(ortho_x * w))
                                wz = cur_z + int(round(ortho_z * w))
                            set_block(wx, cur_y, wz, Block(namespace="minecraft", base_name=material))
                            # Полностью очищаем всё сверху (чтобы убрать рукав)
                            for dy in range(1, 5):
                                set_block(wx, cur_y + dy, wz, Block(namespace="minecraft", base_name="air"))


            # --- СТРОИМ РУКАВ ВОКРУГ ЛЕСТНИЦЫ (по периметру ступеней + крышу) ---
            # Собрать все ступени
            stairs_cells = set()
            for step in range(N_STEPS + 1):
                cur_x = int(round(xx + step_dx * step))
                cur_z = int(round(zz + step_dz * step))
                cur_y = ground_y - step
                for w in range(-width // 2, width // 2 + 1):
                    wx, wz = cur_x, cur_z
                    if width > 1:
                        ortho_x = -step_dz
                        ortho_z = step_dx
                        wx = cur_x + int(round(ortho_x * w))
                        wz = cur_z + int(round(ortho_z * w))
                    stairs_cells.add((wx, cur_y, wz))

            # Теперь получить 2D-маску (XZ) всех ступеней
            stairs_mask = set((x, z) for (x, y, z) in stairs_cells)

            # Найти 2D-периметр
            perimeter = get_2d_perimeter(stairs_mask)

            # Для каждой точки периметра строим ВЕРТИКАЛЬНУЮ стенку вверх до rel_y-1
            for (x, z) in perimeter:
                # Найти минимальный y среди ступеней с этим (x, z)
                ys = [y for (xx, y, zz) in stairs_cells if xx == x and zz == z]
                if not ys:
                    continue
                min_y = min(ys)
                rel_y = terrain_y.get((x, z), get_y_for_block(x, z))
                target_y = rel_y - 1
                for y in range(min_y, target_y):
                    set_block(x, y, z, Block(namespace="minecraft", base_name="stone_bricks"))

            # Крыша — по всему периметру, на высоте rel_y+4
            for (x, z) in perimeter:
                rel_y = terrain_y.get((x, z), get_y_for_block(x, z))
                set_block(x, rel_y - 1 , z, Block(namespace="minecraft", base_name="stone_bricks"))

    return tunnel_profiles

road_blocks = set()
rail_blocks = set()

bridge_profiles = generate_bridge_profiles_and_pillars(
    features, node_coords, terrain_y, ROAD_MATERIALS, set_block, surface_material_map,
    get_y_for_block, Y_BASE, road_blocks, min_x, max_x, min_z, max_z
)

tunnel_profiles = generate_tunnel_profiles(
    features, node_coords, terrain_y, ROAD_MATERIALS, set_block, surface_material_map,
    get_y_for_block, Y_BASE, road_blocks, min_x, max_x, min_z, max_z
)

tunnel_holes = generate_tunnel_hole_mask(features, node_coords, terrain_y, get_y_for_block)

for feature in features:
    # --- ФИЛЬТР ДЛЯ ДОРОГ И РЕЛЬСОВ ---
    tags = feature.get("tags", {})
    # Только реальные дороги или рельсы!
    if not (
        (tags.get("highway") in {
            "motorway", "trunk", "primary", "secondary", "tertiary", "unclassified",
            "residential", "service", "living_street", "road", "track", "path", "footway",
            "cycleway", "bridleway", "steps"
        }) or
        (tags.get("railway") in {
            "rail", "tram", "light_rail", "subway", "narrow_gauge", "monorail"
        })
    ):
        continue
    # Не обрабатывать реки, заборы, водные объекты
    if "waterway" in tags or "barrier" in tags:
        continue

    tags = feature.get("tags", {})
    nodes = [node_coords.get(nid) for nid in feature.get("nodes", []) if nid in node_coords]
    if not nodes or len(nodes) < 2:
        continue
    material, width = ROAD_MATERIALS.get(tags.get("highway") or tags.get("railway"), ("stone", 3))
    block = Block(namespace="minecraft", base_name=material)
    is_bridge = tags.get("bridge") or int(tags.get("layer", "0")) != 0
    is_tunnel = tags.get("tunnel")
    is_subway = tags.get("railway") == "subway"

    # 1. рельсы
    if tags.get("railway") in ("rail", "tram", "light_rail"):
        # --- 1. Подложка ---
        for i in range(1, len(nodes)):
            (x1, z1), (x2, z2) = nodes[i-1], nodes[i]
            line = bresenham_line(x1, z1, x2, z2)
            for (x, z) in line:
                if is_bridge:
                    if (x, z) in bridge_profiles:
                        y = bridge_profiles[(x, z)]
                    else:
                        continue
                elif is_tunnel:
                    if (x, z) in tunnel_profiles:
                        y = tunnel_profiles[(x, z)]
                    else:
                        y = terrain_y.get((x, z), Y_BASE) - 7
                else:
                    y = terrain_y.get((x, z), Y_BASE)
                set_block(x, y, z, Block(namespace="minecraft", base_name="cobblestone"))
        # --- 2. Рельсы поверх ---
        for i in range(1, len(nodes)):
            (x1, z1), (x2, z2) = nodes[i-1], nodes[i]
            line = bresenham_line(x1, z1, x2, z2)
            for (x, z) in line:
                if is_bridge:
                    if (x, z) in bridge_profiles:
                        y = bridge_profiles[(x, z)]
                    else:
                        continue
                elif is_tunnel:
                    if (x, z) in tunnel_profiles:
                        y = tunnel_profiles[(x, z)]
                    else:
                        y = terrain_y.get((x, z), Y_BASE) - 7
                else:
                    y = terrain_y.get((x, z), Y_BASE)
                set_block(x, y + 1, z, Block(namespace="minecraft", base_name="rail"))
        continue

    # 2. Метро (тоже без дырок, как и раньше)
    elif tags.get("railway") == "subway":
        for (x1, z1), (x2, z2) in zip(nodes, nodes[1:]):
            dx = x2 - x1
            dz = z2 - z1
            dist = max(abs(dx), abs(dz))
            if dist == 0:
                continue
            for i in range(dist + 1):
                x = round(x1 + dx * i / dist)
                z = round(z1 + dz * i / dist)
                set_block(x, -62, z, Block(namespace="minecraft", base_name="cobblestone"))
                set_block(x, -61, z, Block(namespace="minecraft", base_name="rail"))
        continue

    # 3. Обычные дороги и всё остальное
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
                if is_bridge:
                    if (xx, zz) in bridge_profiles:
                        y = bridge_profiles[(xx, zz)]
                    else:
                        continue
                elif is_tunnel:
                    if (xx, zz) in tunnel_profiles:
                        y = tunnel_profiles[(xx, zz)]
                    else:
                        y = terrain_y.get((xx, zz), Y_BASE) - 7
                else:
                    y = terrain_y.get((xx, zz), Y_BASE)
                # Проверяем: нет ли тут дырки для туннеля
                if (xx, y, zz) in tunnel_holes:
                    continue
                set_block(xx, y, zz, block)


PILLAR_STEP = 50  # раз в сколько блоков ставить опоры

allowed_foundation = (
    "grass_block", "dirt", "muddy_mangrove_roots", "sandstone", "water"
)

print("🧱 Ставим колонны по краям моста...")

for feature in features:
    tags = feature.get("tags", {})
    layer = int(tags.get("layer", "0"))
    is_bridge = tags.get("bridge") or (layer != 0)
    if not is_bridge:
        continue

    kind = tags.get("highway") or tags.get("railway")
    material, width = ROAD_MATERIALS.get(kind, ("stone", 3))
    width = max(1, width)

    nodes = [node_coords.get(nid) for nid in feature.get("nodes", []) if nid in node_coords]
    if not nodes or len(nodes) < 2:
        continue

    # Собираем линию моста
    bridge_line = []
    for i in range(1, len(nodes)):
        bridge_line += bresenham_line(nodes[i-1][0], nodes[i-1][1], nodes[i][0], nodes[i][1])
    bridge_line = [pt for i, pt in enumerate(bridge_line) if i == 0 or pt != bridge_line[i-1]]
    L = len(bridge_line)

    # Где ставим опоры: крайние + через каждый PILLAR_STEP
    pillar_indices = [0, L-1] + [i for i in range(PILLAR_STEP, L-1, PILLAR_STEP)]

    for idx in pillar_indices:
        x, z = bridge_line[idx]

        if idx < len(bridge_line) - 1:
            nx, nz = bridge_line[idx + 1]
            dx = nx - x
            dz = nz - z
        else:
            px, pz = bridge_line[idx - 1]
            dx = x - px
            dz = z - pz

        norm = math.hypot(dx, dz)
        if norm == 0:
            continue
        ortho_x = -dz / norm
        ortho_z = dx / norm

        for side in [-1, 1]:
            # Только одна колонна по краю — никакого dwidth цикла!
            edge_x = int(round(x + ortho_x * (width // 2 + 0.5) * side))
            edge_z = int(round(z + ortho_z * (width // 2 + 0.5) * side))

            # --- ВАЖНО: создаём чанк перед get_block ---
            ensure_chunk(level, edge_x, edge_z, DIMENSION)

            ground_y = terrain_y.get((edge_x, edge_z), Y_BASE)
            # Сначала пробуем обычный surface (ground_y)
            mat = level.get_block(edge_x, ground_y, edge_z, DIMENSION).base_name

            # Если это не из разрешённых, попробуй посмотреть, что ниже — там может быть вода!
            if mat not in allowed_foundation:
                # Проверяем на уровень ниже — там теперь вода?
                mat_below = level.get_block(edge_x, ground_y - 1, edge_z, DIMENSION).base_name
                if mat_below == "water":
                    mat = "water"
                    ground_y = ground_y - 1
                else:
                    continue
            bridge_y = bridge_profiles.get((edge_x, edge_z))
            if not bridge_y:
                continue

            for py in range(ground_y + 1, bridge_y):
                set_block(edge_x, py, edge_z, Block(namespace="minecraft", base_name="stone_bricks"))


road_lines = []
road_widths = []
for feature_road in features:
    tags_road = feature_road.get("tags", {})
    if "highway" in tags_road and feature_road.get("nodes"):
        road_nodes = [node_coords.get(nid) for nid in feature_road["nodes"] if nid in node_coords]
        if len(road_nodes) >= 2:
            road_lines.append(LineString(road_nodes))
            width = ROAD_MATERIALS.get(tags_road["highway"], ("stone", 3))[1]
            road_widths.append(width)

# Собираем ВСЕ сквозные клетки заранее (до цикла по зданиям!)
all_skvoznie_road_cells = set()
for feature in features:
    tags = feature.get("tags", {})
    if "building" in tags and feature.get("nodes"):
        nodes = [node_coords.get(nid) for nid in feature.get("nodes", []) if nid in node_coords]
        if not nodes or len(nodes) < 2:
            continue
        if nodes[0] != nodes[-1]:
            nodes.append(nodes[0])
        polygon = Polygon(nodes)
        if not polygon.is_valid or polygon.is_empty:
            continue
        for line, width in zip(road_lines, road_widths):
            if not polygon.intersects(line):
                continue
            line_points = []
            coords = list(line.coords)
            for i in range(1, len(coords)):
                x1, z1 = coords[i-1]
                x2, z2 = coords[i]
                line_points += bresenham_line(int(round(x1)), int(round(z1)), int(round(x2)), int(round(z2)))
            line_points = [pt for i, pt in enumerate(line_points) if i == 0 or pt != line_points[i-1]]  # убрать дубли

            coords_inside = [pt for pt in line_points if polygon.contains(Point(pt))]
            boundary_pts = [pt for pt in line_points if polygon.boundary.distance(Point(pt)) < 1.2]
            if len(boundary_pts) >= 2 and len(coords_inside) >= 1:
                for pt in coords_inside:
                    cx, cz = int(round(pt[0])), int(round(pt[1]))
                    for dx in range(-width // 2, width // 2 + 1):
                        for dz in range(-width // 2, width // 2 + 1):
                            if polygon.contains(Point(cx + dx, cz + dz)):
                                all_skvoznie_road_cells.add((cx + dx, cz + dz))

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

            height = get_building_height(tags)
            num_floors = tags.get('building:levels')
            if num_floors is not None:
                try:
                    num_floors = int(num_floors)
                except Exception:
                    num_floors = height // 4
            else:
                num_floors = height // 4
            if height > 10 or num_floors > 3:
                tunnel_cut_height = 8
            else:
                tunnel_cut_height = 3

            for x in range(min_x, max_x + 1):
                for z in range(min_z, max_z + 1):
                    if polygon.contains(Point(x, z)):
                        ground_y = terrain_y.get((x, z), Y_BASE)
                        if (x, z) in all_skvoznie_road_cells or (x, ground_y, z) in short_tunnel_cells:
                            for dy in range(1, 1 + tunnel_cut_height):
                                set_block(x, ground_y + dy, z, Block(namespace="minecraft", base_name="air"))
                            for dy in range(1 + tunnel_cut_height, 1 + height):
                                set_block(x, ground_y + dy, z, Block(namespace="minecraft", base_name="bricks"))
                            set_block(x, ground_y + 1 + height, z, Block(namespace="minecraft", base_name="stone_slab"))
                        else:
                            for dy in range(1, 1 + height):
                                set_block(x, ground_y + dy, z, Block(namespace="minecraft", base_name="bricks"))
                            set_block(x, ground_y + 1 + height, z, Block(namespace="minecraft", base_name="stone_slab"))
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

# --- Мультиполигоны (relation building)
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

            height = get_building_height(tags)
            num_floors = tags.get('building:levels')
            if num_floors is not None:
                try:
                    num_floors = int(num_floors)
                except Exception:
                    num_floors = height // 4
            else:
                num_floors = height // 4
            if height > 10 or num_floors > 3:
                tunnel_cut_height = 8
            else:
                tunnel_cut_height = 3

            for x in range(min_x, max_x + 1):
                for z in range(min_z, max_z + 1):
                    if polygon.contains(Point(x, z)):
                        ground_y = terrain_y.get((x, z), Y_BASE)
                        if (x, z) in all_skvoznie_road_cells or (x, ground_y, z) in short_tunnel_cells:
                            for dy in range(1, 1 + tunnel_cut_height):
                                set_block(x, ground_y + dy, z, Block(namespace="minecraft", base_name="air"))
                            for dy in range(1 + tunnel_cut_height, 1 + height):
                                set_block(x, ground_y + dy, z, Block(namespace="minecraft", base_name="bricks"))
                            set_block(x, ground_y + 1 + height, z, Block(namespace="minecraft", base_name="stone_slab"))
                        else:
                            for dy in range(1, 1 + height):
                                set_block(x, ground_y + dy, z, Block(namespace="minecraft", base_name="bricks"))
                            set_block(x, ground_y + 1 + height, z, Block(namespace="minecraft", base_name="stone_slab"))
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

# --- Ограждение зон по периметру (Y_BASE+1)
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

# 2. Ставим fence
for x, z in fence_points:
    y = terrain_y.get((x, z), Y_BASE)
    set_block(
        x, y+1, z,
        Block(namespace="minecraft", base_name="oak_fence")
    )


# --- Инфраструктура ---


print("🚸 Генерация наземных пешеходных переходов (зебр)...")

HIGHWAY_PRIORITY = {
    "motorway": 10, "trunk": 9, "primary": 8, "secondary": 7, "tertiary": 6,
    "unclassified": 5, "residential": 4, "service": 3, "living_street": 2, "road": 2,
    "track": 1, "footway": 0, "path": 0, "cycleway": 0, "bridleway": 0
}

for feature in features:
    tags = feature.get("tags", {})
    if feature["type"] == "node" and tags.get("highway") == "crossing":
        crossing_id = feature["id"]
        if crossing_id not in node_coords:
            continue
        x0, z0 = node_coords[crossing_id]
        y0 = terrain_y.get((x0, z0), Y_BASE)

        # Найдём все дороги, к которым этот crossing привязан
        candidate_ways = []
        for way in features:
            if way.get("type") == "way" and crossing_id in way.get("nodes", []):
                road_tags = way.get("tags", {})
                hwy = road_tags.get("highway", "road")
                width = ROAD_MATERIALS.get(hwy, ("stone", 3))[1]
                priority = HIGHWAY_PRIORITY.get(hwy, 0)
                candidate_ways.append((priority, width, way))

        if not candidate_ways:
            continue

        # Выбираем самую приоритетную (priority > width)
        candidate_ways.sort(key=lambda t: (t[0], t[1]), reverse=True)
        _, width, main_way = candidate_ways[0]

        way_nodes = main_way["nodes"]
        idx = way_nodes.index(crossing_id)
        nodes = [node_coords[nid] for nid in way_nodes if nid in node_coords]
        if not nodes or len(nodes) < 2:
            continue

        # Если ширина меньше 3 — пропускаем
        if width < 3:
            continue

        # Направление вдоль дороги
        if 0 < idx < len(nodes) - 1:
            prev_node = nodes[idx - 1]
            next_node = nodes[idx + 1]
        elif idx == 0 and len(nodes) > 1:
            prev_node = nodes[1]
            next_node = nodes[0]
        elif idx == len(nodes) - 1 and len(nodes) > 1:
            prev_node = nodes[-2]
            next_node = nodes[-1]
        else:
            continue

        # Вектор вдоль дороги (будет "длина" линии зебры)
        dx = next_node[0] - prev_node[0]
        dz = next_node[1] - prev_node[1]
        norm = math.hypot(dx, dz)
        if norm == 0:
            continue
        dir_x = dx / norm
        dir_z = dz / norm
        # Ортогональный вектор (для размещения "полос" поперек, вдоль всей ширины)
        ortho_x = -dir_z
        ortho_z = dir_x

        zebra_length = 7  # Длина каждой белой линии

        # Проходим по всей ширине, через одну полосу (зебра полосками)
        for w in range(-width // 2, width // 2 + 1):
            if abs(w) % 2 == 0:
                for step in range(-zebra_length // 2, zebra_length // 2 + 1):
                    zx = int(round(x0 + ortho_x * w + dir_x * step))
                    zz = int(round(z0 + ortho_z * w + dir_z * step))
                    y = terrain_y.get((zx, zz), Y_BASE)
                    set_block(zx, y, zz, Block(namespace="minecraft", base_name="white_concrete"))


print("🚌 Генерация остановок...")

STOP_TAGS = [
    ("highway", "bus_stop"),
    ("highway", "tram_stop"),
    ("highway", "platform"),
    ("public_transport", "platform"),
    ("public_transport", "stop_position"),
]

for feature in features:
    tags = feature.get("tags", {})
    if feature["type"] == "node" and any(tags.get(k) == v for k, v in STOP_TAGS):
        stop_id = feature["id"]
        if stop_id not in node_coords:
            continue
        x0, z0 = node_coords[stop_id]

        # Находим дорогу возле остановки (приоритет)
        candidate_ways = []
        for way in features:
            if way.get("type") == "way" and stop_id in way.get("nodes", []):
                road_tags = way.get("tags", {})
                hwy = road_tags.get("highway", "road")
                width = ROAD_MATERIALS.get(hwy, ("stone", 3))[1]
                priority = HIGHWAY_PRIORITY.get(hwy, 0)
                candidate_ways.append((priority, width, way))
        if not candidate_ways:
            continue

        candidate_ways.sort(key=lambda t: (t[0], t[1]), reverse=True)
        _, road_width, main_way = candidate_ways[0]

        way_nodes = main_way["nodes"]
        idx = way_nodes.index(stop_id)
        nodes = [node_coords[nid] for nid in way_nodes if nid in node_coords]
        if not nodes or len(nodes) < 2:
            continue

        # Вектор вдоль дороги (direction), и вбок (ortho)
        if 0 < idx < len(nodes) - 1:
            prev_node = nodes[idx - 1]
            next_node = nodes[idx + 1]
        elif idx == 0 and len(nodes) > 1:
            prev_node = nodes[1]
            next_node = nodes[0]
        elif idx == len(nodes) - 1 and len(nodes) > 1:
            prev_node = nodes[-2]
            next_node = nodes[-1]
        else:
            continue

        dx = next_node[0] - prev_node[0]
        dz = next_node[1] - prev_node[1]
        norm = math.hypot(dx, dz)
        if norm == 0:
            continue
        dir_x = dx / norm
        dir_z = dz / norm
        ortho_x = -dir_z
        ortho_z = dir_x

        # ПЕРЕД остановкой на 1 блок вбок от центра (для ёлочки)
        marking_offset = road_width // 2 + 1
        x_mark = x0 + ortho_x * marking_offset
        z_mark = z0 + ortho_z * marking_offset

        # Рисуем "ёлочку" вдоль дороги
        marking_length = 15
        marking_start = -((marking_length - 1) // 2)
        for i in range(marking_start, marking_start + marking_length):
            for w in range(2):
                # Первый ряд (w=0): 1 . 1 . 1 ... (чётные i)
                # Второй ряд (w=1): 1 1 . 1 . ... (чётные i и по краям)
                if (w == 0 and i % 2 == 0) or (w == 1 and (i % 2 == 0 or abs(i) == (marking_length // 2))):
                    mx = int(round(x_mark - ortho_x * w + dir_x * i))
                    mz = int(round(z_mark - ortho_z * w + dir_z * i))
                    my = terrain_y.get((mx, mz), Y_BASE)
                    set_block(mx, my, mz, Block(namespace="minecraft", base_name="yellow_concrete"))

        # --- Павильон ---
        offset_from_road = marking_offset + 2  # на 2 блока дальше от разметки
        stop_cx = int(round(x0 + ortho_x * offset_from_road))
        stop_cz = int(round(z0 + ortho_z * offset_from_road))

        shelter_length = 6  # длина павильона вдоль дороги
        shelter_height = 2  # ширина

        # --- Задняя стеклянная стена ---
        for l in range(-shelter_length // 2, shelter_length // 2 + 1):
            x = int(round(stop_cx + dir_x * l + ortho_x * 1))
            z = int(round(stop_cz + dir_z * l + ortho_z * 1))
            for h in range(shelter_height):
                gy = terrain_y.get((x, z), Y_BASE) + 1 + h
                set_block(x, gy, z, Block(namespace="minecraft", base_name="glass"))

        # --- Скамейка (2 блока по центру) ---
        for l in range(-1, 1):
            x = int(round(stop_cx + dir_x * l))
            z = int(round(stop_cz + dir_z * l))
            y = terrain_y.get((x, z), Y_BASE) + 1
            set_block(x, y, z, Block(namespace="minecraft", base_name="oak_stairs"))

        # --- Крыша над лавкой и стеклом ---
        for l in range(-shelter_length // 2, shelter_length // 2 + 1):
            for w in range(0, 2):
                x = int(round(stop_cx + dir_x * l + ortho_x * (1 - w)))
                z = int(round(stop_cz + dir_z * l + ortho_z * (1 - w)))
                y = terrain_y.get((x, z), Y_BASE) + 1 + shelter_height
                set_block(x, y, z, Block(namespace="minecraft", base_name="smooth_stone_slab"))


print("🚦 Генерация светофоров")

ALLOWED_FOUNDATION = {"grass_block", "dirt", "sandstone", "snow_block"}
ROAD_SURFACES = set([m for (m, _w) in ROAD_MATERIALS.values()]) | {"rail"}
MAX_SEARCH_RADIUS = 24
PLACED_LIGHTS = set()

# Приоритет дорог (можешь использовать уже объявленный HIGHWAY_PRIORITY; на всякий случай дублирую)
HIGHWAY_PRIORITY = {
    "motorway": 10, "trunk": 9, "primary": 8, "secondary": 7, "tertiary": 6,
    "unclassified": 5, "residential": 4, "service": 3, "living_street": 2, "road": 2,
    "track": 1, "footway": 0, "path": 0, "cycleway": 0, "bridleway": 0
}

TRAFFIC_LIGHT_TAGS = [
    ("highway", "traffic_signals"),
    ("railway", "traffic_light"),
    ("highway", "crossing_traffic_signals"),
]

def ring_perimeter(cx: int, cz: int, r: int):
    if r == 0:
        yield (cx, cz)
        return
    for dx in range(-r, r + 1):
        yield (cx + dx, cz - r)
        yield (cx + dx, cz + r)
    for dz in range(-r + 1, r):
        yield (cx - r, cz + dz)
        yield (cx + r, cz + dz)

def can_place_light_here(x: int, z: int) -> tuple[bool, int]:
    if (x, z) in PLACED_LIGHTS:
        return (False, 0)
    y = terrain_y.get((x, z))
    if y is None:
        return (False, 0)
    ensure_chunk(level, x, z, DIMENSION)
    try:
        foundation = level.get_block(x, y, z, DIMENSION).base_name
        above = level.get_block(x, y + 1, z, DIMENSION).base_name
    except Exception:
        return (False, 0)
    if foundation not in ALLOWED_FOUNDATION: return (False, 0)
    if above != "air": return (False, 0)
    if surface_material_map.get((x, z)) == "water": return (False, 0)
    if actual_surface_material_map.get((x, z)) in ROAD_SURFACES: return (False, 0)
    return (True, y)

def place_traffic_light(bx: int, by: int, bz: int):
    PLACED_LIGHTS.add((bx, bz))
    ensure_chunk(level, bx, bz, DIMENSION)
    for dy in range(1, 8):
        set_block(bx, by + dy, bz, Block("minecraft", "air"))
    base_y = by + 1
    for dy in range(3):
        set_block(bx, base_y + dy, bz, Block("minecraft", "andesite_wall"))
    set_block(bx, base_y + 3, bz, Block("minecraft", "emerald_block"))
    set_block(bx, base_y + 4, bz, Block("minecraft", "gold_block"))
    set_block(bx, base_y + 5, bz, Block("minecraft", "redstone_block"))
    set_block(bx, base_y + 6, bz, Block("minecraft", "andesite_slab"))

def pick_main_way_for_node(node_id: int):
    """Берём самый приоритетный way, содержащий узел (дорога/рельсы)."""
    candidates = []
    for way in features:
        if way.get("type") != "way": 
            continue
        if node_id not in way.get("nodes", []):
            continue
        tags = way.get("tags", {})
        hwy = tags.get("highway")
        rail = tags.get("railway")
        if not hwy and not rail:
            continue
        priority = HIGHWAY_PRIORITY.get(hwy, 4 if rail else 0)  # рельсы ставим средним приоритетом
        width = ROAD_MATERIALS.get(hwy or rail, ("stone", 3))[1]
        candidates.append((priority, width, way))
    if not candidates:
        return None
    candidates.sort(key=lambda t: (t[0], t[1]), reverse=True)
    return candidates[0][2]

def direction_at_node(way, node_id: int):
    """Вектор направления дороги в узле (dx, dz). Если не удаётся — None."""
    nodes = [node_coords.get(nid) for nid in way.get("nodes", []) if nid in node_coords]
    if not nodes or len(nodes) < 2: 
        return None
    try:
        idx = way["nodes"].index(node_id)
    except ValueError:
        return None

    if 0 < idx < len(nodes) - 1:
        (x1, z1) = nodes[idx - 1]
        (x2, z2) = nodes[idx + 1]
    elif idx == 0 and len(nodes) > 1:
        (x1, z1) = nodes[0]
        (x2, z2) = nodes[1]
    elif idx == len(nodes) - 1 and len(nodes) > 1:
        (x1, z1) = nodes[-2]
        (x2, z2) = nodes[-1]
    else:
        return None

    dx, dz = x2 - x1, z2 - z1
    norm = math.hypot(dx, dz)
    if norm == 0:
        return None
    return (dx / norm, dz / norm)

for feature in features:
    if feature["type"] != "node":
        continue
    tags = feature.get("tags", {})
    if not any(tags.get(k) == v for k, v in TRAFFIC_LIGHT_TAGS):
        continue

    nid = feature["id"]
    if nid not in node_coords:
        continue
    x0, z0 = node_coords[nid]

    # 1) выбираем главный way и считаем направление в узле
    main_way = pick_main_way_for_node(nid)
    if not main_way:
        print(f"⚠️ Нет подходящего way для светофора в узле {nid}")
        continue
    dir_vec = direction_at_node(main_way, nid)
    if not dir_vec:
        print(f"⚠️ Не удалось вычислить направление для узла {nid}")
        continue

    dx, dz = dir_vec
    # правый орт-вектор
    rx, rz = -dz, dx

    best_xyz = None
    best_d2 = None

    # 2) поиск только в полуплоскости справа от направления: dot((p - p0), right_vec) > 0
    for r in range(0, MAX_SEARCH_RADIUS + 1):
        found_on_ring = False
        for (cx, cz) in ring_perimeter(x0, z0, r):
            vx, vz = (cx - x0), (cz - z0)
            # фильтр "только справа"
            if vx * rx + vz * rz <= 0:
                continue
            ok, y = can_place_light_here(cx, cz)
            if not ok:
                continue
            d2 = vx * vx + vz * vz
            if best_d2 is None or d2 < best_d2:
                best_d2 = d2
                best_xyz = (cx, y, cz)
                found_on_ring = True
        if found_on_ring:
            break

    if best_xyz is None:
        print(f"⚠️ Правый борт: не найдено места для светофора около ({x0},{z0})")
        continue

    bx, by, bz = best_xyz
    place_traffic_light(bx, by, bz)


# 🌳 Растительность

# --- хелперы для саженцев ---
SAPLING_BY_TREE = {
    "oak":       "oak_sapling",
    "birch":     "birch_sapling",
    "spruce":    "spruce_sapling",
    "jungle":    "jungle_sapling",
    "acacia":    "acacia_sapling",
    "cherry":    "cherry_sapling",
    # "dark_oak" — отдельная логика 2x2 ниже
}
GOOD_SOILS = {"grass_block", "dirt", "podzol", "coarse_dirt", "rooted_dirt", "coarse_dirt"}

def ensure_soil(x, y, z):
    """Гарантируем подходящую почву под саженец."""
    try:
        below = level.get_block(x, y, z, DIMENSION).base_name
    except Exception:
        below = "air"
    if below not in GOOD_SOILS:
        set_block(x, y, z, Block("minecraft", "dirt"))

def place_dark_oak_cluster(x, y, z):
    """Тёмный дуб требует 2x2 саженцев."""
    coords = [(x, z), (x+1, z), (x, z+1), (x+1, z+1)]
    for (xx, zz) in coords:
        ensure_chunk(level, xx, zz, DIMENSION)
        ensure_soil(xx, y, zz)
        set_block(xx, y+1, zz, Block("minecraft", "air"))
    for (xx, zz) in coords:
        set_block(xx, y+1, zz, Block("minecraft", "dark_oak_sapling"))
    return True

def place_sapling(x, y, z, tree_type):
    """Ставим корректный саженец для типа дерева (или кластер для dark_oak)."""
    ensure_chunk(level, x, z, DIMENSION)
    y = terrain_y.get((x, z), Y_BASE)
    try:
        surface = level.get_block(x, y, z, DIMENSION).base_name
        above   = level.get_block(x, y+1, z, DIMENSION).base_name
    except Exception:
        return
    # запреты: вода/занято/инфра
    if surface == "water" or above != "air":
        return
    if (x, z) in building_blocks or (x, z) in road_blocks or (x, z) in rail_blocks or (x, z) in beach_blocks:
        return

    if tree_type == "dark_oak":
        place_dark_oak_cluster(x, y, z)
        return
    if tree_type == "mangrove":
        # мангры требуют особых условий — пропускаем
        return

    sap = SAPLING_BY_TREE.get(tree_type, "oak_sapling")
    ensure_soil(x, y, z)
    set_block(x, y+1, z, Block("minecraft", sap))


print("🌳 Генерация растительности и декора...")
for polygon, key in zone_polygons:
    block_name = ZONE_MATERIALS[key]
    min_x, min_z, max_x, max_z = map(int, map(round, polygon.bounds))

    # трава/цветы/кусты/бамбук/тростник внутри полигона
    for x in range(min_x, max_x+1):
        for z in range(min_z, max_z+1):
            if not polygon.contains(Point(x, z)):
                continue
            if key in ["leisure=park", "landuse=meadow", "natural=grassland"]:
                if random.random() < 0.13:
                    y = terrain_y.get((x, z), Y_BASE)
                    set_plant(x, y+1, z, random.choice(GRASS_PLANTS + FLOWERS))
            if key in ["leisure=park", "landuse=meadow", "natural=wood", "natural=jungle"]:
                if random.random() < 0.03:
                    y = terrain_y.get((x, z), Y_BASE)
                    set_plant(x, y+1, z, "sweet_berry_bush")
            if key == "natural=jungle" and random.random() < 0.08:
                y = terrain_y.get((x, z), Y_BASE)
                set_plant(x, y+1, z, "bamboo")
            if block_name == "water":
                for dx, dz in [(-1,0),(1,0),(0,-1),(0,1)]:
                    nx, nz = x+dx, z+dz
                    if random.random() < 0.005:
                        y = terrain_y.get((x, z), Y_BASE)
                        set_plant(nx, y+1, nz, "sugar_cane")

    # вместо готовых деревьев — сажаем САЖЕНЦЫ по сетке 3×3 с прежними шансами
    tree_types = ZONE_TREES.get(key)
    if tree_types:
        for tx in range(min_x, max_x+1, 3):   # плотная сетка, как у тебя
            for tz in range(min_z, max_z+1, 3):
                if not polygon.contains(Point(tx, tz)):
                    continue
                ttype = random.choice(tree_types)
                if ttype == "cherry":
                    if random.random() < 0.07:
                        y = terrain_y.get((tx, tz), Y_BASE)
                        place_sapling(tx, y, tz, ttype)
                else:
                    if random.random() < 0.45:
                        y = terrain_y.get((tx, tz), Y_BASE)
                        place_sapling(tx, y, tz, ttype)

print("🌱 Сажаем низкую/высокую траву и цветы...")
for (x, z) in park_forest_blocks:
    y = terrain_y.get((x, z), Y_BASE)
    ensure_chunk(level, x, z, DIMENSION)
    block_below = level.get_block(x, y, z, DIMENSION)
    block_here  = level.get_block(x, y+1, z, DIMENSION)
    if block_below.base_name != "grass_block" or block_here.base_name != "air":
        continue
    if (x, z) in building_blocks or (x, z) in road_blocks or (x, z) in rail_blocks or (x, z) in beach_blocks:
        continue

    # вне «чистого парка»
    if (x, z) not in residential_blocks:
        r = random.random()
        if r < 0.50:
            set_plant(x, y+1, z, random.choice(["grass", "fern"]))
        elif r < 0.70:
            set_plant(x, y+1, z, random.choice(["tall_grass", "large_fern"]))
        elif r < 0.85:
            set_plant(x, y+1, z, random.choice(FLOWERS))
        elif r < 0.90:
            set_plant(x, y+1, z, "sweet_berry_bush")
    else:
        r = random.random()
        if r < 0.18:
            set_plant(x, y+1, z, random.choice(FLOWERS))
        elif r < 0.22:
            set_plant(x, y+1, z, "sweet_berry_bush")

print("🌳 Сажаем саженцы в парках и лесах")
for (x, z) in park_forest_blocks:
    y = terrain_y.get((x, z), Y_BASE)
    ensure_chunk(level, x, z, DIMENSION)
    block_below = level.get_block(x, y, z, DIMENSION)
    block_here  = level.get_block(x, y+1, z, DIMENSION)
    if block_below.base_name != "grass_block" or block_here.base_name != "air":
        continue
    if (x, z) in building_blocks or (x, z) in road_blocks or (x, z) in rail_blocks or (x, z) in beach_blocks:
        continue
    if random.random() < 0.05:  # та же плотность
        place_sapling(x, y, z, random.choice(["oak", "birch", "spruce", "acacia", "dark_oak"]))

print("🌲 Сажаем саженцы в жилых районах")
for (x, z) in residential_blocks:
    if (x, z) in park_forest_blocks:
        continue
    y = terrain_y.get((x, z), Y_BASE)
    ensure_chunk(level, x, z, DIMENSION)
    block_below = level.get_block(x, y, z, DIMENSION)
    block_here  = level.get_block(x, y+1, z, DIMENSION)
    if block_below.base_name != "grass_block" or block_here.base_name != "air":
        continue
    if (x, z) in building_blocks or (x, z) in road_blocks or (x, z) in rail_blocks or (x, z) in beach_blocks:
        continue
    if random.random() < 0.02:
        place_sapling(x, y, z, random.choice(["oak", "birch", "acacia"]))

print("🌿 Сажаем саженцы вне всех зон")
for (x, z) in empty_blocks:
    y = terrain_y.get((x, z), Y_BASE)
    ensure_chunk(level, x, z, DIMENSION)
    block_below = level.get_block(x, y, z, DIMENSION)
    block_here  = level.get_block(x, y+1, z, DIMENSION)
    if block_below.base_name != "grass_block" or block_here.base_name != "air":
        continue
    if (x, z) in building_blocks or (x, z) in road_blocks or (x, z) in rail_blocks or (x, z) in beach_blocks:
        continue
    if random.random() < 0.02:
        place_sapling(x, y, z, random.choice(["oak", "birch"]))


if error_count:
    print(f"⚠️ Всего ошибок: {error_count}")
print("💾 Сохраняем...")

level.save()
level.close()

end_time = time.time()
duration = end_time - start_time

# Время в формате часы, минуты, секунды
hours = int(duration // 3600)
minutes = int((duration % 3600) // 60)
seconds = int(duration % 60)

# Размер участка в метрах (бралось с фронта)
planned_size_m = size  # Это size из coords.json, который пришёл с фронта
planned_area_m2 = planned_size_m * planned_size_m
planned_area_km2 = planned_area_m2 / 1_000_000

# Размер участка в блоках
actual_blocks_x = max_x - min_x + 1
actual_blocks_z = max_z - min_z + 1
actual_blocks_total = actual_blocks_x * actual_blocks_z

# Попробуем восстановить реальный "метр на 1 блок" (с учетом округлений и map-проекций)
if actual_blocks_x > 0:
    actual_m_per_block = planned_size_m / actual_blocks_x
    actual_area_m2 = actual_blocks_x * actual_blocks_z * (actual_m_per_block ** 2)
    actual_area_km2 = actual_area_m2 / 1_000_000
else:
    actual_m_per_block = 1
    actual_area_m2 = actual_blocks_total
    actual_area_km2 = actual_area_m2 / 1_000_000

print()
print("=== Генерация завершена ===")
print(f"🗺️ Заданный участок:         {planned_size_m:.0f} × {planned_size_m:.0f} м  =  {planned_area_km2:.3f} км²")
print(f"🟩 Всего поставлено блоков:  {placed_blocks_count:,}")
print(f"⏱️ Время генерации:         {hours} ч {minutes} мин {seconds} сек  ({duration:.1f} сек)")
print("============================")
print("🎉 Генерация завершена.")