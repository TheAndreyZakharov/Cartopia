package com.cartopia.builder;

import com.cartopia.store.FeatureStream;
import com.cartopia.store.GenerationStore;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.block.state.properties.AttachFace;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;

import java.util.*;


public class CameraGenerator {

    // -------- Материалы камеры --------
    private static final Block POLE_WALL   = Blocks.ANDESITE_WALL;
    private static final Block HEAD_BLOCK  = Blocks.CHISELED_STONE_BRICKS;
    private static final Block SIDE_BUTTON = Blocks.POLISHED_BLACKSTONE_BUTTON;

    // -------- Дорожные поверхности (куда ставить НЕЛЬЗЯ) --------
    private static final Set<Block> ROAD_SURFACE_BLOCKS = new HashSet<>(Arrays.asList(
            Blocks.GRAY_CONCRETE,         // полотно
            Blocks.WHITE_CONCRETE,        // разметка/зебры
            Blocks.YELLOW_CONCRETE,       // осевая/бордюры
            Blocks.STONE,
            Blocks.COBBLESTONE,
            Blocks.SPRUCE_PLANKS,         // настилы/мостки
            Blocks.CHISELED_STONE_BRICKS, // «металл»
            Blocks.SEA_LANTERN            // огни ВПП/такси
    ));

    // -------- Параметры дороги/поиска --------
    private static final int DEFAULT_ROAD_WIDTH = 12;
    private static final int MAX_EDGE_SEARCH    = 128;
    private static final int EXTRA_AFTER_EDGE   = 1;

    // -------- Контекст --------
    private final ServerLevel level;
    private final JsonObject coords;
    private final GenerationStore store;

    // bbox → блоки (клип)
    private int minX, maxX, minZ, maxZ;

    public CameraGenerator(ServerLevel level, JsonObject coords, GenerationStore store) {
        this.level = level;
        this.coords = coords;
        this.store = store;
    }

    // -------- Вещалка --------
    private static void broadcast(ServerLevel level, String msg) {
        try {
            if (level.getServer() != null) {
                for (ServerPlayer p : level.getServer().getPlayerList().getPlayers()) {
                    p.sendSystemMessage(Component.literal("[Cartopia] " + msg));
                }
            }
        } catch (Throwable ignore) {}
        System.out.println("[Cartopia] " + msg);
    }

    // -------- Типы --------
    private static final class DDir {
        final double dx, dz;
        DDir(double dx, double dz){ this.dx=dx; this.dz=dz; }
        boolean isZero(){ return Math.abs(dx) < 1e-9 && Math.abs(dz) < 1e-9; }
        double len(){ return Math.hypot(dx, dz); }
        DDir unit(){
            double L = len();
            if (L < 1e-9) return new DDir(0, 0);
            return new DDir(dx/L, dz/L);
        }
        @SuppressWarnings("unused")
        DDir perp(){ return new DDir(-dz, dx); }
    }
    private static final class Road {
        final List<int[]> pts; final int width; final int rank;
        Road(List<int[]> pts, int width, int rank){ this.pts=pts; this.width=width; this.rank=rank; }
    }
    private static final class CamPoint {
        final int x, z;
        CamPoint(int x, int z){ this.x=x; this.z=z; }
    }

    // кеш координат узлов (по id) — для enforcement
    private final Map<Long,int[]> nodeXZCache = new HashMap<>();
    // узлы, которые нужно взять из отношений enforcement
    private final Set<Long> wantEnforcementNodes = new HashSet<>();

    // -------- Паблик запуск --------
    public void generate() {
        broadcast(level, "🎥 Генерация камер (stream)…");

        if (coords == null || !coords.has("center") || !coords.has("bbox")) {
            broadcast(level, "❌ Нет coords/center/bbox — пропуск CameraGenerator.");
            return;
        }

        JsonObject center = coords.getAsJsonObject("center");
        JsonObject bbox   = coords.getAsJsonObject("bbox");
        final double centerLat  = center.get("lat").getAsDouble();
        final double centerLng  = center.get("lng").getAsDouble();
        final int    sizeMeters = coords.get("sizeMeters").getAsInt();

        final double south = bbox.get("south").getAsDouble();
        final double north = bbox.get("north").getAsDouble();
        final double west  = bbox.get("west").getAsDouble();
        final double east  = bbox.get("east").getAsDouble();

        final int centerX = coords.has("player") ? (int)Math.round(coords.getAsJsonObject("player").get("x").getAsDouble()) : 0;
        final int centerZ = coords.has("player") ? (int)Math.round(coords.getAsJsonObject("player").get("z").getAsDouble()) : 0;

        // bbox → блоки
        int[] a = latlngToBlock(south, west,  centerLat, centerLng, east, west, north, south, sizeMeters, centerX, centerZ);
        int[] b = latlngToBlock(north, east,  centerLat, centerLng, east, west, north, south, sizeMeters, centerX, centerZ);
        minX = Math.min(a[0], b[0]); maxX = Math.max(a[0], b[0]);
        minZ = Math.min(a[1], b[1]); maxZ = Math.max(a[1], b[1]);

        List<Road> roads = new ArrayList<>();
        List<CamPoint> cams = new ArrayList<>();

        boolean streaming = (store != null);
        try {
            if (streaming) {
                try (FeatureStream fs = store.featureStream()) {
                    for (JsonObject f : fs) {
                        collectRoads(f, roads,
                                centerLat, centerLng, east, west, north, south, sizeMeters, centerX, centerZ);
                        collectCameras(f, cams,
                                centerLat, centerLng, east, west, north, south, sizeMeters, centerX, centerZ);
                    }
                }
            } else {
                if (!coords.has("features")) {
                    broadcast(level, "CameraGenerator: нет coords.features — пропуск.");
                    return;
                }
                JsonArray elements = coords.getAsJsonObject("features").getAsJsonArray("elements");
                if (elements == null || elements.size() == 0) {
                    broadcast(level, "CameraGenerator: features.elements пуст — пропуск.");
                    return;
                }
                for (JsonElement el : elements) {
                    JsonObject f = el.getAsJsonObject();
                    collectRoads(f, roads,
                            centerLat, centerLng, east, west, north, south, sizeMeters, centerX, centerZ);
                    collectCameras(f, cams,
                            centerLat, centerLng, east, west, north, south, sizeMeters, centerX, centerZ);
                }
            }
        } catch (Exception ex) {
            broadcast(level, "CameraGenerator: ошибка чтения features: " + ex.getMessage());
        }

        // Пост-обработка: если мы хотели узел из enforcement, но он прошёл в стриме раньше —
        // он уже был закеширован nodeXZCache. Добавим его в камеру.
        for (Long id : wantEnforcementNodes) {
            int[] xz = nodeXZCache.get(id);
            if (xz != null) cams.add(new CamPoint(xz[0], xz[1]));
        }

        if (cams.isEmpty()) {
            broadcast(level, "CameraGenerator: камер не найдено — готово.");
            return;
        }

        // Рендер: для каждой точки найдём ближайшую дорогу и поставим у ПРАВОГО края
        int built = 0;
        for (CamPoint cp : cams) {
            try {
                if (!inBounds(cp.x, cp.z)) continue;

                RoadNear rn = nearestRoadAt(roads, cp.x, cp.z);
                if (rn != null && rn.dir != null && !rn.dir.isZero()) {
                    // Вправо по ходу — до кромки + шаг наружу
                    int[] place = moveRightOffPavement(cp.x, cp.z, rn.dir, rn.width);
                    if (place != null) {
                        buildCamera(place[0], place[1]);
                    } else {
                        // fallback: поставим прямо по координате, но убедимся, что это не полотно
                        int[] safe = nudgeIfOnRoad(cp.x, cp.z);
                        buildCamera(safe[0], safe[1]);
                    }
                } else {
                    // дороги нет — ставим прямо по месту
                    int[] safe = nudgeIfOnRoad(cp.x, cp.z);
                    buildCamera(safe[0], safe[1]);
                }
                built++;
                if (built % Math.max(1, cams.size()/5) == 0) {
                    int pct = (int)Math.round(100.0 * built / Math.max(1, cams.size()));
                    broadcast(level, "Камеры: ~" + pct + "%");
                }
            } catch (Exception ex) {
                broadcast(level, "CameraGenerator: ошибка на ("+cp.x+","+cp.z+"): " + ex.getMessage());
            }
        }
        broadcast(level, "✅ Камер поставлено: " + built);
    }

    // -------- Сбор дорог --------
    private void collectRoads(JsonObject f, List<Road> roads,
                              double centerLat, double centerLng,
                              double east, double west, double north, double south,
                              int sizeMeters, int centerX, int centerZ) {

        String type = opt(f, "type");
        if (!"way".equals(type)) return;

        JsonObject tags = tagsOf(f);
        if (tags == null) return;

        int rank = roadRank(tags);
        if (rank <= 0) return;

        // геометрия полилинии дороги
        JsonArray geom = f.has("geometry") && f.get("geometry").isJsonArray()
                ? f.getAsJsonArray("geometry") : null;
        if (geom == null || geom.size() < 2) return;

        List<int[]> pts = new ArrayList<>(geom.size());
        for (int i=0;i<geom.size();i++){
            JsonObject p = geom.get(i).getAsJsonObject();
            pts.add(latlngToBlock(p.get("lat").getAsDouble(), p.get("lon").getAsDouble(),
                    centerLat, centerLng, east, west, north, south, sizeMeters, centerX, centerZ));
        }

        int width = widthFromTagsOrDefault(tags);
        roads.add(new Road(pts, width, rank));
    }

    private static int roadRank(JsonObject tags) {
        String hwy = low(opt(tags,"highway"));
        if (hwy != null) {
            switch (hwy) {
                case "motorway": return 100;
                case "trunk": return 95;
                case "primary": return 90;
                case "secondary": return 80;
                case "tertiary": return 70;
                case "residential": return 60;
                case "living_street": return 55;
                case "unclassified": return 50;
                case "service": return 45;
                case "track": return 40;
                case "pedestrian":
                case "footway":
                case "path":
                case "cycleway": return 10;
                default: return 30;
            }
        }
        if (opt(tags,"aeroway") != null) return 0;
        return 0;
    }

    private static int widthFromTagsOrDefault(JsonObject tags) {
        Integer w = parseNumericWidth(tags);
        if (w != null && w > 0) return w;
        String hwy = low(opt(tags,"highway"));
        if (hwy != null) {
            switch (hwy) {
                case "motorway": return 20;
                case "trunk": return 18;
                case "primary": return 14;
                case "secondary": return 12;
                case "tertiary": return 12;
                case "residential": return 10;
                case "living_street": return 8;
                case "service": return 8;
                case "track": return 6;
                case "pedestrian": case "footway": case "path": case "cycleway": return 6;
            }
        }
        return DEFAULT_ROAD_WIDTH;
    }

    private static Integer parseNumericWidth(JsonObject tags) {
        if (tags == null) return null;
        String[] keys = new String[]{"width:carriageway","width","est_width","runway:width","taxiway:width"};
        for (String k:keys){
            String v = opt(tags,k);
            if (v == null || v.isBlank()) continue;
            String s = v.trim().toLowerCase(Locale.ROOT).replace(",",".");

            StringBuilder num = new StringBuilder(); boolean dot=false;
            for (char c : s.toCharArray()) {
                if (Character.isDigit(c)) num.append(c);
                else if (c=='.' && !dot) { num.append('.'); dot=true; }
            }
            if (num.length()==0) continue;
            try {
                double meters = Double.parseDouble(num.toString());
                int blocks = (int)Math.round(meters);
                if (blocks>0) return blocks;
            } catch (Exception ignore) {}
        }
        return null;
    }

    // -------- Сбор камер --------
    private void collectCameras(JsonObject f, List<CamPoint> out,
                                double centerLat, double centerLng,
                                double east, double west, double north, double south,
                                int sizeMeters, int centerX, int centerZ) {

        String type = opt(f, "type");
        JsonObject tags = tagsOf(f);

        if ("node".equals(type)) {
            Long id = asLong(f,"id");
            int[] xz = nodeXZ(f, centerLat, centerLng, east, west, north, south, sizeMeters, centerX, centerZ);
            if (id != null && xz != null) nodeXZCache.put(id, xz);

            if (tags != null && isCameraByTags(tags)) {
                if (xz != null) out.add(new CamPoint(xz[0], xz[1]));
                return;
            }

            if (id != null && wantEnforcementNodes.contains(id)) {
                if (xz != null) out.add(new CamPoint(xz[0], xz[1]));
            }
            return;
        }

        if ("way".equals(type) || "relation".equals(type)) {
            if (tags != null && isCameraByTags(tags)) {
                int[] xz = centroidXZ(f, centerLat, centerLng, east, west, north, south, sizeMeters, centerX, centerZ);
                if (xz != null) out.add(new CamPoint(xz[0], xz[1]));
            }

            if ("relation".equals(type)) {
                String rType = low(opt(tags,"type"));
                String enf   = low(opt(tags,"enforcement"));
                if ("enforcement".equals(rType) && "maxspeed".equals(enf)) {
                    JsonArray members = f.has("members") && f.get("members").isJsonArray() ? f.getAsJsonArray("members") : null;
                    if (members != null && members.size()>0) {
                        for (JsonElement mEl : members) {
                            JsonObject m = mEl.getAsJsonObject();
                            String role = low(opt(m,"role"));
                            if (role == null) continue;
                            if (!role.equals("device") && !role.equals("camera")) continue;

                            String mtype = opt(m,"type");
                            Long ref = asLong(m,"ref");

                            JsonArray g = m.has("geometry") && m.get("geometry").isJsonArray()
                                    ? m.getAsJsonArray("geometry") : null;
                            if (g != null && g.size() > 0) {
                                for (int i=0;i<g.size();i++){
                                    JsonObject p = g.get(i).getAsJsonObject();
                                    int[] xz = latlngToBlock(p.get("lat").getAsDouble(), p.get("lon").getAsDouble(),
                                            centerLat, centerLng, east, west, north, south, sizeMeters, centerX, centerZ);
                                    out.add(new CamPoint(xz[0], xz[1]));
                                }
                                continue;
                            }

                            if ("node".equals(mtype) && ref != null) {
                                wantEnforcementNodes.add(ref);
                                int[] cached = nodeXZCache.get(ref);
                                if (cached != null) out.add(new CamPoint(cached[0], cached[1]));
                            }
                        }
                    }
                }
            }
        }
    }

    private static boolean isCameraByTags(JsonObject t) {
        if (t == null) return false;
        String hwy = low(opt(t,"highway"));
        if ("speed_camera".equals(hwy)) return true;

        String mm  = low(opt(t,"man_made"));
        String st  = low(opt(t,"surveillance:type"));
        if ("surveillance".equals(mm)) {
            if (st == null) return true;
            if (st.matches("(?i)^(camera|alpr|anpr|radar|lidar)$")) return true;
        }

        if (containsValueLike(t, "speed_camera")) return true;
        return false;
    }

    // -------- Поиск ближайшей дороги и правого края --------
    private static final class RoadNear {
        final DDir dir; final int width;
        RoadNear(DDir dir, int width){ this.dir=dir; this.width=width; }
    }

    private RoadNear nearestRoadAt(List<Road> roads, int x, int z) {
        double bestD2 = Double.MAX_VALUE;
        DDir bestDir  = null;
        int bestWidth = DEFAULT_ROAD_WIDTH;
        int bestRank  = -1;

        for (Road r : roads) {
            if (r.pts.size() < 2) continue;
            NearestProj np = nearestOnPolyline(r.pts, x, z);
            if (np == null) continue;
            double d2 = np.dist2;

            boolean better = false;
            if (d2 + 1e-6 < bestD2) better = true;
            else if (Math.abs(d2 - bestD2) <= 1e-6) {
                if (r.rank > bestRank) better = true;
                else if (r.rank == bestRank && r.width > bestWidth) better = true;
            }

            if (better) {
                bestD2 = d2;
                bestDir = new DDir(np.segDx, np.segDz).unit();
                bestWidth = r.width;
                bestRank  = r.rank;
            }
        }
        if (bestDir == null || bestDir.isZero()) return null;
        return new RoadNear(bestDir, bestWidth);
    }

    private int[] moveRightOffPavement(int x0, int z0, DDir along, int roadWidth) {
        int[] a = cardinalStep(along);
        int[] right = new int[]{ +a[1], -a[0] };

        int sx = x0 + right[0] * Math.max(0, roadWidth / 2);
        int sz = z0 + right[1] * Math.max(0, roadWidth / 2);

        return marchRightToRoadEdge(sx, sz, right, MAX_EDGE_SEARCH, EXTRA_AFTER_EDGE);
    }

    private int[] marchRightToRoadEdge(int sx, int sz, int[] stepRight, int maxSteps, int extraAfter) {
        int x = sx, z = sz;
        int steps = 0;

        if (!isRoadSurfaceAt(x, z)) {
            for (int i=0; i<extraAfter; i++) {
                int nx = x + stepRight[0], nz = z + stepRight[1];
                if (!inBounds(nx, nz)) break;
                if (isRoadSurfaceAt(nx, nz)) break;
                x = nx; z = nz;
            }
            return new int[]{x, z};
        }

        while (steps < maxSteps && inBounds(x, z) && isRoadSurfaceAt(x, z)) {
            x += stepRight[0]; z += stepRight[1]; steps++;
        }
        if (!inBounds(x, z)) return null;
        if (isRoadSurfaceAt(x, z)) return null;

        for (int i=0; i<extraAfter; i++) {
            int nx = x + stepRight[0], nz = z + stepRight[1];
            if (!inBounds(nx, nz)) break;
            if (isRoadSurfaceAt(nx, nz)) break;
            x = nx; z = nz;
        }
        return new int[]{x, z};
    }

    private int[] nudgeIfOnRoad(int x, int z) {
        if (!isRoadSurfaceAt(x, z)) return new int[]{x, z};
        int[][] shifts = new int[][] { {+2,0},{-2,0},{0,+2},{0,-2},{+2,+2},{-2,-2},{+2,-2},{-2,+2} };
        for (int[] s : shifts) {
            int nx = x + s[0], nz = z + s[1];
            if (inBounds(nx,nz) && !isRoadSurfaceAt(nx,nz)) return new int[]{nx,nz};
        }
        return new int[]{x, z};
    }

    // -------- Постройка одной камеры --------
    private void buildCamera(int x, int z) {
        int y = groundY(x, z);
        if (y == Integer.MIN_VALUE) return;

        int y0 = y + 1;

        setBlock(x, y0,     z, POLE_WALL);
        setBlock(x, y0 + 1, z, POLE_WALL);
        setBlock(x, y0 + 2, z, POLE_WALL);

        setBlock(x, y0 + 3, z, HEAD_BLOCK);

        placeSideButtonIfAir(x - 1, y0 + 3, z, Direction.EAST);
        placeSideButtonIfAir(x + 1, y0 + 3, z, Direction.WEST);
        placeSideButtonIfAir(x, y0 + 3, z - 1, Direction.SOUTH);
        placeSideButtonIfAir(x, y0 + 3, z + 1, Direction.NORTH);
    }

    private void placeSideButtonIfAir(int x, int y, int z, Direction faceTowardBlock) {
        BlockPos pos = new BlockPos(x,y,z);
        if (!level.getBlockState(pos).isAir()) return;
        BlockState st = SIDE_BUTTON.defaultBlockState();
        try {
            if (st.hasProperty(BlockStateProperties.ATTACH_FACE))
                st = st.setValue(BlockStateProperties.ATTACH_FACE, AttachFace.WALL);
            if (st.hasProperty(BlockStateProperties.HORIZONTAL_FACING))
                st = st.setValue(BlockStateProperties.HORIZONTAL_FACING, faceTowardBlock.getOpposite());
        } catch (Throwable ignore) {}
        level.setBlock(pos, st, 3);
    }

    // -------- Дорожное покрытие / рельеф / клип --------
    private boolean isRoadSurfaceAt(int x, int z) {
        int y = groundY(x, z);
        if (y == Integer.MIN_VALUE) return false;
        Block b = level.getBlockState(new BlockPos(x, y, z)).getBlock();
        return ROAD_SURFACE_BLOCKS.contains(b);
    }

    private int groundY(int x, int z) {
        try {
            if (store != null && store.grid != null && store.grid.inBounds(x, z)) {
                int gy = store.grid.groundY(x, z);
                if (gy != Integer.MIN_VALUE) return gy;
            }
        } catch (Throwable ignore) {}
        try {
            if (coords != null && coords.has("terrainGrid")) {
                JsonObject g = coords.getAsJsonObject("terrainGrid");
                if (g.has("minX")) {
                    int minGX = g.get("minX").getAsInt();
                    int minGZ = g.get("minZ").getAsInt();
                    int w    = g.get("width").getAsInt();
                    int h    = g.get("height").getAsInt();
                    int ix = x - minGX, iz = z - minGZ;
                    if (ix >= 0 && ix < w && iz >= 0 && iz < h) {
                        JsonArray data = g.getAsJsonArray("data");
                        return data.get(iz * w + ix).getAsInt();
                    }
                }
            }
        } catch (Throwable ignore) {}
        return level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z) - 1;
    }

    private boolean inBounds(int x, int z) {
        return !(x < minX || x > maxX || z < minZ || z > maxZ);
    }

    private void setBlock(int x, int y, int z, Block b) {
        level.setBlock(new BlockPos(x,y,z), b.defaultBlockState(), 3);
    }

    // -------- Геометрия/проекции --------
    private static int[] cardinalStep(DDir v) {
        if (Math.abs(v.dx) >= Math.abs(v.dz)) {
            return new int[]{ v.dx >= 0 ? 1 : -1, 0 };
        } else {
            return new int[]{ 0, v.dz >= 0 ? 1 : -1 };
        }
    }

    private static final class NearestProj {
        @SuppressWarnings("unused") final double nearX;
        @SuppressWarnings("unused") final double nearZ;
        final double segDx, segDz, dist2;
        NearestProj(double nearX, double nearZ, double segDx, double segDz, double dist2){
            this.nearX=nearX; this.nearZ=nearZ; this.segDx=segDx; this.segDz=segDz; this.dist2=dist2;
        }
    }

    private static NearestProj nearestOnPolyline(List<int[]> pts, int x, int z) {
        if (pts == null || pts.size() < 2) return null;
        double bestD2 = Double.MAX_VALUE;
        double nearX = x, nearZ = z, segDxBest = 0, segDzBest = 0;

        for (int i = 0; i < pts.size() - 1; i++) {
            int[] A = pts.get(i), B = pts.get(i+1);
            double ax = A[0], az = A[1], bx = B[0], bz = B[1];
            double vx = bx - ax, vz = bz - az, wx = x - ax,  wz = z - az;

            double vv = vx*vx + vz*vz;
            if (vv < 1e-9) continue;
            double t = (vx*wx + vz*wz) / vv;
            if (t < 0) t = 0; else if (t > 1) t = 1;

            double px = ax + t * vx, pz = az + t * vz;
            double dx = x - px, dz = z - pz;
            double d2 = dx*dx + dz*dz;
            if (d2 < bestD2) {
                bestD2 = d2; nearX = px; nearZ = pz; segDxBest = vx; segDzBest = vz;
            }
        }
        return new NearestProj(nearX, nearZ, segDxBest, segDzBest, bestD2);
    }

    // -------- Координаты из элементов --------
    private static int[] nodeXZ(JsonObject node,
                                double centerLat, double centerLng,
                                double east, double west, double north, double south,
                                int sizeMeters, int centerX, int centerZ) {
        Double lat = asDouble(node,"lat"), lon = asDouble(node,"lon");
        if (lat != null && lon != null) {
            return latlngToBlock(lat, lon, centerLat, centerLng, east, west, north, south, sizeMeters, centerX, centerZ);
        }
        JsonArray g = node.has("geometry") && node.get("geometry").isJsonArray()
                ? node.getAsJsonArray("geometry") : null;
        if (g != null && g.size() > 0) {
            JsonObject p = g.get(0).getAsJsonObject();
            return latlngToBlock(p.get("lat").getAsDouble(), p.get("lon").getAsDouble(),
                    centerLat, centerLng, east, west, north, south, sizeMeters, centerX, centerZ);
        }
        return null;
    }

    private static int[] centroidXZ(JsonObject el,
                                    double centerLat, double centerLng,
                                    double east, double west, double north, double south,
                                    int sizeMeters, int centerX, int centerZ) {
        JsonArray g = el.has("geometry") && el.get("geometry").isJsonArray()
                ? el.getAsJsonArray("geometry") : null;
        if (g == null || g.size() < 1) return null;
        long sx=0, sz=0; int n=0;
        for (int i=0;i<g.size();i++){
            JsonObject p = g.get(i).getAsJsonObject();
            int[] xz = latlngToBlock(p.get("lat").getAsDouble(), p.get("lon").getAsDouble(),
                    centerLat, centerLng, east, west, north, south, sizeMeters, centerX, centerZ);
            sx += xz[0]; sz += xz[1]; n++;
        }
        if (n==0) return null;
        return new int[]{ (int)Math.round(sx/(double)n), (int)Math.round(sz/(double)n) };
    }

    // -------- JSON/утилиты --------
    private static JsonObject tagsOf(JsonObject e) {
        return (e.has("tags") && e.get("tags").isJsonObject()) ? e.getAsJsonObject("tags") : null;
    }
    private static String opt(JsonObject o, String k) {
        try { return (o!=null && o.has(k) && !o.get(k).isJsonNull()) ? o.get(k).getAsString() : null; }
        catch (Throwable ignore){ return null; }
    }
    private static String low(String s){ return s==null? null : s.toLowerCase(Locale.ROOT); }
    private static Long   asLong  (JsonObject o, String k){ try { return o.has(k) ? o.get(k).getAsLong()   : null; } catch (Throwable ignore){return null;} }
    private static Double asDouble(JsonObject o, String k){ try { return o.has(k) ? o.get(k).getAsDouble() : null; } catch (Throwable ignore){return null;} }

    private static boolean containsValueLike(JsonObject t, String needle) {
        if (t == null || needle == null) return false;
        String n = needle.toLowerCase(Locale.ROOT);
        for (Map.Entry<String, JsonElement> e : t.entrySet()) {
            JsonElement v = e.getValue();
            if (v == null || v.isJsonNull()) continue;
            try {
                String s = v.getAsString().toLowerCase(Locale.ROOT);
                if (s.contains(n)) return true;
            } catch (Exception ignore) {}
        }
        return false;
    }

    // -------- Проекция гео → блоки --------
    private static int[] latlngToBlock(double lat, double lng,
                                       double centerLat, double centerLng,
                                       double east, double west, double north, double south,
                                       int sizeMeters, int centerX, int centerZ) {
        double dx = (lng - centerLng) / (east - west) * sizeMeters;
        double dz = (lat - centerLat) / (south - north) * sizeMeters;
        return new int[]{ (int)Math.round(centerX + dx), (int)Math.round(centerZ + dz) };
    }
}