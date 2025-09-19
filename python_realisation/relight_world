from amulet import load_level

# Путь к папке мира
world_path = "/Users/andrey/Documents/projects/Cartopia/run/saves/Cartopia"

print("🗺️ Открытие мира:", world_path)
level = load_level(world_path)
print("🔦 Пересчёт освещения...")

DIMENSION = "minecraft:overworld"

chunk_coords = list(level.all_chunk_coords(DIMENSION))

for cx, cz in chunk_coords:
    # Функция пересчёта освещения чанка:
    level.relight_chunk(cx, cz, DIMENSION)
    print(f"Relit chunk ({cx}, {cz})")

print("💾 Сохраняем...")
level.save()
level.close()
print("✅ Освещение пересчитано!")
