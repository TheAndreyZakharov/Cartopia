import json
import os
import math
from shapely.geometry import Polygon, Point, MultiPolygon
from amulet import load_level
from amulet.api.block import Block

# === Настройки ===
Y_BASE = -60  # высота генерации (поверх земли суперплоского мира)
ROAD_WIDTHS = {
    "residential": 6,
    "tertiary": 5,
    "secondary": 7,
    "primary": 8,
    "motorway": 10
}
BUILDING_HEIGHT = 5
BLOCK_VERSION = ("java", (1, 20, 1))
DIMENSION = "minecraft:overworld"

# === Загрузка координат и OSM-данных ===
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

# === Поиск пути к миру Minecraft ===
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
    # fallback
    bbox_south = center_lat - (size / 2) / 111320
    bbox_north = center_lat + (size / 2) / 111320
    bbox_west = center_lng - (size / 2) / (111320 * math.cos(math.radians(center_lat)))
    bbox_east = center_lng + (size / 2) / (111320 * math.cos(math.radians(center_lat)))

# === Центр карты → блоки
def latlng_to_block_coords(lat, lng):
    dx = (lng - center_lng) / (bbox_east - bbox_west) * size
    dz = (lat - center_lat) / (bbox_south - bbox_north) * size

    x = int(round(center_x + dx))
    z = int(round(center_z + dz))
    return x, z

# Центр — от игрока или от (0,0)
if player:
    center_x = int(player["x"])
    center_z = int(player["z"])
else:
    center_x, center_z = 0, 0

print(f"🧭 Центр в Minecraft координатах: x={center_x}, z={center_z}")

# === Сопоставление узлов OSM → блоки
node_coords = {}
for el in features:
    if el["type"] == "node":
        node_coords[el["id"]] = latlng_to_block_coords(el["lat"], el["lon"])

# === Подготовка блоков
error_count = 0

def set_block(x, y, z, block):
    global error_count
    try:
        level.set_version_block(x, y, z, DIMENSION, BLOCK_VERSION, block)
    except Exception as e:
        error_count += 1
        if error_count < 10:
            print(f"⚠️ Ошибка при установке блока ({x},{y},{z}):", str(e))

road = Block(namespace="minecraft", base_name="gray_concrete")
wall = Block(namespace="minecraft", base_name="bricks")
roof = Block(namespace="minecraft", base_name="stone_slab")

print("🧱 Генерация объектов...")

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

    # === Генерация дорог ===
    if "highway" in tags:
        width = ROAD_WIDTHS.get(tags["highway"], 4)
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
                        set_block(x, Y_BASE, z + w, road)
                    else:
                        set_block(x + w, Y_BASE, z, road)


    if nodes[0] != nodes[-1]:
        nodes.append(nodes[0])
    
    if "building" in tags:
        if feature.get("nodes") and nodes[0] == nodes[-1]:
            try:
                polygon = Polygon(nodes)
                if not polygon.is_valid:
                    continue
                min_x, min_z, max_x, max_z = map(int, map(round, polygon.bounds))
                for x in range(min_x, max_x + 1):
                    for z in range(min_z, max_z + 1):
                        if polygon.contains(Point(x, z)):
                            for y in range(Y_BASE, Y_BASE + BUILDING_HEIGHT):
                                set_block(x, y, z, wall)
                            set_block(x, Y_BASE + BUILDING_HEIGHT, z, roof)
            except Exception as e:
                error_count += 1
                if error_count < 5:
                    print(f"⚠️ Ошибка при генерации здания: {e}")


# === Обработка зданий из relation (мультиполигонов)
from shapely.geometry import Polygon, Point, MultiPolygon

# === Обработка зданий из relation (мультиполигонов)
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
            for x in range(min_x, max_x + 1):
                for z in range(min_z, max_z + 1):
                    if polygon.contains(Point(x, z)):
                        for y in range(Y_BASE, Y_BASE + BUILDING_HEIGHT):
                            set_block(x, y, z, wall)
                        set_block(x, Y_BASE + BUILDING_HEIGHT, z, roof)

        except Exception as e:
            error_count += 1
            if error_count < 5:
                print(f"⚠️ Ошибка при генерации здания с дырой (relation): {e}")




if error_count:
    print(f"⚠️ Всего ошибок: {error_count}")

# === Сохраняем мир ===
print("💾 Сохраняем...")
level.save()
level.close()
print("🎉 Генерация завершена.")
