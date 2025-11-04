package com.cartopia.weather;

import com.google.gson.*;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.server.ServerLifecycleHooks;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.storage.ServerLevelData;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;

public final class WeatherTimeController {

    private WeatherTimeController(){}

    // ===== регистрация обработчика тиков =====
    static { MinecraftForge.EVENT_BUS.register(WeatherTimeController.class); }

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final ScheduledExecutorService SCHED =
            Executors.newSingleThreadScheduledExecutor(r -> { Thread t = new Thread(r,"Cartopia-Weather"); t.setDaemon(true); return t; });

    private static final Map<ServerLevel, Instance> INSTANCES = new ConcurrentHashMap<>();

    private static final class Instance {
        final ServerLevel level;
        final double lat, lon;
        final Path outDir;

        volatile boolean running = false;
        volatile ZoneId zoneId = ZoneId.of("UTC");
        volatile long   anchorWallMs = 0L;     // когда мы "привязались"
        volatile double anchorDayTime = 0.0;   // к какому dayTime привязались
        volatile double ticksPerMs   = 1000.0 / 3_600_000.0; // 1000 тиков / 1 реальный час
        volatile long   lastSetMs    = 0L;     // чтобы не спамить setDayTime
        // === SNAPSHOT оригинального состояния при включении ===
        volatile Long    snapDayTimeTicks = null;
        volatile Boolean snapRaining = null;
        volatile Boolean snapThundering = null;
        volatile Integer snapClearTicks = null;
        volatile Integer snapRainTicks = null;
        volatile Integer snapThunderTicks = null;
        volatile Boolean snapRuleDaylight = null;
        volatile Boolean snapRuleWeather = null;

        Instance(ServerLevel level, double lat, double lon, Path outDir){
            this.level = level; this.lat = lat; this.lon = lon; this.outDir = outDir;
        }
    }

    // ---- публичный API ----
    public static void start(ServerLevel level, com.google.gson.JsonObject coords){
        try{
            var center = coords.getAsJsonObject("center");
            double lat = center.get("lat").getAsDouble();
            double lon = center.get("lng").getAsDouble();
            Path outDir = Paths.get(coords.getAsJsonObject("paths").get("packDir").getAsString());

            Instance inst = new Instance(level, lat, lon, outDir);

            Instance prev = INSTANCES.put(level, inst);
            if (prev != null) prev.running = false;

            // ВКЛ по умолчанию + снимок ТЕКУЩЕГО ванильного состояния
            setEnabled(level, true);
        }catch(Exception e){
            e.printStackTrace();
        }
    }

    public static void stop(ServerLevel level){
        INSTANCES.remove(level);
    }

    // ===== плавное продвижение времени — раз в ~1с =====
    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent e){
        if (e.phase != TickEvent.Phase.END) return;
        MinecraftServer s = ServerLifecycleHooks.getCurrentServer();
        if (s == null) return;

        long now = System.currentTimeMillis();
        for (Instance i : INSTANCES.values()){
            if (!i.running) continue;
            if (now - i.lastSetMs < 1000) continue; // обновляем ~раз в секунду

            double elapsedMs = Math.max(0, now - i.anchorWallMs);
            double target = i.anchorDayTime + elapsedMs * i.ticksPerMs;
            long day = ((long)Math.floor(target)) % 24000L;
            if (day < 0) day += 24000L;
            i.lastSetMs = now;

            long set = day;
            s.execute(() -> {
                try { i.level.setDayTime(set); }
                catch (Throwable ignore) {
                    try {
                        s.getCommands().performPrefixedCommand(
                            s.createCommandSourceStack().withLevel(i.level).withSuppressedOutput(),
                            "time set " + set
                        );
                    } catch (Throwable ignored) {}
                }
            });
        }
    }

    // ===== запрос к Open-Meteo, применение погоды и планирование следующего =====
    private static void fetchAndApply(Instance i, String reason){
        if (INSTANCES.get(i.level) != i) return; // этот Instance уже не актуален
        if (!i.running) return;    //  если выключили — ничего не делаем
        try{
            String hourly = String.join(",",
                "weather_code","precipitation","rain","showers","snowfall","temperature_2m"
            );
            String url = String.format(Locale.ROOT,
                "https://api.open-meteo.com/v1/forecast?latitude=%f&longitude=%f&hourly=%s&past_hours=1&forecast_hours=0&timezone=auto&timeformat=iso8601",
                i.lat, i.lon, URLEncoder.encode(hourly, StandardCharsets.UTF_8)
            );

            HttpClient client = HttpClient.newBuilder().version(HttpClient.Version.HTTP_2).build();
            HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                    .header("User-Agent","Cartopia/1.0 (+local)")
                    .build();
            HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode()/100 != 2) throw new RuntimeException("Open-Meteo HTTP "+resp.statusCode());

            JsonObject root = JsonParser.parseString(resp.body()).getAsJsonObject();
            String tz = root.has("timezone") ? root.get("timezone").getAsString() : "UTC";
            i.zoneId = ZoneId.of(tz);

            JsonObject h = root.getAsJsonObject("hourly");
            String timeIso = h.getAsJsonArray("time").get(0).getAsString();  // предыдущий законченный час (локальное время точки)
            int    code    = getInt(h,"weather_code",0);
            double precip  = getD (h,"precipitation",0);
            double rain    = getD (h,"rain",0);
            double snowcm  = getD (h,"snowfall",0);
            double t2m     = getD (h,"temperature_2m",Double.NaN);

            // привязка dayTime ровно к этому часу (в MC 0 = 06:00)
            LocalDateTime ldt = LocalDateTime.parse(timeIso);
            int hh = ldt.getHour(), mm = ldt.getMinute(), ss = 0;
            // double dayTicks = ((hh + 6) % 24) * 1000.0 + mm*(1000.0/60.0) + ss*(1000.0/3600.0); - неверно

            double hours = hh + mm/60.0 + ss/3600.0;
            // 0 тиков = 06:00 => вычитаем 6 часов и нормализуем в [0,24)
            double dayTicks = ((hours - 6.0 + 24.0) % 24.0) * 1000.0;

            i.anchorDayTime = dayTicks;
            i.anchorWallMs  = System.currentTimeMillis();
            i.running = true;

            // Моментально выставим время один раз (сразу видно смену)
            MinecraftServer s2 = i.level.getServer();
            s2.execute(() -> {
                long set = Math.round(dayTicks);
                try { i.level.setDayTime(set); }
                catch (Throwable ignore) {
                    try {
                        s2.getCommands().performPrefixedCommand(
                            s2.createCommandSourceStack().withLevel(i.level).withSuppressedOutput(),
                            "time set " + set
                        );
                    } catch (Throwable ignored) {}
                }
            });


            i.anchorDayTime = dayTicks;
            i.anchorWallMs  = System.currentTimeMillis();
            i.running = true;

            // записываем файлы в пакет
            try {
                Files.createDirectories(i.outDir);
                Files.writeString(i.outDir.resolve("open-meteo-last-hour.json"), resp.body(), StandardCharsets.UTF_8);
                JsonObject brief = new JsonObject();
                brief.addProperty("time_iso", timeIso);
                brief.addProperty("timezone", tz);
                brief.addProperty("day_time_ticks", (long)dayTicks);
                brief.addProperty("weather_code", code);
                brief.addProperty("precip_mm", precip);
                brief.addProperty("rain_mm",   rain);
                brief.addProperty("snow_cm",   snowcm);
                if (!Double.isNaN(t2m)) brief.addProperty("temp_c", t2m);
                Files.writeString(i.outDir.resolve("open-meteo-brief.json"), GSON.toJson(brief), StandardCharsets.UTF_8);
            } catch (Exception ignore){}

            // применяем погоду
            if (i.running) { // NEW: на всякий случай ещё раз
                Weather w = mapWeather(code, precip, snowcm, t2m);
                MinecraftServer s = i.level.getServer();
                s.execute(() -> applyWeather(i.level, w));
            }

            // планируем следующий запуск...
            if (i.running) { // NEW: планируем только когда включено
                ZonedDateTime nowZ = ZonedDateTime.now(i.zoneId);
                ZonedDateTime next = nowZ.withMinute(5).withSecond(0).withNano(0);
                if (!next.isAfter(nowZ)) next = next.plusHours(1);
                long delayMs = Duration.between(ZonedDateTime.now(i.zoneId), next).toMillis();
                SCHED.schedule(() -> fetchAndApply(i, "hourly"), Math.max(1000, delayMs), TimeUnit.MILLISECONDS);
            }

        } catch(Exception e){
            e.printStackTrace();
            // повтора хватит через 5 минут, но тоже только если включено
            SCHED.schedule(() -> { if (i.running) fetchAndApply(i, "retry"); }, 5, TimeUnit.MINUTES);
        }
    }

    // ===== маппинг кодов WMO → погода MC =====
    private enum Weather { CLEAR, RAIN, THUNDER, SNOW_OR_RAIN }

    private static Weather mapWeather(int code, double precipMm, double snowCm, double t2m){
        Set<Integer> th = Set.of(95,96,99);
        Set<Integer> rn = Set.of(51,53,55,56,57,61,63,65,80,81,82);
        Set<Integer> sn = Set.of(71,73,75,77,85,86);

        if (th.contains(code)) return Weather.THUNDER;
        if (sn.contains(code) || snowCm > 0.0 || (!Double.isNaN(t2m) && t2m <= 0.2 && precipMm > 0)) return Weather.SNOW_OR_RAIN;
        if (rn.contains(code) || precipMm > 0.1) return Weather.RAIN;
        return Weather.CLEAR;
    }

    private static void applyWeather(ServerLevel level, Weather w){
        boolean rain = (w == Weather.RAIN || w == Weather.THUNDER || w == Weather.SNOW_OR_RAIN);
        boolean thunder = (w == Weather.THUNDER);

        int seconds = 3700; // держим с запасом; doWeatherCycle=false
        int clearTicks = rain ? 0 : seconds * 20;
        int rainTicks  = rain ? seconds * 20 : 0;

        try {
            //  4 аргумента
            level.setWeatherParameters(clearTicks, rainTicks, rain, thunder);
        } catch (Throwable t) {
            // Fallback через команду
            try {
                String cmd = "weather " + (thunder ? "thunder" : rain ? "rain" : "clear") + " " + seconds;
                level.getServer().getCommands().performPrefixedCommand(
                    level.getServer().createCommandSourceStack()
                        .withLevel(level).withSuppressedOutput(),
                    cmd
                );
            } catch (Throwable ignored) {}
        }
    }

    private static void freezeCycles(ServerLevel level){
        MinecraftServer s = level.getServer();
        s.execute(() -> {
            try {
                level.getGameRules().getRule(net.minecraft.world.level.GameRules.RULE_DAYLIGHT).set(false, s);
            } catch (Throwable t) {
                try { s.getCommands().performPrefixedCommand(s.createCommandSourceStack().withLevel(level).withSuppressedOutput(),
                        "gamerule doDaylightCycle false"); } catch (Throwable ignored) {}
            }
            try {
                level.getGameRules().getRule(net.minecraft.world.level.GameRules.RULE_WEATHER_CYCLE).set(false, s);
            } catch (Throwable t) {
                try { s.getCommands().performPrefixedCommand(s.createCommandSourceStack().withLevel(level).withSuppressedOutput(),
                        "gamerule doWeatherCycle false"); } catch (Throwable ignored) {}
            }
        });
    }

    private static int getInt(JsonObject h, String key, int def){
        try { return h.getAsJsonArray(key).get(0).getAsInt(); } catch (Exception e) { return def; }
    }
    private static double getD(JsonObject h, String key, double def){
        try { return h.getAsJsonArray(key).get(0).getAsDouble(); } catch (Exception e) { return def; }
    }



    // Узнать, активен ли синк на уровне
    public static boolean isEnabled(ServerLevel level) {
        Instance i = INSTANCES.get(level);
        return i != null && i.running;
    }

    @SuppressWarnings("unused")
    // Включить/выключить синк на уровне: при ВКЛ делаем снапшот, при ВЫКЛ — восстанавливаем
    public static void setEnabled(ServerLevel level, boolean enabled) {
        Instance i = INSTANCES.get(level);
        if (i == null) return;

        if (enabled) {
            // --- Снимок текущего "ванильного" состояния ДО любых изменений ---
            try {
                // Время (сохраним фазы суток; setDayTime работает по 0..23999, getDayTime в long)
                long dt = level.getDayTime();
                i.snapDayTimeTicks = dt % 24000L;

                // Погода
                try {
                    ServerLevelData data = (ServerLevelData) level.getLevelData();
                    i.snapRaining       = data.isRaining();
                    i.snapThundering    = data.isThundering();
                    i.snapClearTicks    = data.getClearWeatherTime();
                    i.snapRainTicks     = data.getRainTime();
                    i.snapThunderTicks  = data.getThunderTime();
                } catch (Throwable t) {
                    // Фоллбек, если класс/методы отличаются
                    i.snapRaining    = level.isRaining();
                    i.snapThundering = level.isThundering();
                    // разумные длительности по умолчанию
                    i.snapClearTicks   = i.snapRaining ? 0 : 6000;
                    i.snapRainTicks    = i.snapRaining ? 6000 : 0;
                    i.snapThunderTicks = i.snapThundering ? 6000 : 0;
                }

                // Геймерулы
                i.snapRuleDaylight = level.getGameRules().getBoolean(GameRules.RULE_DAYLIGHT);
                i.snapRuleWeather  = level.getGameRules().getBoolean(GameRules.RULE_WEATHER_CYCLE);
            } catch (Throwable ignore) {}

            // --- Включаем синк и замораживаем ванильные циклы ---
            i.running = true;
            freezeCycles(level);

            // «Пинок»: сразу подтянем реальное время/погоду
            i.anchorWallMs = System.currentTimeMillis();
            try {
                SCHED.execute(() -> {
                    if (!i.running) return;
                    fetchAndApply(i, "manual-enable");
                });
            } catch (Throwable ignore) {}

        } else {
            // --- Выключаем синк и возвращаем ВСЁ как было в момент включения ---
            i.running = false;

            MinecraftServer s = level.getServer();
            s.execute(() -> {
                // 1) Время
                if (i.snapDayTimeTicks != null) {
                    try { level.setDayTime(i.snapDayTimeTicks); }
                    catch (Throwable t) {
                        try {
                            s.getCommands().performPrefixedCommand(
                                s.createCommandSourceStack().withLevel(level).withSuppressedOutput(),
                                "time set " + i.snapDayTimeTicks
                            );
                        } catch (Throwable ignored) {}
                    }
                }
                // 2) Погода
                
                try {
                    int clearTicks   = (i.snapClearTicks   != null ? i.snapClearTicks   : (i.snapRaining != null && i.snapRaining ? 0 : 6000));
                    int rainTicks    = (i.snapRainTicks    != null ? i.snapRainTicks    : (i.snapRaining != null && i.snapRaining ? 6000 : 0));
                    int thunderTicks = (i.snapThunderTicks != null ? i.snapThunderTicks : (i.snapThundering != null && i.snapThundering ? 6000 : 0));
                    boolean raining  = (i.snapRaining    != null && i.snapRaining);
                    boolean thunder  = (i.snapThundering != null && i.snapThundering);

                    level.setWeatherParameters(clearTicks, rainTicks, raining, thunder);
                } catch (Throwable t) {
                    try {
                        String cmd = "weather " + ((i.snapThundering != null && i.snapThundering) ? "thunder"
                                : (i.snapRaining != null && i.snapRaining) ? "rain" : "clear") + " 300";
                        s.getCommands().performPrefixedCommand(
                            s.createCommandSourceStack().withLevel(level).withSuppressedOutput(),
                            cmd
                        );
                    } catch (Throwable ignored) {}
                }

                // 3) Вернём геймерулы к сохранённым значениям (именно к тем, что были при включении)
                try {
                    if (i.snapRuleDaylight != null) {
                        level.getGameRules().getRule(GameRules.RULE_DAYLIGHT).set(i.snapRuleDaylight, s);
                    }
                } catch (Throwable t) {
                    try {
                        s.getCommands().performPrefixedCommand(
                            s.createCommandSourceStack().withLevel(level).withSuppressedOutput(),
                            "gamerule doDaylightCycle " + ((i.snapRuleDaylight != null && i.snapRuleDaylight) ? "true" : "false")
                        );
                    } catch (Throwable ignored) {}
                }
                try {
                    if (i.snapRuleWeather != null) {
                        level.getGameRules().getRule(GameRules.RULE_WEATHER_CYCLE).set(i.snapRuleWeather, s);
                    }
                } catch (Throwable t) {
                    try {
                        s.getCommands().performPrefixedCommand(
                            s.createCommandSourceStack().withLevel(level).withSuppressedOutput(),
                            "gamerule doWeatherCycle " + ((i.snapRuleWeather != null && i.snapRuleWeather) ? "true" : "false")
                        );
                    } catch (Throwable ignored) {}
                }
            });
        }
    }

    @SuppressWarnings("unused")
    // Разморозка правил (вернуть ванильное поведение)
    private static void unfreezeCycles(ServerLevel level){
        MinecraftServer s = level.getServer();
        s.execute(() -> {
            try {
                level.getGameRules().getRule(net.minecraft.world.level.GameRules.RULE_DAYLIGHT).set(true, s);
            } catch (Throwable t) {
                try { s.getCommands().performPrefixedCommand(
                    s.createCommandSourceStack().withLevel(level).withSuppressedOutput(),
                    "gamerule doDaylightCycle true"); } catch (Throwable ignored) {}
            }
            try {
                level.getGameRules().getRule(net.minecraft.world.level.GameRules.RULE_WEATHER_CYCLE).set(true, s);
            } catch (Throwable t) {
                try { s.getCommands().performPrefixedCommand(
                    s.createCommandSourceStack().withLevel(level).withSuppressedOutput(),
                    "gamerule doWeatherCycle true"); } catch (Throwable ignored) {}
            }
        });
    }

    public static boolean hasInstance(ServerLevel level) {
        return INSTANCES.containsKey(level);
    }





}