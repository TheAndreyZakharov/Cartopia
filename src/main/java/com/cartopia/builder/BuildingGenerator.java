package com.cartopia.builder;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.registries.ForgeRegistries;
import java.util.*;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.block.state.properties.DoorHingeSide;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;


public class BuildingGenerator {

    // ========= ПАЛИТРЫ МАТЕРИАЛОВ/ЦВЕТОВ =========

    /** Базовый материал фасада по типу материала OSM -> minecraft:block_id */
    private static final Map<String, String> FACADE_MATERIAL = new LinkedHashMap<>();
    /** Материал крыши по материалу OSM -> minecraft:block_id */
    private static final Map<String, String> ROOF_MATERIAL = new LinkedHashMap<>();
    /** Предпочтительный цветовой материал, если есть цвет (для фасада) */
    private static final Map<String, String> COLOR_BASE_FACADE = new LinkedHashMap<>();
    /** Предпочтительный цветовой материал, если есть цвет (для крыши) */
    private static final Map<String, String> COLOR_BASE_ROOF = new LinkedHashMap<>();
    /** CSS/OSM имена цветов -> ближайший цвет Minecraft (набор concrete/terracotta/wool) */
    private static final Map<String, String> COLOR_NAME_TO_BLOCK = new LinkedHashMap<>();
    /** CSS/OSM имена цветов СТЕКЛА -> соответствующий stained/tinted glass */
    private static final Map<String, String> GLASS_COLOR_NAME_TO_BLOCK = new LinkedHashMap<>();
    /** Палитра RGB для ближайшего подбора по HEX (RRGGBB) */
    private static final Map<String, int[]> BLOCK_RGB = new LinkedHashMap<>();
    /** Если не указан материал — дефолтные блоки */
    private static final String DEFAULT_FACADE_BLOCK = "minecraft:light_gray_concrete";
    private static final String DEFAULT_ROOF_BLOCK   = "minecraft:stone_bricks";

    /** высота этажа (блоков) */
    private static final int LEVEL_HEIGHT = 4;

    /** Высота прорезаемого прохода по умолчанию (если здание низкое) */
    private static final int PASSAGE_CLEAR_HEIGHT = 4;

    /** Целевая ширина прохода (в блоках) */
    private static final int PASSAGE_WIDTH_BLOCKS = 4;

    /** Вычисляем высоту прохода: если здание выше 15 блоков — 8, иначе дефолт (4) */
    private int computePassageClearHeight(int buildingHeightBlocks) {
        return (buildingHeightBlocks > 15 ? 8 : PASSAGE_CLEAR_HEIGHT);
    }

    // Навесы: отступ крыши от рельефа и шаг опор
    private static final int CANOPY_OFFSET_BLOCKS = 6;
    private static final int CANOPY_SUPPORT_STEP  = 5;

    /** Фундамент/подложка под здание */
    private static final String FOUNDATION_BLOCK_ID = "minecraft:stone_bricks";
    /** Толщина подложки (в блоках). 1 —  */
    private static final int FOUNDATION_THICKNESS = 1;

    private static final boolean FLOOR_LIGHTING_ENABLED_DEF = true;
    private static final String  FLOOR_LIGHT_BLOCK_ID_DEF   = "minecraft:glowstone";
    private static final int     FLOOR_LIGHT_EDGE_MARGIN_DEF = 1;
    private static final int     FLOOR_LIGHT_MIN_SPACING_DEF = 4;  
    private static final int     FLOOR_LIGHT_PERIOD_DEF      = 4;  

    // === АНТИ-ПРОСВЕТ сквозь здание: внутренняя перегородка ===
    private static final boolean PRIVACY_WALLS_ENABLED = true;     // выкл/вкл фичу
    private static final int     PRIVACY_WALL_OFFSET_BLOCKS = 5;   // отступ внутрь от контура здания (в блоках)
    private static final int     PRIVACY_WALL_HEIGHT_BLOCKS = 3;   // высота перегородки от пола (в блоках)
    private static final String  PRIVACY_WALL_BLOCK_ID   = "minecraft:light_gray_concrete"; // материал перегородки

    // === ПЕРИМЕТРАЛЬНЫЕ ФОНАРИ ===
    private static final boolean PERIMETER_LANTERNS_ENABLED = true;
    private static final int     PERIM_LANTERN_HEIGHT       = 4;  // от уровня земли, снаружи
    private static final int     PERIM_LANTERN_STEP         = 4;  // шаг вдоль периметра
    private static final int     PERIM_LANTERN_SEARCH_RAD   = 3;  // поиск ближайшей замены, если стекло/мешает

    static {
        // ----- FACADE materials -----
        putAll(FACADE_MATERIAL, new Object[][]{
                {"brick",           "minecraft:bricks"},
                {"brickwork",       "minecraft:bricks"},
                {"stone",           "minecraft:stone"},
                {"granite",         "minecraft:polished_granite"},
                {"limestone",       "minecraft:smooth_stone"},
                {"sandstone",       "minecraft:smooth_sandstone"},
                {"marble",          "minecraft:quartz_block"},
                {"concrete",        "minecraft:light_gray_concrete"},
                {"plaster",         "minecraft:white_concrete"},
                {"stucco",          "minecraft:white_concrete"},
                {"render",          "minecraft:white_concrete"},
                {"adobe",           "minecraft:terracotta"},
                {"clay",            "minecraft:terracotta"},
                {"wood",            "minecraft:oak_planks"},
                {"timber",          "minecraft:spruce_planks"},
                {"metal",           "minecraft:iron_block"},
                {"steel",           "minecraft:iron_block"},
                {"panel",           "minecraft:smooth_quartz"},
                {"composite",       "minecraft:smooth_quartz"},
                {"copper",          "minecraft:waxed_cut_copper"},
                {"deepslate",       "minecraft:polished_deepslate"},
                {"gold",            "minecraft:gold_block"},
                {"gilded",          "minecraft:gold_block"},
                {"gilt",            "minecraft:gold_block"},
                {"iron",            "minecraft:iron_block"},
                {"stainless_steel", "minecraft:iron_block"},
                {"chrome",          "minecraft:iron_block"},
                {"chromium",        "minecraft:iron_block"},
                {"aluminium",       "minecraft:iron_block"},
                {"aluminum",        "minecraft:iron_block"},
                {"zinc",            "minecraft:iron_block"},
                {"tin",             "minecraft:iron_block"},
                {"silver",          "minecraft:iron_block"},
                {"titanium",        "minecraft:iron_block"},
                {"nickel",          "minecraft:iron_block"},
                {"lead",            "minecraft:gray_concrete"},
                {"bronze",          "minecraft:orange_terracotta"},
                {"brass",           "minecraft:yellow_terracotta"},
                {"glass",           "minecraft:blue_stained_glass"},
                {"verdigris",       "minecraft:waxed_oxidized_cut_copper"},
                {"mirror",            "minecraft:blue_stained_glass"},
                {"mirrored",          "minecraft:blue_stained_glass"},
                {"mirror_glass",      "minecraft:blue_stained_glass"},
                {"mirror glass",      "minecraft:blue_stained_glass"},
                {"reflective",        "minecraft:blue_stained_glass"},
                {"reflective_glass",  "minecraft:blue_stained_glass"},
                {"reflective glass",  "minecraft:blue_stained_glass"},
                {"wooden",            "minecraft:oak_planks"},
                {"lumber",            "minecraft:oak_planks"},
                {"timber_frame",      "minecraft:oak_planks"},
                {"half_timbered",     "minecraft:oak_planks"},
                {"board",             "minecraft:oak_planks"},
                {"boards",            "minecraft:oak_planks"},
                {"plank",             "minecraft:oak_planks"},
                {"planks",            "minecraft:oak_planks"},
                {"log",               "minecraft:oak_planks"},
                {"logs",              "minecraft:oak_planks"},
                {"oak",               "minecraft:oak_planks"},
                {"dark_oak",          "minecraft:dark_oak_planks"},
                {"dark oak",          "minecraft:dark_oak_planks"},
                {"spruce",            "minecraft:spruce_planks"},
                {"pine",              "minecraft:spruce_planks"},
                {"fir",               "minecraft:spruce_planks"},
                {"birch",             "minecraft:birch_planks"},
                {"beech",             "minecraft:birch_planks"},
                {"ash",               "minecraft:birch_planks"},
                {"acacia",            "minecraft:acacia_planks"},
                {"jungle",            "minecraft:jungle_planks"},
                {"mahogany",          "minecraft:jungle_planks"},
                {"teak",              "minecraft:jungle_planks"},
                {"mangrove",          "minecraft:mangrove_planks"},
                {"cherry",            "minecraft:cherry_planks"},
                {"bamboo",            "minecraft:bamboo_planks"},
                {"warped",            "minecraft:warped_planks"},
                {"crimson",           "minecraft:crimson_planks"},
                {"cedar",             "minecraft:spruce_planks"},
                {"larch",             "minecraft:spruce_planks"},
                {"walnut",            "minecraft:dark_oak_planks"},
                {"chestnut",          "minecraft:dark_oak_planks"},
                {"elm",               "minecraft:oak_planks"},
        });

        // ----- ROOF materials -----
        putAll(ROOF_MATERIAL, new Object[][]{
                {"tile",            "minecraft:red_terracotta"},
                {"clay",            "minecraft:red_terracotta"},
                {"slate",           "minecraft:deepslate_tiles"},
                {"thatch",          "minecraft:hay_block"},
                {"metal",           "minecraft:iron_block"},
                {"zinc",            "minecraft:iron_block"},
                {"asphalt",         "minecraft:black_concrete"},
                {"shingle",         "minecraft:gray_terracotta"},
                {"concrete",        "minecraft:light_gray_concrete"},
                {"glass",           "minecraft:blue_stained_glass"},
                {"gold",            "minecraft:gold_block"},
                {"gilded",          "minecraft:gold_block"},
                {"gilt",            "minecraft:gold_block"},
                {"copper",          "minecraft:waxed_cut_copper"},
                {"iron",            "minecraft:iron_block"},
                {"steel",           "minecraft:iron_block"},
                {"stainless_steel", "minecraft:iron_block"},
                {"chrome",          "minecraft:iron_block"},
                {"chromium",        "minecraft:iron_block"},
                {"aluminium",       "minecraft:iron_block"},
                {"aluminum",        "minecraft:iron_block"},
                {"zinc",            "minecraft:iron_block"},
                {"tin",             "minecraft:iron_block"},
                {"silver",          "minecraft:iron_block"},
                {"titanium",        "minecraft:iron_block"},
                {"nickel",          "minecraft:iron_block"},
                {"lead",            "minecraft:gray_concrete"},
                {"bronze",          "minecraft:orange_terracotta"},
                {"brass",           "minecraft:yellow_terracotta"},
                {"verdigris",       "minecraft:waxed_oxidized_cut_copper"},
                {"wood",             "minecraft:oak_planks"},
                {"wooden",           "minecraft:oak_planks"},
                {"wood_shingle",     "minecraft:oak_planks"},
                {"shingle_wood",     "minecraft:oak_planks"},
                {"shake",            "minecraft:spruce_planks"},
                {"cedar_shake",      "minecraft:spruce_planks"},
        });

        putAll(COLOR_BASE_FACADE, new Object[][]{
                {"default", "minecraft:white_concrete"}
        });
        putAll(COLOR_BASE_ROOF, new Object[][]{
                {"default", "minecraft:red_terracotta"}
        });

        // ----- Мэппинг имён цветов (полный набор CSS/X11 + синонимы) -> Minecraft блок -----
        // Нормализация уже удаляет пробелы/дефисы/подчёркивания и делает lower-case,
        // так что "light_goldenrod_yellow" == "lightgoldenrodyellow".
        putAll(COLOR_NAME_TO_BLOCK, new Object[][]{
                // Белые/светлые
                {"white", "minecraft:white_concrete"},
                {"snow", "minecraft:white_concrete"},
                {"whitesmoke", "minecraft:white_concrete"},
                {"ghostwhite", "minecraft:white_concrete"},
                {"ivory", "minecraft:white_concrete"},
                {"floralwhite", "minecraft:white_concrete"},
                {"seashell", "minecraft:white_concrete"},
                {"honeydew", "minecraft:white_concrete"},
                {"mintcream", "minecraft:white_concrete"},
                {"azure", "minecraft:light_blue_concrete"},
                {"aliceblue", "minecraft:light_blue_concrete"},
                {"oldlace", "minecraft:white_terracotta"},
                {"linen", "minecraft:white_terracotta"},
                {"cornsilk", "minecraft:white_terracotta"},
                {"beige", "minecraft:white_terracotta"},
                {"antiquewhite", "minecraft:white_terracotta"},
                {"papayawhip", "minecraft:white_terracotta"},
                {"blanchedalmond", "minecraft:white_terracotta"},
                {"bisque", "minecraft:white_terracotta"},
                {"peachpuff", "minecraft:white_terracotta"},
                {"navajowhite", "minecraft:white_terracotta"},
                {"wheat", "minecraft:yellow_terracotta"},
                {"lemonchiffon", "minecraft:yellow_concrete"},
                {"lightyellow", "minecraft:yellow_concrete"},
                {"lightgoldenrodyellow", "minecraft:yellow_concrete"},
                {"khaki", "minecraft:yellow_terracotta"},
                {"palegoldenrod", "minecraft:yellow_terracotta"},
                {"gold", "minecraft:yellow_concrete"},
                {"yellow", "minecraft:yellow_concrete"},
                {"silver", "minecraft:iron_block"},
                {"gainsboro", "minecraft:light_gray_concrete"},
                {"lightgray", "minecraft:light_gray_concrete"},
                {"lightgrey", "minecraft:light_gray_concrete"},
                {"darkgray", "minecraft:gray_concrete"},
                {"darkgrey", "minecraft:gray_concrete"},
                {"gray", "minecraft:gray_concrete"},
                {"grey", "minecraft:gray_concrete"},
                {"dimgray", "minecraft:gray_concrete"},
                {"dimgrey", "minecraft:gray_concrete"},
                {"black", "minecraft:black_concrete"},
                {"red", "minecraft:red_concrete"},
                {"darkred", "minecraft:red_concrete"},
                {"firebrick", "minecraft:red_concrete"},
                {"crimson", "minecraft:red_concrete"},
                {"indianred", "minecraft:red_terracotta"},
                {"tomato", "minecraft:red_concrete"},
                {"salmon", "minecraft:pink_concrete"},
                {"darksalmon", "minecraft:pink_concrete"},
                {"lightsalmon", "minecraft:orange_concrete"},
                {"coral", "minecraft:orange_concrete"},
                {"orangered", "minecraft:orange_concrete"},
                {"orange", "minecraft:orange_concrete"},
                {"pink", "minecraft:pink_concrete"},
                {"lightpink", "minecraft:pink_concrete"},
                {"hotpink", "minecraft:pink_concrete"},
                {"deeppink", "minecraft:pink_concrete"},
                {"palevioletred", "minecraft:magenta_terracotta"},
                {"mediumvioletred", "minecraft:magenta_concrete"},
                {"maroon", "minecraft:red_terracotta"},
                {"magenta", "minecraft:magenta_concrete"},
                {"fuchsia", "minecraft:magenta_concrete"},
                {"lavenderblush", "minecraft:pink_terracotta"},
                {"lavender", "minecraft:light_blue_terracotta"},
                {"plum", "minecraft:purple_terracotta"},
                {"violet", "minecraft:purple_concrete"},
                {"orchid", "minecraft:magenta_concrete"},
                {"thistle", "minecraft:purple_terracotta"},
                {"rebeccapurple", "minecraft:purple_concrete"},
                {"purple", "minecraft:purple_concrete"},
                {"blueviolet", "minecraft:purple_concrete"},
                {"darkmagenta", "minecraft:magenta_concrete"},
                {"darkviolet", "minecraft:purple_concrete"},
                {"darkorchid", "minecraft:purple_concrete"},
                {"mediumorchid", "minecraft:magenta_concrete"},
                {"mediumpurple", "minecraft:purple_concrete"},
                {"slateblue", "minecraft:blue_concrete"},
                {"darkslateblue", "minecraft:purple_concrete"},
                {"mediumslateblue", "minecraft:blue_concrete"},
                {"indigo", "minecraft:purple_concrete"},
                {"blue", "minecraft:blue_concrete"},
                {"mediumblue", "minecraft:blue_concrete"},
                {"darkblue", "minecraft:blue_concrete"},
                {"midnightblue", "minecraft:blue_concrete"},
                {"navy", "minecraft:blue_concrete"},
                {"royalblue", "minecraft:blue_concrete"},
                {"cornflowerblue", "minecraft:blue_concrete"},
                {"dodgerblue", "minecraft:blue_concrete"},
                {"deepskyblue", "minecraft:light_blue_concrete"},
                {"lightskyblue", "minecraft:light_blue_concrete"},
                {"skyblue", "minecraft:light_blue_concrete"},
                {"lightblue", "minecraft:light_blue_concrete"},
                {"steelblue", "minecraft:iron_block"},
                {"lightsteelblue", "minecraft:light_blue_concrete"},
                {"powderblue", "minecraft:light_blue_concrete"},
                {"aqua", "minecraft:cyan_concrete"},
                {"cyan", "minecraft:cyan_concrete"},
                {"turquoise", "minecraft:cyan_concrete"},
                {"mediumturquoise", "minecraft:cyan_concrete"},
                {"darkturquoise", "minecraft:cyan_concrete"},
                {"paleturquoise", "minecraft:cyan_terracotta"},
                {"lightseagreen", "minecraft:cyan_concrete"},
                {"cadetblue", "minecraft:cyan_terracotta"},
                {"teal", "minecraft:cyan_concrete"},
                {"aquamarine", "minecraft:cyan_concrete"},
                {"green", "minecraft:green_concrete"},
                {"darkgreen", "minecraft:green_concrete"},
                {"forestgreen", "minecraft:green_concrete"},
                {"seagreen", "minecraft:green_terracotta"},
                {"mediumseagreen", "minecraft:green_terracotta"},
                {"lime", "minecraft:lime_concrete"},
                {"limegreen", "minecraft:lime_concrete"},
                {"lawngreen", "minecraft:lime_concrete"},
                {"chartreuse", "minecraft:lime_concrete"},
                {"greenyellow", "minecraft:lime_concrete"},
                {"springgreen", "minecraft:lime_concrete"},
                {"mediumspringgreen", "minecraft:lime_concrete"},
                {"palegreen", "minecraft:lime_concrete"},
                {"lightgreen", "minecraft:lime_concrete"},
                {"olive", "minecraft:green_terracotta"},
                {"olivedrab", "minecraft:green_terracotta"},
                {"darkolivegreen", "minecraft:green_terracotta"},
                {"darkseagreen", "minecraft:green_terracotta"},
                {"brown", "minecraft:brown_concrete"},
                {"saddlebrown", "minecraft:brown_concrete"},
                {"sienna", "minecraft:brown_terracotta"},
                {"chocolate", "minecraft:brown_concrete"},
                {"peru", "minecraft:brown_terracotta"},
                {"tan", "minecraft:white_terracotta"},
                {"rosybrown", "minecraft:brown_terracotta"},
                {"sandybrown", "minecraft:brown_terracotta"},
                {"burlywood", "minecraft:brown_terracotta"},
                {"cream", "minecraft:white_concrete"},
                {"beige2", "minecraft:white_terracotta"},
                {"navyblue", "minecraft:blue_concrete"},
                {"darkgrey", "minecraft:gray_concrete"},
                {"lightgrey", "minecraft:light_gray_concrete"},
                {"gold",            "minecraft:gold_block"},
                {"golden",          "minecraft:gold_block"},
                {"goldenrod",       "minecraft:yellow_concrete"},
                {"silver",          "minecraft:iron_block"},
                {"platinum",        "minecraft:iron_block"},
                {"gunmetal",        "minecraft:gray_concrete"},
                {"pewter",          "minecraft:gray_concrete"},
                {"bronze",          "minecraft:orange_terracotta"},
                {"brass",           "minecraft:yellow_terracotta"},
                {"copper",          "minecraft:waxed_cut_copper"},
                {"slategray",         "minecraft:gray_concrete"},
                {"slategrey",         "minecraft:gray_concrete"},
                {"lightslategray",    "minecraft:light_gray_concrete"},
                {"lightslategrey",    "minecraft:light_gray_concrete"},
                {"darkslategray",     "minecraft:gray_concrete"},
                {"darkslategrey",     "minecraft:gray_concrete"},
                {"lightcyan",         "minecraft:light_blue_concrete"},
                {"darkcyan",          "minecraft:cyan_concrete"},
                {"mediumaquamarine",  "minecraft:cyan_terracotta"},
                {"moccasin",          "minecraft:orange_terracotta"},
                {"mistyrose",         "minecraft:pink_terracotta"},
                {"lightcoral",        "minecraft:pink_concrete"},
                {"darkorange",        "minecraft:orange_concrete"},
                {"darkkhaki",         "minecraft:yellow_terracotta"},
                {"yellowgreen",       "minecraft:lime_concrete"},
                {"darkgoldenrod",     "minecraft:yellow_terracotta"},
                {"bluegrey",          "minecraft:light_blue_concrete"},
                {"bluegray",          "minecraft:light_blue_concrete"},
                {"nickel",          "minecraft:iron_block"},
                {"verdigris",       "minecraft:waxed_oxidized_cut_copper"},
                {"gilded",          "minecraft:gold_block"},
                {"gilt",            "minecraft:gold_block"},
                {"lead",            "minecraft:gray_concrete"},
                {"steel",           "minecraft:iron_block"},
                {"chrome",          "minecraft:iron_block"},
                {"chromium",        "minecraft:iron_block"},
                {"aluminium",       "minecraft:iron_block"},
                {"aluminum",        "minecraft:iron_block"},
                {"zinc",            "minecraft:iron_block"},
                {"tin",             "minecraft:iron_block"},
                {"titanium",        "minecraft:iron_block"},
        });

        // ----- Мэппинг имён цветов СТЕКЛА (CSS/X11 + частые синонимы) -> stained/tinted glass -----
        putAll(GLASS_COLOR_NAME_TO_BLOCK, new Object[][]{
                {"clear",               "minecraft:glass"},
                {"transparent",         "minecraft:glass"},
                {"translucent",         "minecraft:glass"},
                {"untinted",            "minecraft:glass"},
                {"tinted",              "minecraft:tinted_glass"},
                {"smoke",               "minecraft:tinted_glass"},
                {"smoked",              "minecraft:tinted_glass"},
                {"smoky",               "minecraft:tinted_glass"},
                {"bronze_glass",        "minecraft:brown_stained_glass"},
                {"frosted",             "minecraft:white_stained_glass"},
                {"etched",              "minecraft:white_stained_glass"},
                {"mirror",              "minecraft:light_gray_stained_glass"},
                {"white",               "minecraft:white_stained_glass"},
                {"snow",                "minecraft:white_stained_glass"},
                {"whitesmoke",          "minecraft:white_stained_glass"},
                {"ghostwhite",          "minecraft:white_stained_glass"},
                {"ivory",               "minecraft:white_stained_glass"},
                {"floralwhite",         "minecraft:white_stained_glass"},
                {"seashell",            "minecraft:white_stained_glass"},
                {"honeydew",            "minecraft:white_stained_glass"},
                {"mintcream",           "minecraft:white_stained_glass"},
                {"oldlace",             "minecraft:white_stained_glass"},
                {"linen",               "minecraft:white_stained_glass"},
                {"beige",               "minecraft:white_stained_glass"},
                {"antiquewhite",        "minecraft:white_stained_glass"},
                {"cornsilk",            "minecraft:yellow_stained_glass"},
                {"papayawhip",          "minecraft:white_stained_glass"},
                {"blanchedalmond",      "minecraft:white_stained_glass"},
                {"bisque",              "minecraft:white_stained_glass"},
                {"peachpuff",           "minecraft:white_stained_glass"},
                {"navajowhite",         "minecraft:white_stained_glass"},
                {"gainsboro",           "minecraft:light_gray_stained_glass"},
                {"lightgray",           "minecraft:light_gray_stained_glass"},
                {"lightgrey",           "minecraft:light_gray_stained_glass"},
                {"silver",              "minecraft:light_gray_stained_glass"},
                {"gray",                "minecraft:gray_stained_glass"},
                {"grey",                "minecraft:gray_stained_glass"},
                {"darkgray",            "minecraft:gray_stained_glass"},
                {"darkgrey",            "minecraft:gray_stained_glass"},
                {"dimgray",             "minecraft:gray_stained_glass"},
                {"dimgrey",             "minecraft:gray_stained_glass"},
                {"black",               "minecraft:black_stained_glass"},
                {"lemonchiffon",        "minecraft:yellow_stained_glass"},
                {"lightyellow",         "minecraft:yellow_stained_glass"},
                {"lightgoldenrodyellow","minecraft:yellow_stained_glass"},
                {"palegoldenrod",       "minecraft:yellow_stained_glass"},
                {"khaki",               "minecraft:yellow_stained_glass"},
                {"gold",                "minecraft:yellow_stained_glass"},
                {"golden",              "minecraft:yellow_stained_glass"},
                {"goldenrod",           "minecraft:yellow_stained_glass"},
                {"yellow",              "minecraft:yellow_stained_glass"},
                {"darkgoldenrod",       "minecraft:yellow_stained_glass"},
                {"orange",              "minecraft:orange_stained_glass"},
                {"orangered",           "minecraft:orange_stained_glass"},
                {"darkorange",          "minecraft:orange_stained_glass"},
                {"moccasin",            "minecraft:orange_stained_glass"},
                {"coral",               "minecraft:orange_stained_glass"},
                {"lightsalmon",         "minecraft:orange_stained_glass"},
                {"red",                 "minecraft:red_stained_glass"},
                {"darkred",             "minecraft:red_stained_glass"},
                {"firebrick",           "minecraft:red_stained_glass"},
                {"crimson",             "minecraft:red_stained_glass"},
                {"indianred",           "minecraft:red_stained_glass"},
                {"tomato",              "minecraft:red_stained_glass"},
                {"salmon",              "minecraft:pink_stained_glass"},
                {"darksalmon",          "minecraft:pink_stained_glass"},
                {"pink",                "minecraft:pink_stained_glass"},
                {"lightpink",           "minecraft:pink_stained_glass"},
                {"hotpink",             "minecraft:pink_stained_glass"},
                {"deeppink",            "minecraft:pink_stained_glass"},
                {"lavenderblush",       "minecraft:pink_stained_glass"},
                {"palevioletred",       "minecraft:magenta_stained_glass"},
                {"mediumvioletred",     "minecraft:magenta_stained_glass"},
                {"magenta",             "minecraft:magenta_stained_glass"},
                {"fuchsia",             "minecraft:magenta_stained_glass"},
                {"violet",              "minecraft:purple_stained_glass"},
                {"orchid",              "minecraft:magenta_stained_glass"},
                {"thistle",             "minecraft:purple_stained_glass"},
                {"rebeccapurple",       "minecraft:purple_stained_glass"},
                {"purple",              "minecraft:purple_stained_glass"},
                {"blueviolet",          "minecraft:purple_stained_glass"},
                {"darkmagenta",         "minecraft:magenta_stained_glass"},
                {"darkviolet",          "minecraft:purple_stained_glass"},
                {"darkorchid",          "minecraft:purple_stained_glass"},
                {"mediumorchid",        "minecraft:magenta_stained_glass"},
                {"mediumpurple",        "minecraft:purple_stained_glass"},
                {"indigo",              "minecraft:purple_stained_glass"},
                {"slateblue",           "minecraft:blue_stained_glass"},
                {"darkslateblue",       "minecraft:purple_stained_glass"},
                {"mediumslateblue",     "minecraft:blue_stained_glass"},
                {"blue",                "minecraft:blue_stained_glass"},
                {"mediumblue",          "minecraft:blue_stained_glass"},
                {"darkblue",            "minecraft:blue_stained_glass"},
                {"midnightblue",        "minecraft:blue_stained_glass"},
                {"navy",                "minecraft:blue_stained_glass"},
                {"royalblue",           "minecraft:blue_stained_glass"},
                {"cornflowerblue",      "minecraft:blue_stained_glass"},
                {"dodgerblue",          "minecraft:blue_stained_glass"},
                {"deepskyblue",         "minecraft:light_blue_stained_glass"},
                {"lightskyblue",        "minecraft:light_blue_stained_glass"},
                {"skyblue",             "minecraft:light_blue_stained_glass"},
                {"lightblue",           "minecraft:light_blue_stained_glass"},
                {"powderblue",          "minecraft:light_blue_stained_glass"},
                {"aliceblue",           "minecraft:light_blue_stained_glass"},
                {"azure",               "minecraft:light_blue_stained_glass"},
                {"lightsteelblue",      "minecraft:light_blue_stained_glass"},
                {"bluegrey",            "minecraft:light_blue_stained_glass"},
                {"bluegray",            "minecraft:light_blue_stained_glass"},
                {"aqua",                "minecraft:cyan_stained_glass"},
                {"cyan",                "minecraft:cyan_stained_glass"},
                {"turquoise",           "minecraft:cyan_stained_glass"},
                {"mediumturquoise",     "minecraft:cyan_stained_glass"},
                {"darkturquoise",       "minecraft:cyan_stained_glass"},
                {"paleturquoise",       "minecraft:light_blue_stained_glass"},
                {"lightseagreen",       "minecraft:cyan_stained_glass"},
                {"cadetblue",           "minecraft:cyan_stained_glass"},
                {"teal",                "minecraft:cyan_stained_glass"},
                {"aquamarine",          "minecraft:cyan_stained_glass"},
                {"lightcyan",           "minecraft:light_blue_stained_glass"},
                {"darkcyan",            "minecraft:cyan_stained_glass"},
                {"mediumaquamarine",    "minecraft:cyan_stained_glass"},
                {"steelblue",           "minecraft:cyan_stained_glass"},
                {"green",               "minecraft:green_stained_glass"},
                {"darkgreen",           "minecraft:green_stained_glass"},
                {"forestgreen",         "minecraft:green_stained_glass"},
                {"seagreen",            "minecraft:green_stained_glass"},
                {"mediumseagreen",      "minecraft:green_stained_glass"},
                {"lime",                "minecraft:lime_stained_glass"},
                {"limegreen",           "minecraft:lime_stained_glass"},
                {"lawngreen",           "minecraft:lime_stained_glass"},
                {"chartreuse",          "minecraft:lime_stained_glass"},
                {"greenyellow",         "minecraft:lime_stained_glass"},
                {"springgreen",         "minecraft:lime_stained_glass"},
                {"mediumspringgreen",   "minecraft:lime_stained_glass"},
                {"palegreen",           "minecraft:lime_stained_glass"},
                {"lightgreen",          "minecraft:lime_stained_glass"},
                {"olive",               "minecraft:green_stained_glass"},
                {"olivedrab",           "minecraft:green_stained_glass"},
                {"darkolivegreen",      "minecraft:green_stained_glass"},
                {"darkseagreen",        "minecraft:green_stained_glass"},
                {"verdigris",           "minecraft:cyan_stained_glass"},
                {"brown",               "minecraft:brown_stained_glass"},
                {"saddlebrown",         "minecraft:brown_stained_glass"},
                {"sienna",              "minecraft:brown_stained_glass"},
                {"chocolate",           "minecraft:brown_stained_glass"},
                {"peru",                "minecraft:brown_stained_glass"},
                {"tan",                 "minecraft:brown_stained_glass"},
                {"rosybrown",           "minecraft:brown_stained_glass"},
                {"sandybrown",          "minecraft:brown_stained_glass"},
                {"burlywood",           "minecraft:brown_stained_glass"},
                {"bronze",              "minecraft:brown_stained_glass"},
                {"brass",               "minecraft:yellow_stained_glass"},
                {"copper",              "minecraft:orange_stained_glass"},
                {"silver_color",        "minecraft:light_gray_stained_glass"},
                {"platinum",            "minecraft:light_gray_stained_glass"},
                {"gunmetal",            "minecraft:gray_stained_glass"},
                {"pewter",              "minecraft:gray_stained_glass"},
                {"cream",               "minecraft:white_stained_glass"},
                {"beige2",              "minecraft:white_stained_glass"},
                {"navyblue",            "minecraft:blue_stained_glass"},
                {"slategray",           "minecraft:gray_stained_glass"},
                {"slategrey",           "minecraft:gray_stained_glass"},
                {"lightslategray",      "minecraft:light_gray_stained_glass"},
                {"lightslategrey",      "minecraft:light_gray_stained_glass"},
                {"darkslategray",       "minecraft:gray_stained_glass"},
                {"darkslategrey",       "minecraft:gray_stained_glass"}
        });

        // ----- RGB палитра для ближайшего HEX-матчинга (Concrete/ Terracotta/ Wool) -----
        putConcretePaletteRGB();
        putTerracottaPaletteRGB();
        putWoolPaletteRGB();
        putStainedGlassPaletteRGB();
    }

    private static void putConcretePaletteRGB() {
        putRGB("minecraft:white_concrete",       207, 213, 214);
        putRGB("minecraft:light_gray_concrete",  142, 142, 134);
        putRGB("minecraft:gray_concrete",        55,  58,  62);
        putRGB("minecraft:black_concrete",       8,   10,  15);
        putRGB("minecraft:red_concrete",         142, 33,  33);
        putRGB("minecraft:orange_concrete",      224, 97,  0);
        putRGB("minecraft:yellow_concrete",      241, 175, 21);
        putRGB("minecraft:lime_concrete",        94,  168, 24);
        putRGB("minecraft:green_concrete",       73,  91,  36);
        putRGB("minecraft:cyan_concrete",        21,  119, 136);
        putRGB("minecraft:light_blue_concrete",  36,  137, 199);
        putRGB("minecraft:blue_concrete",        44,  46,  143);
        putRGB("minecraft:purple_concrete",      100, 31,  156);
        putRGB("minecraft:magenta_concrete",     169, 48,  159);
        putRGB("minecraft:pink_concrete",        214, 101, 143);
        putRGB("minecraft:brown_concrete",       96,  60,  32);
    }
    private static void putTerracottaPaletteRGB() {
        putRGB("minecraft:terracotta",           152, 94, 67);
        putRGB("minecraft:white_terracotta",     209, 178, 161);
        putRGB("minecraft:light_gray_terracotta",135, 107, 98);
        putRGB("minecraft:gray_terracotta",      58,  42,  36);
        putRGB("minecraft:black_terracotta",     37,  22,  16);
        putRGB("minecraft:red_terracotta",       142, 60,  46);
        putRGB("minecraft:orange_terracotta",    162, 84,  38);
        putRGB("minecraft:yellow_terracotta",    186, 133, 36);
        putRGB("minecraft:lime_terracotta",      103, 117, 52);
        putRGB("minecraft:green_terracotta",     76,  83,  42);
        putRGB("minecraft:cyan_terracotta",      86,  91,  91);
        putRGB("minecraft:light_blue_terracotta",113, 108, 137);
        putRGB("minecraft:blue_terracotta",      74,  59,  91);
        putRGB("minecraft:purple_terracotta",    118, 70,  86);
        putRGB("minecraft:magenta_terracotta",   149, 88,  108);
        putRGB("minecraft:pink_terracotta",      160, 78,  78);
        putRGB("minecraft:brown_terracotta",     77,  51,  35);
    }
    private static void putWoolPaletteRGB() {
        putRGB("minecraft:white_wool",           234, 236, 237);
        putRGB("minecraft:light_gray_wool",      142, 142, 134);
        putRGB("minecraft:gray_wool",            62,  68,  71);
        putRGB("minecraft:black_wool",           22,  22,  26);
        putRGB("minecraft:red_wool",             160, 39,  34);
        putRGB("minecraft:orange_wool",          235, 125, 27);
        putRGB("minecraft:yellow_wool",          248, 198, 40);
        putRGB("minecraft:lime_wool",            112, 185, 25);
        putRGB("minecraft:green_wool",           84,  110, 38);
        putRGB("minecraft:cyan_wool",            21,  137, 145);
        putRGB("minecraft:light_blue_wool",      58,  175, 217);
        putRGB("minecraft:blue_wool",            60,  68,  170);
        putRGB("minecraft:purple_wool",          137, 50,  184);
        putRGB("minecraft:magenta_wool",         191, 75,  201);
        putRGB("minecraft:pink_wool",            237, 141, 172);
        putRGB("minecraft:brown_wool",           126, 84,  48);
    }
    private static void putStainedGlassPaletteRGB() {
        putRGB("minecraft:white_stained_glass",       242, 243, 244);
        putRGB("minecraft:light_gray_stained_glass",  185, 185, 180);
        putRGB("minecraft:gray_stained_glass",        76,  76,  76);
        putRGB("minecraft:black_stained_glass",       25,  25,  25);
        putRGB("minecraft:red_stained_glass",         160, 39,  34);
        putRGB("minecraft:orange_stained_glass",      235, 125, 27);
        putRGB("minecraft:yellow_stained_glass",      248, 198, 40);
        putRGB("minecraft:lime_stained_glass",        112, 185, 25);
        putRGB("minecraft:green_stained_glass",       84,  110, 38);
        putRGB("minecraft:cyan_stained_glass",        21,  137, 145);
        putRGB("minecraft:light_blue_stained_glass",  58,  175, 217);
        putRGB("minecraft:blue_stained_glass",        60,  68,  170);
        putRGB("minecraft:purple_stained_glass",      137, 50,  184);
        putRGB("minecraft:magenta_stained_glass",     191, 75,  201);
        putRGB("minecraft:pink_stained_glass",        237, 141, 172);
        putRGB("minecraft:brown_stained_glass",       126, 84,  48);
    }
    private static void putRGB(String id, int r, int g, int b) {
        BLOCK_RGB.put(id, new int[]{r,g,b});
    }

    private static final Set<String> METAL_MATERIALS = new HashSet<>(Arrays.asList(
            "metal","steel","iron","stainless_steel","chrome","chromium","aluminium","aluminum",
            "zinc","tin","silver","titanium","nickel","lead","copper","bronze","brass",
            "gold","gilded","gilt"
    ));

    private static final Set<String> STONE_LIKE_MATERIALS = new HashSet<>(Arrays.asList(
            "stone","granite","limestone","sandstone","marble","deepslate","brick","brickwork",
            "panel","composite","quartz","quartz_block"
    ));
    @SuppressWarnings("unused")
    /** Материалы фасада, которые логично "красить" в цвет тега (если указан): */
    private static final Set<String> TINTABLE_FACADE_MATERIALS = new HashSet<>(Arrays.asList(
            "concrete","plaster","stucco","render","adobe","clay","wood","timber","panel","composite","glass"
    ));
    @SuppressWarnings("unused")
    /** Материалы крыши, для которых цвет уместен: */
    private static final Set<String> TINTABLE_ROOF_MATERIALS = new HashSet<>(Arrays.asList(
            "tile","shingle","concrete","clay","glass","asphalt"
    ));

    private static final boolean STRICT_TERRAIN = true;
    // Игнорим vanilla-лимит, строим высоко. Если твой мод реально снимает лимит — он примет эти Y.
    private static final boolean IGNORE_WORLD_LIMIT = true;
    private static final int SOFT_MAX_Y = 8192; 

    private static final Set<String> STRUCTURAL_HEIGHT_KEYS = new HashSet<>(Arrays.asList(
        "height","building:height",
        "building:levels","levels","building:levels:aboveground",
        "min_level","building:min_level",
        "min_height","building:min_height",
        "roof:height","roof:levels"
    ));

    // Родительские "оболочки" зданий (их заливки + теги)
    private static final class Shell {
        Set<Long> fill;
        JsonObject tags;
    }
    private final List<Shell> parentShells = new ArrayList<>();

    // Ключи внешности, которые наследуем
    private static final String[] FAC_MAT_KEYS = new String[]{
            "building:material","material","facade:material","building:facade:material",
            "cladding","building:cladding","facade:cladding","wall:material","building:wall:material"
    };
    private static final String[] FAC_COL_KEYS = new String[]{
            "building:colour","building:color","colour","color",
            "facade:colour","facade:color","building:facade:colour","building:facade:color"
    };
    private static final String[] ROOF_MAT_KEYS = new String[]{
            "roof:material"
    };
    private static final String[] ROOF_COL_KEYS = new String[]{
            "roof:colour","roof:color"
    };

    private boolean hasNonBlank(JsonObject o, String key){
        if (o == null || !o.has(key) || o.get(key).isJsonNull()) return false;
        try {
            if (o.get(key).isJsonPrimitive()) {
                String s = o.get(key).getAsString();
                return s != null && !s.trim().isEmpty();
            }
        } catch (Throwable ignore) {}
        return true; // если это не примитив — считаем заданным
    }

    private void copyIfMissing(JsonObject dst, JsonObject src, String... keys){
        if (dst == null || src == null) return;
        for (String k : keys){
            if (!hasNonBlank(dst, k) && hasNonBlank(src, k)){
                dst.add(k, src.get(k));
            }
        }
    }

    /** Наследуем внешний вид части от родителя, который геометрически её содержит.
     *  Копируем ТОЛЬКО материал/цвет фасада и крыши, причём по каждому ключу — если у части он отсутствует. */
    private JsonObject inheritAppearanceFromParent(JsonObject partTags, Set<Long> partFill){
        if (partTags == null || partFill == null || partFill.isEmpty()) return partTags;

        // Сначала быстрый тест по центру (оставим — вдруг сработает)
        int[] bb = bounds(partFill);
        int cx = (bb[0] + bb[2]) >> 1, cz = (bb[1] + bb[3]) >> 1;
        long cKey = BlockPos.asLong(cx, 0, cz);

        for (Shell sh : parentShells){
            if (sh.fill == null || sh.fill.isEmpty()) continue;

            boolean matches = sh.fill.contains(cKey);

            // Надёжный фолбэк: есть ли пересечение областей?
            if (!matches) {
                for (long k : partFill) {
                    if (sh.fill.contains(k)) { matches = true; break; }
                }
            }

            if (matches) {
                JsonObject merged = partTags.deepCopy();
                // Фасад
                copyIfMissing(merged, sh.tags, FAC_MAT_KEYS);
                copyIfMissing(merged, sh.tags, FAC_COL_KEYS);
                // Крыша (на всякий случай)
                copyIfMissing(merged, sh.tags, ROOF_MAT_KEYS);
                copyIfMissing(merged, sh.tags, ROOF_COL_KEYS);
                return merged;
            }
        }
        return partTags;
    }

    private static final boolean FORCE_GROUND_ANCHOR = true;

    private int worldTopCap() {
        return IGNORE_WORLD_LIMIT ? SOFT_MAX_Y : (level.getMaxBuildHeight() - 1);
    }

    // ====== ДАЛЬШЕ — ВСЯ ЛОГИКА ГЕНЕРАЦИИ ======

    private final ServerLevel level;
    private final JsonObject coords;

    // Снимок высоты поверхности ДО начала строительства (по XZ)
    private final Map<Long, Integer> groundSnapshot = new HashMap<>();

    // Все XZ-клетки, занятые building:part (защита от перезаписи общим контуром)
    private final Set<Long> partFootprint = new HashSet<>();

    public BuildingGenerator(ServerLevel level, JsonObject coords) {
        this.level = level;
        this.coords = coords;
    }

    // Взвешенный дефолт фасада, если в OSM не указаны ни материал, ни цвет
    private String pickWeightedDefaultFacade() {
        class W { final String id; final double w; W(String id, double w){ this.id=id; this.w=w; } }

        List<W> options = new ArrayList<>();
        options.add(new W("minecraft:polished_diorite",      0.20));
        options.add(new W("minecraft:diorite",               0.20)); 
        options.add(new W("minecraft:quartz_bricks",         0.20)); 
        options.add(new W("minecraft:bricks",                0.05)); 
        options.add(new W("minecraft:end_stone_bricks",      0.05)); 
        options.add(new W("minecraft:light_gray_concrete",   0.05)); 
        options.add(new W("minecraft:calcite",               0.05)); 
        options.add(new W("minecraft:bone_block",            0.05));
        options.add(new W("minecraft:cut_sandstone",         0.05));
        options.add(new W("minecraft:white_terracotta",      0.05));
        options.add(new W("minecraft:light_gray_terracotta", 0.05));

        // Оставляем только зарегистрированные блоки, чтобы не свалиться в STONE при resolveBlock
        double sum = 0.0;
        List<W> valid = new ArrayList<>();
        for (W w : options) {
            if (ForgeRegistries.BLOCKS.getValue(ResourceLocation.tryParse(w.id)) != null) {
                valid.add(w);
                sum += w.w;
            }
        }
        if (valid.isEmpty()) return DEFAULT_FACADE_BLOCK;

        double r = Math.random() * sum;
        double acc = 0.0;
        for (W w : valid) {
            acc += w.w;
            if (r <= acc) return w.id;
        }
        return valid.get(valid.size() - 1).id;
    }

    // --- широковещалка
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

    // ======== ПУБЛИЧНЫЙ ЗАПУСК ========
    public void generate() {
        broadcast(level, "🏗️ Генерация зданий…");

        if (coords == null || !coords.has("features")) {
            broadcast(level, "В coords нет features — пропускаю BuildingGenerator.");
            return;
        }

        JsonObject center = coords.getAsJsonObject("center");
        JsonObject bbox   = coords.getAsJsonObject("bbox");

        final double centerLat   = center.get("lat").getAsDouble();
        final double centerLng   = center.get("lng").getAsDouble();
        final int    sizeMeters  = coords.get("sizeMeters").getAsInt();

        final double south = bbox.get("south").getAsDouble();
        final double north = bbox.get("north").getAsDouble();
        final double west  = bbox.get("west").getAsDouble();
        final double east  = bbox.get("east").getAsDouble();

        final int centerX = coords.has("player")
                ? (int)Math.round(coords.getAsJsonObject("player").get("x").getAsDouble())
                : 0;
        final int centerZ = coords.has("player")
                ? (int)Math.round(coords.getAsJsonObject("player").get("z").getAsDouble())
                : 0;

        int[] a = latlngToBlock(south, west, centerLat, centerLng, east, west, north, south, sizeMeters, centerX, centerZ);
        int[] b = latlngToBlock(north, east,  centerLat, centerLng, east, west, north, south, sizeMeters, centerX, centerZ);
        final int minX = Math.min(a[0], b[0]);
        final int maxX = Math.max(a[0], b[0]);
        final int minZ = Math.min(a[1], b[1]);
        final int maxZ = Math.max(a[1], b[1]);

        // Сделать снимок исходного рельефа, чтобы не опираться на уже построенные блоки
        snapshotGround(minX, maxX, minZ, maxZ);

        JsonArray elements = coords.getAsJsonObject("features").getAsJsonArray("elements");
        if (elements == null || elements.size() == 0) {
            broadcast(level, "OSM elements пуст — пропускаю здания.");
            return;
        }

        // === NEW: индекс по id и набор way, которые участвуют в relation, чтобы не дублировать
        Map<Long, JsonObject> byId = new HashMap<>();
        for (JsonElement je : elements) {
            JsonObject e = je.getAsJsonObject();
            if (e.has("id")) byId.put(e.get("id").getAsLong(), e);
        }
        Set<Long> waysUsedInRelations = new HashSet<>();

        collectParentShells(elements, byId,
            centerLat, centerLng, east, west, north, south,
            sizeMeters, centerX, centerZ,
            minX, maxX, minZ, maxZ);
        
        // Соберём заранее узлы entrance=* и линии tunnel=building_passage
        List<int[]> entrances = collectEntrances(elements, centerLat, centerLng, east, west, north, south, sizeMeters, centerX, centerZ);
        List<List<int[]>> passages = collectPassages(elements, centerLat, centerLng, east, west, north, south, sizeMeters, centerX, centerZ);
        // === NEW: копим части, чтобы потом построить снизу вверх
        List<PartTask> partTasks = new ArrayList<>();


        // === NEW: сначала relation с building:part
        for (JsonElement je : elements) {
            JsonObject e = je.getAsJsonObject();
            if (!"relation".equals(optString(e, "type"))) continue;
            JsonObject tags = (e.has("tags") && e.get("tags").isJsonObject()) ? e.getAsJsonObject("tags") : null;
            if (tags == null || !isBuildingPart(tags)) continue;

            MultiPoly mp = assembleMultiPoly(e, byId, centerLat, centerLng, east, west, north, south, sizeMeters, centerX, centerZ, waysUsedInRelations);
            if (mp != null && !mp.outers.isEmpty()) {
                Set<Long> fill = rasterizeMultiPolygon(mp.outers, mp.inners, minX, maxX, minZ, maxZ);
                partFootprint.addAll(fill);
                JsonObject effTags = (mp.tags != null ? mp.tags : tags);
                effTags = inheritAppearanceFromParent(effTags, fill); // НАСЛЕДОВАНИЕ
                int mo = effectiveMinOffsetForPart(effTags);
                partTasks.add(new PartTask(fill, mp.outers, effTags, mo));
            }        
        }

  

        // 1) Сначала building:part (детализация)
        int totalParts = countBy(elements, t -> isBuildingPart(t));
        int processed = 0;
        for (JsonElement je : elements) {
            JsonObject e = je.getAsJsonObject();
            JsonObject tags = (e.has("tags") && e.get("tags").isJsonObject()) ? e.getAsJsonObject("tags") : null;
            if (tags == null) continue;
            if (!isBuildingPart(tags)) continue;
            if (!"way".equals(optString(e, "type"))) continue;
            Long id = e.has("id") ? e.get("id").getAsLong() : null;
            if (id != null && waysUsedInRelations.contains(id)) continue;
            JsonArray geom = e.getAsJsonArray("geometry");
            if (geom == null || geom.size() < 3) continue;

            List<int[]> ring = toRingTiles(geom, centerLat, centerLng, east, west, north, south, sizeMeters, centerX, centerZ);
            Set<Long> partFill = rasterizePolygon(ring, minX, maxX, minZ, maxZ);
            partFootprint.addAll(partFill);
            JsonObject effPartTags = inheritAppearanceFromParent(tags, partFill); // НАСЛЕДОВАНИЕ
            Set<Long> fill = rasterizePolygon(ring, minX, maxX, minZ, maxZ);
            partFootprint.addAll(fill);
            int mo = effectiveMinOffsetForPart(effPartTags);
            List<List<int[]>> outers = new ArrayList<>();
            outers.add(ring);
            partTasks.add(new PartTask(fill, outers, effPartTags, mo));

            processed++;
            if (totalParts > 0 && processed % Math.max(1, totalParts/10) == 0) {
                int pct = (int)Math.round(100.0 * processed / Math.max(1,totalParts));
                broadcast(level, "Здания (части): ~" + pct + "%");
            }
        }

        // === NEW: строим ВСЕ части снизу вверх (без зазоров на стыке)
        partTasks.sort(Comparator.comparingInt(t -> t.minOffsetSort));
        for (PartTask t : partTasks) {
            buildFromFill(t.fill, t.outers, t.tags,
                        entrances, passages, minX, maxX, minZ, maxZ, /*airOnly=*/false);
        }

        // === NEW: затем relation с building=*
        for (JsonElement je : elements) {
            JsonObject e = je.getAsJsonObject();
            if (!"relation".equals(optString(e, "type"))) continue;
            JsonObject tags = (e.has("tags") && e.get("tags").isJsonObject()) ? e.getAsJsonObject("tags") : null;
            if (tags == null || !(isBuilding(tags) || isCanopy(tags))) continue;

            MultiPoly mp = assembleMultiPoly(e, byId, centerLat, centerLng, east, west, north, south, sizeMeters, centerX, centerZ, waysUsedInRelations);
            if (mp == null || mp.outers.isEmpty()) continue;

            JsonObject eff = (mp.tags != null ? mp.tags : tags);
            if (isCanopy(eff)) {
                for (List<int[]> o : mp.outers) buildCanopy(o, eff, minX, maxX, minZ, maxZ);
            } else {
                Set<Long> fill = rasterizeMultiPolygon(mp.outers, mp.inners, minX, maxX, minZ, maxZ);
                fill.removeAll(partFootprint);
                if (!fill.isEmpty()) {
                    // ⬇️ ДОБАВЛЕНО: вывод этажности/высоты из внутренних объектов
                    eff = augmentHeightFromInteriorIfMissing(
                            eff, fill, elements,
                            centerLat, centerLng, east, west, north, south,
                            sizeMeters, centerX, centerZ
                    );
                    buildFromFill(fill, mp.outers, eff, entrances, passages, minX, maxX, minZ, maxZ, false);
                }
            }
        }  

        // 2) Затем building=* (общие контуры); объём заполняем только там, где ещё воздух — чтобы не перекрыть части
        int totalBuildings = countBy(elements, t -> isBuilding(t));
        processed = 0;
        for (JsonElement je : elements) {
            JsonObject e = je.getAsJsonObject();
            JsonObject tags = (e.has("tags") && e.get("tags").isJsonObject()) ? e.getAsJsonObject("tags") : null;
            if (tags == null) continue;
            if (!isBuilding(tags)) continue;

            // навесы отдельно
            if (isCanopy(tags)) {
                if (!"way".equals(optString(e, "type"))) continue;
                Long id = e.has("id") ? e.get("id").getAsLong() : null;
                if (id != null && waysUsedInRelations.contains(id)) continue;
                JsonArray geom = e.getAsJsonArray("geometry");
                if (geom == null || geom.size() < 3) continue;
                List<int[]> ring = toRingTiles(geom, centerLat, centerLng, east, west, north, south, sizeMeters, centerX, centerZ);
                buildCanopy(ring, tags, minX, maxX, minZ, maxZ);
            } else {
                if (!"way".equals(optString(e, "type"))) continue;
                Long id = e.has("id") ? e.get("id").getAsLong() : null;
                if (id != null && waysUsedInRelations.contains(id)) continue;
                JsonArray geom = e.getAsJsonArray("geometry");
                if (geom == null || geom.size() < 3) continue;
                List<int[]> ring = toRingTiles(geom, centerLat, centerLng, east, west, north, south, sizeMeters, centerX, centerZ);
                Set<Long> fill = rasterizePolygon(ring, minX, maxX, minZ, maxZ);
                fill.removeAll(partFootprint);
                if (!fill.isEmpty()) {
                    List<List<int[]>> outers = new ArrayList<>();
                    outers.add(ring);
                    // ⬇️ ДОБАВЛЕНО: вывод этажности/высоты из внутренних объектов
                    JsonObject eff = augmentHeightFromInteriorIfMissing(
                            tags, fill, elements,
                            centerLat, centerLng, east, west, north, south,
                            sizeMeters, centerX, centerZ
                    );
                    buildFromFill(fill, outers, eff, entrances, passages, minX, maxX, minZ, maxZ, /*airOnly=*/false);
                }
            }

            processed++;
            if (totalBuildings > 0 && processed % Math.max(1, totalBuildings/10) == 0) {
                int pct = (int)Math.round(100.0 * processed / Math.max(1,totalBuildings));
                broadcast(level, "Здания (контуры): ~" + pct + "%");
            }
        }

        broadcast(level, "Здания готовы.");
    }

    @SuppressWarnings("unused")
    // Перегрузка: для общего контура — ставить блоки только в воздух (airOnly=true)
    private void buildOneBuilding(List<int[]> ring, JsonObject tags,
                                List<int[]> entrances, List<List<int[]>> passages,
                                int minX, int maxX, int minZ, int maxZ) {
        buildOneBuilding(ring, tags, entrances, passages, minX, maxX, minZ, maxZ, /*airOnly=*/false);
    }

    // === NEW: собрать мультиполигон из relation (со сшивкой ways в кольца)
    private MultiPoly assembleMultiPoly(JsonObject relation,
                                        Map<Long, JsonObject> byId,
                                        double centerLat, double centerLng,
                                        double east, double west, double north, double south,
                                        int sizeMeters, int centerX, int centerZ,
                                        Set<Long> waysUsedInRelations) {
        if (!relation.has("members") || !relation.get("members").isJsonArray()) return null;
        JsonArray members = relation.getAsJsonArray("members");

        
        JsonObject tags = relation.getAsJsonObject("tags");

        // 1) Собираем «сегменты» (последовательности lat/lon) отдельно для outer и inner
        List<List<LatLon>> outerSegs = new ArrayList<>();
        List<List<LatLon>> innerSegs = new ArrayList<>();
        List<JsonObject> outerMemberTags = new ArrayList<>();

        for (JsonElement mEl : members) {
            JsonObject m = mEl.getAsJsonObject();
            String role = optString(m, "role");
            String mType = optString(m, "type");
            Long ref = m.has("ref") ? m.get("ref").getAsLong() : null;

            JsonArray geom = null;
            JsonObject way = null; // <— будем доставать теги way
            if (m.has("geometry") && m.get("geometry").isJsonArray()) {
                geom = m.getAsJsonArray("geometry");
            } else if ("way".equals(mType) && ref != null && byId.containsKey(ref)) {
                way = byId.get(ref);
                if (way.has("geometry") && way.get("geometry").isJsonArray()) {
                    geom = way.getAsJsonArray("geometry");
                }
            }
            if (geom == null || geom.size() < 2) continue;


            List<LatLon> seg = new ArrayList<>(geom.size());
            LatLon prev = null;
            for (JsonElement gp : geom) {
                JsonObject p = gp.getAsJsonObject();
                LatLon cur = new LatLon(p.get("lat").getAsDouble(), p.get("lon").getAsDouble());
                // убираем подряд идущие дубликаты
                if (prev == null || !prev.equalsEps(cur)) {
                    seg.add(cur);
                    prev = cur;
                }
            }
            if (seg.size() < 2) continue;

            if (isPartRole(role)) {
                    // parts не участвуют в контуре relation и не помечаем их used — их строит проход по way.
                continue;
            }

            if (isInnerRole(role)) {
                innerSegs.add(seg);
                if ("way".equals(mType) && ref != null) waysUsedInRelations.add(ref);
            } else if (isOuterLikeRole(role)) {
                outerSegs.add(seg);
                if (way != null && way.has("tags") && way.get("tags").isJsonObject()) {
                    outerMemberTags.add(way.getAsJsonObject("tags"));
                }
                if ("way".equals(mType) && ref != null) waysUsedInRelations.add(ref);
            } else {
                // неизвестная роль — считаем наружной
                outerSegs.add(seg);
                if (way != null && way.has("tags") && way.get("tags").isJsonObject()) {
                    outerMemberTags.add(way.getAsJsonObject("tags"));
                }
                if ("way".equals(mType) && ref != null) waysUsedInRelations.add(ref);
            }
        }

        // 2) Сшиваем сегменты в замкнутые кольца (в lat/lon, с допуском)
        List<List<LatLon>> outerRingsLL = stitchRings(outerSegs);
        List<List<LatLon>> innerRingsLL = stitchRings(innerSegs);

        // 3) Конвертируем кольца в X/Z-блоки и замыкаем уже в клетках
        MultiPoly mp = new MultiPoly();
        mp.tags = mergeBuildingTags(tags, outerMemberTags);

        for (List<LatLon> rr : outerRingsLL) {
            List<int[]> ring = ringLatLonToBlocks(rr, centerLat, centerLng, east, west, north, south, sizeMeters, centerX, centerZ);
            if (ring.size() >= 4) mp.outers.add(ring);
        }
        for (List<LatLon> rr : innerRingsLL) {
            List<int[]> ring = ringLatLonToBlocks(rr, centerLat, centerLng, east, west, north, south, sizeMeters, centerX, centerZ);
            if (ring.size() >= 4) mp.inners.add(ring);
        }
        return mp;
    }

    private void collectParentShells(JsonArray elements,
                                    Map<Long, JsonObject> byId,
                                    double centerLat, double centerLng,
                                    double east, double west, double north, double south,
                                    int sizeMeters, int centerX, int centerZ,
                                    int minX, int maxX, int minZ, int maxZ){
        parentShells.clear();

        // 1) Relations с building=* (НЕ части)
        for (JsonElement je : elements) {
            JsonObject e = je.getAsJsonObject();
            if (!"relation".equals(optString(e, "type"))) continue;
            JsonObject tags = (e.has("tags") && e.get("tags").isJsonObject()) ? e.getAsJsonObject("tags") : null;
            if (tags == null || !isBuilding(tags) || isBuildingPart(tags)) continue;

            MultiPoly mp = assembleMultiPoly(e, byId, centerLat, centerLng, east, west, north, south, sizeMeters, centerX, centerZ, new HashSet<>());
            if (mp == null || mp.outers.isEmpty()) continue;

            Set<Long> fill = rasterizeMultiPolygon(mp.outers, mp.inners, minX, maxX, minZ, maxZ);
            if (fill.isEmpty()) continue;

            Shell sh = new Shell();
            sh.fill = fill;
            sh.tags = (mp.tags != null ? mp.tags : tags).deepCopy();
            parentShells.add(sh);
        }

        // 2) Ways с building=* (НЕ части). Удобно на случай, когда здание задано одним контуром
        for (JsonElement je : elements) {
            JsonObject e = je.getAsJsonObject();
            if (!"way".equals(optString(e, "type"))) continue;
            JsonObject tags = (e.has("tags") && e.get("tags").isJsonObject()) ? e.getAsJsonObject("tags") : null;
            if (tags == null || !isBuilding(tags) || isBuildingPart(tags)) continue;

            JsonArray geom = e.getAsJsonArray("geometry");
            if (geom == null || geom.size() < 3) continue;

            List<int[]> ring = toRingTiles(geom, centerLat, centerLng, east, west, north, south, sizeMeters, centerX, centerZ);
            Set<Long> fill = rasterizePolygon(ring, minX, maxX, minZ, maxZ);
            if (fill.isEmpty()) continue;

            Shell sh = new Shell();
            sh.fill = fill;
            sh.tags = tags.deepCopy();
            parentShells.add(sh);
        }
    }

    private static boolean isInnerRole(String role) {
        if (role == null) return false;
        role = role.trim().toLowerCase(Locale.ROOT);
        return role.equals("inner") || role.equals("hole");
    }
    private static boolean isOuterLikeRole(String role) {
        if (role == null) return true; // пустая роль = наружу
        role = role.trim().toLowerCase(Locale.ROOT);
        // "outline" — это то же, что "outer" у type=building
        return role.equals("") || role.equals("outer") || role.equals("outline")
            || role.equals("exterior") || role.equals("shell");
    }
    private static boolean isPartRole(String role) {
        if (role == null) return false;
        role = role.trim().toLowerCase(Locale.ROOT);
        return role.equals("part") || role.equals("building:part") || role.equals("element");
    }

    // допуск ~ 1e-7 градуса (~1 см) достаточно; можно 1e-6 если данные «шумят»
    private static final double LL_EPS = 1e-6;

    private static class LatLon {
        final double lat, lon;
        LatLon(double lat, double lon) { this.lat = lat; this.lon = lon; }
        boolean equalsEps(LatLon o) {
            return Math.abs(lat - o.lat) <= LL_EPS && Math.abs(lon - o.lon) <= LL_EPS;
        }
    }

    // Сшить набор открытых способов в замкнутые кольца.
    // Умеет разворачивать сегменты и приклеивать к голове/хвосту текущего кольца.
    private List<List<LatLon>> stitchRings(List<List<LatLon>> segs) {
        List<List<LatLon>> pool = new ArrayList<>();
        for (List<LatLon> s : segs) if (s.size() >= 2) pool.add(new ArrayList<>(s));
        List<List<LatLon>> rings = new ArrayList<>();
        while (!pool.isEmpty()) {
            List<LatLon> cur = new ArrayList<>(pool.remove(pool.size() - 1));
            boolean progress;
            do {
                progress = false;
                LatLon head = cur.get(0);
                LatLon tail = cur.get(cur.size() - 1);
                for (int i = pool.size() - 1; i >= 0; i--) {
                    List<LatLon> s = pool.get(i);
                    LatLon sHead = s.get(0), sTail = s.get(s.size() - 1);
                    if (tail.equalsEps(sHead)) {
                        // ...cur + s
                        cur.addAll(s.subList(1, s.size()));
                    } else if (tail.equalsEps(sTail)) {
                        // ...cur + reverse(s)
                        Collections.reverse(s);
                        cur.addAll(s.subList(1, s.size()));
                    } else if (head.equalsEps(sTail)) {
                        // s + cur...
                        cur.addAll(0, s.subList(0, s.size() - 1));
                    } else if (head.equalsEps(sHead)) {
                        // reverse(s) + cur...
                        Collections.reverse(s);
                        cur.addAll(0, s.subList(0, s.size() - 1));
                    } else {
                        continue;
                    }
                    pool.remove(i);
                    progress = true;
                    break;
                }
            } while (progress);

            // Закрываем, если почти замкнуто
            LatLon first = cur.get(0), last = cur.get(cur.size() - 1);
            if (!first.equalsEps(last)) {
                // попытка замкнуть — просто пришиваем первую точку
                cur.add(first);
            }
            if (cur.size() < 4) continue;
            if (cur.size() >= 4) rings.add(cur);
        }
        return rings;
    }

    private List<int[]> ringLatLonToBlocks(List<LatLon> ringLL,
                                        double centerLat, double centerLng,
                                        double east, double west, double north, double south,
                                        int sizeMeters, int centerX, int centerZ) {
        List<int[]> ring = new ArrayList<>(ringLL.size());
        int[] prev = null;
        for (LatLon p : ringLL) {
            int[] xz = latlngToBlock(p.lat, p.lon, centerLat, centerLng, east, west, north, south, sizeMeters, centerX, centerZ);
            if (prev == null || prev[0] != xz[0] || prev[1] != xz[1]) {
                ring.add(xz);
                prev = xz;
            }
        }
        // гарантируем замыкание в клетках
        if (ring.size() >= 2) {
            int[] f = ring.get(0), l = ring.get(ring.size() - 1);
            if (f[0] != l[0] || f[1] != l[1]) ring.add(new int[]{f[0], f[1]});
        }
        return ring;
    }

    // ====== КЛЮЧЕВАЯ ЛОГИКА: ОДНО ЗДАНИЕ ======

    private void buildOneBuilding(List<int[]> ring, JsonObject tags,
                                List<int[]> entrances, List<List<int[]>> passages,
                                int minX, int maxX, int minZ, int maxZ,
                                boolean airOnly) {
        if (ring == null || ring.size() < 3) return;

        // 1) Растеризуем контур части в набор XZ-клеток
        Set<Long> fill = rasterizePolygon(ring, minX, maxX, minZ, maxZ);
        if (fill.isEmpty()) return;

        // 2) Собираем список outer-колец (для совместимости с мультиполигоном)
        List<List<int[]>> outers = new ArrayList<>(1);
        outers.add(ring);

        // 3) Строим через ОДНУ функцию, как и обычные здания:
        // одинаковый рельеф (terrainGrid/snapshot), одинаковая расчистка/стены/крыша/двери.
        buildFromFill(fill, outers, tags, entrances, passages, minX, maxX, minZ, maxZ, airOnly);
    }

    // ====== НАВЕСЫ (building=roof / man_made=canopy) ======
    private void buildCanopy(List<int[]> ring, JsonObject tags,
                            int minX, int maxX, int minZ, int maxZ) {
        if (ring == null || ring.size() < 3) return;

        Set<Long> fill = rasterizePolygon(ring, minX, maxX, minZ, maxZ);
        if (fill.isEmpty()) return;

        // запасной y для случаев вне снимка (берём по центроиду контура)
        int[] c = centroid(ring);
        int fallbackSurfY = terrainYFromCoordsOrWorld(c[0], c[1], null);
        if (fallbackSurfY == Integer.MIN_VALUE) return;

        Block roof   = resolveBlock(pickRoofBlock(tags));
        Block pillar = Blocks.SMOOTH_STONE;

        // 1) Крыша: для каждой клетки — на высоте (рельеф + 6)
        for (long key : fill) {
            int x = BlockPos.getX(key);
            int z = BlockPos.getZ(key);
            int ySurf = terrainYFromCoordsOrWorld(x, z, fallbackSurfY);
            if (ySurf == Integer.MIN_VALUE) continue;

            int yRoof = ySurf + 1 + CANOPY_OFFSET_BLOCKS;
            level.setBlock(new BlockPos(x, yRoof, z), roof.defaultBlockState(), 3);
        }

        // 2) Опоры: по периметру каждые 5 блоков — от земли до низа крыши
        Set<Long> edge = edgeOfFill(fill);
        int placed = 0;
        for (long key : edge) {
            int x = BlockPos.getX(key);
            int z = BlockPos.getZ(key);

            // дискретизация «каждые 5 блоков» вдоль периметра
            if (Math.floorMod(x + z, CANOPY_SUPPORT_STEP) != 0) continue;

            int ySurf = terrainYFromCoordsOrWorld(x, z, fallbackSurfY);
            if (ySurf == Integer.MIN_VALUE) continue;

            int yRoof = ySurf + 1 + CANOPY_OFFSET_BLOCKS;
            for (int y = ySurf + 1; y < yRoof; y++) {
                level.setBlock(new BlockPos(x, y, z), pillar.defaultBlockState(), 3);
            }
            placed++;
        }

        // На очень маленьких навесах модуль может не поймать точку шага — подстрахуемся углами
        if (placed == 0 && !edge.isEmpty()) {
            for (long[] corner : pickFourCorners(edge)) {
                int cx = (int) corner[0], cz = (int) corner[1];
                int ySurf = terrainYFromCoordsOrWorld(cx, cz, fallbackSurfY);
                if (ySurf == Integer.MIN_VALUE) continue;
                int yRoof = ySurf + 1 + CANOPY_OFFSET_BLOCKS;
                for (int y = ySurf + 1; y < yRoof; y++) {
                    level.setBlock(new BlockPos(cx, y, cz), pillar.defaultBlockState(), 3);
                }
            }
        }
    }

    // ПРОХОДЫ: режем динамической высотой (8, если здание >15 блоков; иначе 4)
    // и делаем ширину 4 блока поперёк линии (ориентируемся по доминирующей оси сегмента)
    private void carvePassages(Set<Long> buildingFill, List<List<int[]>> passages,
                            int unusedMinOffset, int buildingHeightBlocks, int fallbackSurfY) {
        if (passages == null || passages.isEmpty()) return;
        final int worldMax = worldTopCap();
        final int H = Math.max(1, computePassageClearHeight(Math.max(1, buildingHeightBlocks)));

        final int W = Math.max(1, PASSAGE_WIDTH_BLOCKS);
        final int tFrom = -(W/2 - 1);  // для W=4 → -1
        final int tTo   =  (W/2);      // для W=4 →  2

        for (List<int[]> linePts : passages) {
            for (int i = 1; i < linePts.size(); i++) {
                int[] a = linePts.get(i - 1), b = linePts.get(i);
                int dxa = Math.abs(b[0] - a[0]);
                int dza = Math.abs(b[1] - a[1]);

                // поперечное направление к сегменту (доминирующая ось определяет перпендикуляр)
                // если сегмент «горизонтальный» → перпендикул. идёт по Z; если «вертикальный» → по X
                final int nx = (dxa >= dza) ? 0 : 1; // 0 = не смещаем X, 1 = смещаем X
                final int nz = (dxa >= dza) ? 1 : 0; // 1 = смещаем Z, 0 = не смещаем Z

                List<int[]> seg = bresenhamLine(a[0], a[1], b[0], b[1]);
                for (int[] p : seg) {
                    int x = p[0], z = p[1];

                    for (int t = tFrom; t <= tTo; t++) {
                        int xx = x + t * nx;
                        int zz = z + t * nz;
                        if (!containsXZ(buildingFill, xx, zz)) continue;

                        int ySurf = terrainYFromCoordsOrWorld(xx, zz, fallbackSurfY);
                        if (ySurf == Integer.MIN_VALUE) continue;

                        int y0 = ySurf + 1;
                        int y1 = Math.min(worldMax, y0 + H - 1);
                        for (int y = y0; y <= y1; y++) {
                            level.setBlock(new BlockPos(xx, y, zz), Blocks.AIR.defaultBlockState(), 3);
                        }
                    }
                }
            }
        }
    }
    
    // Каменный «рукав» вокруг прохода той же ширины и высоты, что и carvePassages
    private void dressPassagesAsStoneSleeve(Set<Long> buildingFill, List<List<int[]>> passages,
                                            int unusedMinOffset, int buildingHeightBlocks, int fallbackSurfY) {
        if (passages == null || passages.isEmpty()) return;

        final int worldMax = worldTopCap();
        final int H = Math.max(1, computePassageClearHeight(Math.max(1, buildingHeightBlocks)));

        Block brick = resolveBlock("minecraft:stone_bricks");
        Block glow  = resolveBlock("minecraft:glowstone");

        final int W = Math.max(1, PASSAGE_WIDTH_BLOCKS);
        final int tFrom = -(W/2 - 1);
        final int tTo   =  (W/2);

        // 1) Собираем клетки тоннеля с новой шириной
        Set<Long> tunnel = new HashSet<>();
        for (List<int[]> linePts : passages) {
            for (int i = 1; i < linePts.size(); i++) {
                int[] a = linePts.get(i - 1), b = linePts.get(i);
                int dxa = Math.abs(b[0] - a[0]);
                int dza = Math.abs(b[1] - a[1]);

                final int nx = (dxa >= dza) ? 0 : 1;
                final int nz = (dxa >= dza) ? 1 : 0;

                for (int[] p : bresenhamLine(a[0], a[1], b[0], b[1])) {
                    int x = p[0], z = p[1];
                    for (int t = tFrom; t <= tTo; t++) {
                        int xx = x + t * nx;
                        int zz = z + t * nz;
                        if (containsXZ(buildingFill, xx, zz)) {
                            tunnel.add(BlockPos.asLong(xx, 0, zz));
                        }
                    }
                }
            }
        }
        if (tunnel.isEmpty()) return;

        int[][] dirs = new int[][]{{1,0},{-1,0},{0,1},{0,-1}};

        // 2) Потолок + стены вокруг
        for (long key : tunnel) {
            int x = BlockPos.getX(key), z = BlockPos.getZ(key);
            int ySurf = terrainYFromCoordsOrWorld(x, z, fallbackSurfY);
            if (ySurf == Integer.MIN_VALUE) continue;

            int y0   = ySurf + 1;
            int yTop = Math.min(worldMax, y0 + H - 1);

            // Потолок: верхняя полоса рукава; каждые 5 блоков — свет
            BlockPos ceil = new BlockPos(x, yTop, z);
            boolean lamp = (Math.floorMod(x + z, 5) == 0);
            level.setBlock(ceil, (lamp ? glow : brick).defaultBlockState(), 3);

            // Стены: один блок толщины по границе тоннеля
            for (int[] d : dirs) {
                int sx = x + d[0], sz = z + d[1];
                long nkey = BlockPos.asLong(sx, 0, sz);
                if (!containsXZ(buildingFill, sx, sz)) continue;
                if (tunnel.contains(nkey)) continue; // внутри — не стена

                for (int y = y0; y <= yTop - 1; y++) {
                    level.setBlock(new BlockPos(sx, y, sz), brick.defaultBlockState(), 3);
                }
            }
        }
    }

    // ====== ДВЕРИ ПО entrance=* ======
    private void placeDoorsOnEntrances(Set<Long> edge, Set<Long> fill, List<int[]> entrances,
                                    int minOffset, int fallbackSurfY) {
        if (entrances == null || entrances.isEmpty() || edge == null || edge.isEmpty()) return;

        // Быстрая структура для поиска ближайшей точки ребра
        List<int[]> edgeList = new ArrayList<>(edge.size());
        for (long k : edge) edgeList.add(new int[]{BlockPos.getX(k), BlockPos.getZ(k)});

        // Чтобы не ставить двери в одну и ту же клетку
        Set<Long> usedDoorSpots = new HashSet<>();

        for (int[] ent : entrances) {
            int ex = ent[0], ez = ent[1];

            // Вход должен находиться внутри полигона здания (или вплотную к нему)
            boolean inside = fill.contains(BlockPos.asLong(ex,0,ez));
            if (!inside) {
                // допустим "рядом": максимум в 2 блока от границы
                int near2 = 2*2;
                boolean near = false;
                for (int[] e : edgeList) {
                    int dx = e[0] - ex, dz = e[1] - ez;
                    if (dx*dx + dz*dz <= near2) { near = true; break; }
                }
                if (!near) continue; // этот entrance к этому зданию не относится
            }

            // Ищем ближайшую клетку ребра
            int bestIdx = -1, bestDist = Integer.MAX_VALUE;
            for (int i = 0; i < edgeList.size(); i++) {
                int dx = edgeList.get(i)[0] - ex;
                int dz = edgeList.get(i)[1] - ez;
                int d2 = dx*dx + dz*dz;
                if (d2 < bestDist) { bestDist = d2; bestIdx = i; }
            }
            if (bestIdx < 0) continue;

            // Слишком далеко — пропускаем (шум/чужой вход). Порог 5 блоков.
            if (bestDist > 25) continue;

            int x = edgeList.get(bestIdx)[0];
            int z = edgeList.get(bestIdx)[1];

            long spot = BlockPos.asLong(x,0,z);
            if (usedDoorSpots.contains(spot)) continue; // уже ставили рядом дверь
            usedDoorSpots.add(spot);

            Direction facing = guessDoorFacing(edge, x, z);
            int yBase = localWallBaseYAt(x, z, minOffset, fallbackSurfY);

            // На всякий — очищаем проём
            level.setBlock(new BlockPos(x, yBase + 1, z), Blocks.AIR.defaultBlockState(), 3);
            level.setBlock(new BlockPos(x, yBase + 2, z), Blocks.AIR.defaultBlockState(), 3);

            placeDoorDarkOak(x, yBase, z, facing);
            ensureDoorLanding(x, z, facing, yBase);
        }
    }

    // простая подступенька у двери, чтобы не было «ступеньки в яму»
    private void ensureDoorLanding(int x, int z, Direction facing, int yBase) {
        int ox = x + facing.getStepX();
        int oz = z + facing.getStepZ();
        int outSurf = terrainYFromCoordsOrWorld(ox, oz, yBase);
        if (outSurf == Integer.MIN_VALUE) return;

        int need = (yBase) - (outSurf + 1); // сколько блоков не хватает до порога
        if (need <= 0) return;              // нормально, не ниже
        // заполняем до порога простыми ступеньками/блоками
        BlockState stairs = Blocks.STONE_STAIRS.defaultBlockState();
        if (stairs.hasProperty(StairBlock.FACING)) stairs = stairs.setValue(StairBlock.FACING, facing);
        int y = outSurf + 1;
        for (int i = 0; i < Math.min(2, need); i++) {
            level.setBlock(new BlockPos(ox, y + i, oz), (i==need-1 ? stairs : Blocks.COBBLESTONE.defaultBlockState()), 3);
        }
    }

    private Direction guessDoorFacing(Set<Long> edge, int x, int z) {
        // если сосед за стеной отсутствует — это наружу
        if (!edge.contains(BlockPos.asLong(x+1,0,z))) return Direction.EAST;
        if (!edge.contains(BlockPos.asLong(x-1,0,z))) return Direction.WEST;
        if (!edge.contains(BlockPos.asLong(x,0,z+1))) return Direction.SOUTH;
        return Direction.NORTH;
    }

    private void placeDoorDarkOak(int x, int yBase, int z, Direction facing) {
        Block doorBlock = Blocks.DARK_OAK_DOOR;
        BlockState stBottom = doorBlock.defaultBlockState();
        if (stBottom.hasProperty(DoorBlock.FACING)) {
            stBottom = stBottom.setValue(DoorBlock.FACING, facing);
        }
        if (stBottom.hasProperty(DoorBlock.HALF)) {
            stBottom = stBottom.setValue(DoorBlock.HALF, DoubleBlockHalf.LOWER);
        }
        if (stBottom.hasProperty(DoorBlock.HINGE)) {
            stBottom = stBottom.setValue(DoorBlock.HINGE, DoorHingeSide.LEFT);
        }
        BlockState stTop = doorBlock.defaultBlockState();
        if (stTop.hasProperty(DoorBlock.FACING)) {
            stTop = stTop.setValue(DoorBlock.FACING, facing);
        }
        if (stTop.hasProperty(DoorBlock.HALF)) {
            stTop = stTop.setValue(DoorBlock.HALF, DoubleBlockHalf.UPPER);
        }
        if (stTop.hasProperty(DoorBlock.HINGE)) {
            stTop = stTop.setValue(DoorBlock.HINGE, DoorHingeSide.LEFT);
        }

        level.setBlock(new BlockPos(x, yBase + 1, z), stBottom, 3);
        level.setBlock(new BlockPos(x, yBase + 2, z), stTop, 3);
    }

    // ====== КРЫШИ ======

    // Локальная плоская крыша: от локального верха стен
    private void buildRoofFlat(Set<Long> fill, int minOffset, int facadeBlocks, int roofBlocks,
                            Block roof, boolean unusedAirOnly, int fallbackSurfY) {
        if (roofBlocks <= 0) return;
        final int worldMax = worldTopCap();
        int thickness = Math.max(1, roofBlocks);

        for (long key : fill) {
            int x = BlockPos.getX(key), z = BlockPos.getZ(key);
            int yBaseTop = localWallTopYAt(x, z, minOffset, facadeBlocks, fallbackSurfY);
            int y0 = Math.min(worldMax, yBaseTop + 1);
            int y1 = Math.min(worldMax, y0 + thickness - 1);
            for (int y = y0; y <= y1; y++) {
                level.setBlock(new BlockPos(x, y, z), roof.defaultBlockState(), 3);
            }
        }
    }

    private void buildRoofGabled(Set<Long> fill, int minOffset, int facadeBlocks, int roofBlocks,
                             Block roof, JsonObject tags, boolean unusedAirOnly, int fallbackSurfY) {
        if (roofBlocks <= 0) return;
        double rad = resolveRoofDirectionRad(tags, fill);
        double vx = Math.cos(rad), vz = Math.sin(rad);

        int[] bb = bounds(fill);
        double cx = (bb[0] + bb[2]) / 2.0;
        double cz = (bb[1] + bb[3]) / 2.0;

        double nx = -vz, nz = vx; // нормаль
        double maxAbs = 1.0;
        for (long k : fill) {
            double px = BlockPos.getX(k) - cx;
            double pz = BlockPos.getZ(k) - cz;
            double t = Math.abs(px * nx + pz * nz);
            if (t > maxAbs) maxAbs = t;
        }

        for (long k : fill) {
            int x = BlockPos.getX(k), z = BlockPos.getZ(k);
            double px = x - cx, pz = z - cz;
            double t = Math.abs(px * nx + pz * nz) / maxAbs; // [0..1] от конька к краю
            int dh = (int)Math.round((1.0 - t) * roofBlocks); // выше в центре, ниже на краях
            for (int dy = 1; dy <= dh; dy++) {
                int yBaseTop = localWallTopYAt(x, z, minOffset, facadeBlocks, fallbackSurfY);
                BlockPos pos = new BlockPos(x, yBaseTop + dy, z);
                level.setBlock(pos, roof.defaultBlockState(), 3);
            }
        }
    }

    private void buildRoofSkillion(Set<Long> fill, int minOffset, int facadeBlocks, int roofBlocks,
                             Block roof, JsonObject tags, boolean unusedAirOnly, int fallbackSurfY) {
        if (roofBlocks <= 0) return;
        double rad = resolveRoofDirectionRad(tags, fill);
        double vx = Math.cos(rad), vz = Math.sin(rad);
        
        int[] bb = bounds(fill);
        double cx = (bb[0] + bb[2]) / 2.0;
        double cz = (bb[1] + bb[3]) / 2.0;

        double maxProj = 1.0, minProj = 0.0;
        boolean init = false;
        for (long k : fill) {
            double px = BlockPos.getX(k) - cx;
            double pz = BlockPos.getZ(k) - cz;
            double proj = px * vx + pz * vz;
            if (!init) { minProj = maxProj = proj; init = true; }
            else { if (proj < minProj) minProj = proj; if (proj > maxProj) maxProj = proj; }
        }
        double span = Math.max(1.0, maxProj - minProj);

        for (long k : fill) {
            int x = BlockPos.getX(k), z = BlockPos.getZ(k);
            double px = x - cx, pz = z - cz;
            double proj = px * vx + pz * vz;
            double t = (proj - minProj) / span; // 0..1
            int dh = (int)Math.round(t * roofBlocks);
            int hh = Math.max(1, dh);
            for (int dy = 1; dy <= hh; dy++) {
                int yBaseTop = localWallTopYAt(x, z, minOffset, facadeBlocks, fallbackSurfY);
                BlockPos pos = new BlockPos(x, yBaseTop + dy, z);
                level.setBlock(pos, roof.defaultBlockState(), 3);
            }
        }
    }

    private void buildRoofPyramidal(Set<Long> fill, int minOffset, int facadeBlocks, int roofBlocks,
                             Block roof, JsonObject tags, boolean unusedAirOnly, int fallbackSurfY) {
        if (roofBlocks <= 0) return;
        int[] bb = bounds(fill);
        double cx = (bb[0] + bb[2]) / 2.0;
        double cz = (bb[1] + bb[3]) / 2.0;

        double maxR = 1.0;
        for (long k : fill) {
            double dx = Math.abs(BlockPos.getX(k) - cx);
            double dz = Math.abs(BlockPos.getZ(k) - cz);
            double r = Math.max(dx, dz);
            if (r > maxR) maxR = r;
        }

        for (long k : fill) {
            int x = BlockPos.getX(k), z = BlockPos.getZ(k);
            double r = Math.max(Math.abs(x - cx), Math.abs(z - cz)) / maxR; // 0..1
            int dh = (int)Math.round((1.0 - r) * roofBlocks);
            for (int dy = 1; dy <= dh; dy++) {
                int yBaseTop = localWallTopYAt(x, z, minOffset, facadeBlocks, fallbackSurfY);
                BlockPos pos = new BlockPos(x, yBaseTop + dy, z);
                level.setBlock(pos, roof.defaultBlockState(), 3);
            }
        }
    }


    // ---------- ВСПОМОГАТЕЛЬНОЕ ДЛЯ КРЫШ ----------
    private double[] centerOf(Set<Long> fill) {
        int[] bb = bounds(fill);
        return new double[]{ (bb[0] + bb[2]) / 2.0, (bb[1] + bb[3]) / 2.0 };
    }
    private double[] projSpan(Set<Long> fill, double vx, double vz, double cx, double cz) {
        double minProj = 0, maxProj = 0; boolean init = false;
        for (long k : fill) {
            double px = BlockPos.getX(k) - cx;
            double pz = BlockPos.getZ(k) - cz;
            double proj = px * vx + pz * vz;
            if (!init) { minProj = maxProj = proj; init = true; }
            else { if (proj < minProj) minProj = proj; if (proj > maxProj) maxProj = proj; }
        }
        return new double[]{minProj, maxProj, Math.max(1e-6, maxProj - minProj)};
    }
    private double maxChebyshevR(Set<Long> fill, double cx, double cz) {
        double maxR = 1.0;
        for (long k : fill) {
            double dx = Math.abs(BlockPos.getX(k) - cx);
            double dz = Math.abs(BlockPos.getZ(k) - cz);
            double r = Math.max(dx, dz);
            if (r > maxR) maxR = r;
        }
        return maxR;
    }
    private static double triangleWave(double x) {
        // x in [0..1] -> /\  (0->0, 0.5->1, 1->0)
        x = x - Math.floor(x);
        return 1.0 - Math.abs(2.0*x - 1.0);
    }

    // ---------- ДОП. ФОРМЫ КРЫШ ----------

    private void buildRoofHip(Set<Long> fill, int minOffset, int facadeBlocks, int roofBlocks,
                             Block roof, JsonObject tags, boolean unusedAirOnly, int fallbackSurfY) {
        if (roofBlocks <= 0) return;
        double rad = resolveRoofDirectionRad(tags, fill);
        double vx = Math.cos(rad), vz = Math.sin(rad);
        double nx = -vz, nz = vx;                        // поперёк (скаты)
        double[] c = centerOf(fill);
        double cx = c[0], cz = c[1];

        // поперёк конька (скаты) — как у gabled
        double maxAbs = 1.0;
        for (long k : fill) {
            double px = BlockPos.getX(k) - cx, pz = BlockPos.getZ(k) - cz;
            double t = Math.abs(px*nx + pz*nz);
            if (t > maxAbs) maxAbs = t;
        }
        // вдоль конька — слегка “подхиповываем” концы
        double[] span = projSpan(fill, vx, vz, cx, cz);
        @SuppressWarnings("unused")
        double minL = span[0], maxL = span[1], L = span[2];

        for (long k : fill) {
            int x = BlockPos.getX(k), z = BlockPos.getZ(k);
            double px = x - cx, pz = z - cz;
            double tAcross = Math.abs(px*nx + pz*nz) / maxAbs; // 0..1 от конька к краю
            double tAlong  = ((px*vx + pz*vz) - minL) / L;     // 0..1 вдоль конька
            double hipFactor = 1.0 - 0.3 * Math.min(tAlong, 1.0 - tAlong); // ниже к концам
            int dh = (int)Math.round((1.0 - tAcross) * roofBlocks * hipFactor);

            for (int dy = 1; dy <= dh; dy++) {
                int yBaseTop = localWallTopYAt(x, z, minOffset, facadeBlocks, fallbackSurfY);
                BlockPos pos = new BlockPos(x, yBaseTop + dy, z);
                level.setBlock(pos, roof.defaultBlockState(), 3);
            }
        }
    }

    private void buildRoofHalfHip(Set<Long> fill, int minOffset, int facadeBlocks, int roofBlocks,
                             Block roof, JsonObject tags, boolean unusedAirOnly, int fallbackSurfY) {
        if (roofBlocks <= 0) return;
        double rad = resolveRoofDirectionRad(tags, fill);
        double vx = Math.cos(rad), vz = Math.sin(rad);
        double nx = -vz, nz = vx;
        double[] c = centerOf(fill); double cx = c[0], cz = c[1];

        double maxAbs = 1.0;
        for (long k : fill) {
            double px = BlockPos.getX(k) - cx, pz = BlockPos.getZ(k) - cz;
            double t = Math.abs(px*nx + pz*nz);
            if (t > maxAbs) maxAbs = t;
        }
        double[] span = projSpan(fill, vx, vz, cx, cz);
        @SuppressWarnings("unused")
        double minL = span[0], maxL = span[1], L = span[2];

        for (long k : fill) {
            int x = BlockPos.getX(k), z = BlockPos.getZ(k);
            double px = x - cx, pz = z - cz;
            double tAcross = Math.abs(px*nx + pz*nz) / maxAbs;
            double tAlong  = ((px*vx + pz*vz) - minL) / L;
            // gabled-профиль + маленькие "полухипы" по краям
            int base = (int)Math.round((1.0 - tAcross) * roofBlocks);
            int cut  = (int)Math.round(0.4 * roofBlocks * Math.min(tAlong, 1.0 - tAlong));
            int dh = Math.max(1, base - cut);

            for (int dy = 1; dy <= dh; dy++) {
                int yBaseTop = localWallTopYAt(x, z, minOffset, facadeBlocks, fallbackSurfY);
                BlockPos pos = new BlockPos(x, yBaseTop + dy, z);
                level.setBlock(pos, roof.defaultBlockState(), 3);
            }
        }
    }

    private void buildRoofGambrel(Set<Long> fill, int minOffset, int facadeBlocks, int roofBlocks,
                             Block roof, JsonObject tags, boolean unusedAirOnly, int fallbackSurfY) {
        if (roofBlocks <= 0) return;
        double rad = resolveRoofDirectionRad(tags, fill);
        double vx = Math.cos(rad), vz = Math.sin(rad);
        double nx = -vz, nz = vx;
        double[] c = centerOf(fill); double cx = c[0], cz = c[1];

        double maxAbs = 1.0;
        for (long k : fill) {
            double px = BlockPos.getX(k) - cx, pz = BlockPos.getZ(k) - cz;
            double t = Math.abs(px*nx + pz*nz);
            if (t > maxAbs) maxAbs = t;
        }

        for (long k : fill) {
            int x = BlockPos.getX(k), z = BlockPos.getZ(k);
            double px = x - cx, pz = z - cz;
            double t = Math.abs(px*nx + pz*nz) / maxAbs; // 0..1 от конька к краю
            // двухломаная: у края круче, ближе к центру — положе
            double u = 1.0 - t; // 1 в центре
            double profile = (u < 0.5) ? (0.6*u) : (0.6*0.5 + (u-0.5)*0.4/0.5); // 60% + 40%
            int dh = Math.max(1, (int)Math.round(profile * roofBlocks));

            for (int dy = 1; dy <= dh; dy++) {
                int yBaseTop = localWallTopYAt(x, z, minOffset, facadeBlocks, fallbackSurfY);
                BlockPos pos = new BlockPos(x, yBaseTop + dy, z);
                level.setBlock(pos, roof.defaultBlockState(), 3);
            }
        }
    }

    private void buildRoofMansard(Set<Long> fill, int minOffset, int facadeBlocks, int roofBlocks,
                             Block roof, JsonObject tags, boolean unusedAirOnly, int fallbackSurfY) {
        if (roofBlocks <= 0) return;
        double[] c = centerOf(fill); double cx = c[0], cz = c[1];
        double R = maxChebyshevR(fill, cx, cz);

        for (long k : fill) {
            int x = BlockPos.getX(k), z = BlockPos.getZ(k);
            double r = Math.max(Math.abs(x - cx), Math.abs(z - cz)) / R; // 0..1
            double u = 1.0 - r;
            // ломанная “по кругу”: у кромки круто (мансардный излом), дальше ровнее
            double profile = (u < 0.4) ? (0.7*u/0.4) : (0.7 + (u-0.4)*0.3/0.6);
            int dh = Math.max(1, (int)Math.round(profile * roofBlocks));
            for (int dy = 1; dy <= dh; dy++) {
                int yBaseTop = localWallTopYAt(x, z, minOffset, facadeBlocks, fallbackSurfY);
                BlockPos pos = new BlockPos(x, yBaseTop + dy, z);
                level.setBlock(pos, roof.defaultBlockState(), 3);
            }
        }
    }

    private void buildRoofDome(Set<Long> fill, int minOffset, int facadeBlocks, int roofBlocks,
                             Block roof, JsonObject tags, boolean unusedAirOnly, int fallbackSurfY) {
        if (roofBlocks <= 0) return;
        double[] c = centerOf(fill); double cx = c[0], cz = c[1];
        double R = maxChebyshevR(fill, cx, cz);

        for (long k : fill) {
            int x = BlockPos.getX(k), z = BlockPos.getZ(k);
            double r = Math.max(Math.abs(x - cx), Math.abs(z - cz)) / R; // 0..1
            double cap = Math.max(0.0, 1.0 - r*r); // параболический купол
            int dh = Math.max(1, (int)Math.round(cap * roofBlocks));
            for (int dy = 1; dy <= dh; dy++) {
                int yBaseTop = localWallTopYAt(x, z, minOffset, facadeBlocks, fallbackSurfY);
                BlockPos pos = new BlockPos(x, yBaseTop + dy, z);
                level.setBlock(pos, roof.defaultBlockState(), 3);
            }
        }
    }

    private void buildRoofOnion(Set<Long> fill, int minOffset, int facadeBlocks, int roofBlocks,
                             Block roof, JsonObject tags, boolean unusedAirOnly, int fallbackSurfY) {
        if (roofBlocks <= 0) return;
        double[] c = centerOf(fill); double cx = c[0], cz = c[1];
        double R = maxChebyshevR(fill, cx, cz);

        for (long k : fill) {
            int x = BlockPos.getX(k), z = BlockPos.getZ(k);
            double r = Math.max(Math.abs(x - cx), Math.abs(z - cz)) / R; // 0..1
            // луковица: купол + утолщение в средней зоне
            double dome = Math.max(0.0, 1.0 - r*r);
            double bulge = Math.max(0.0, Math.sin(Math.PI * (1.0 - r))) * 0.35; // “поясок”
            double h = Math.min(1.0, dome + bulge);
            int dh = Math.max(1, (int)Math.round(h * roofBlocks));
            for (int dy = 1; dy <= dh; dy++) {
                int yBaseTop = localWallTopYAt(x, z, minOffset, facadeBlocks, fallbackSurfY);
                BlockPos pos = new BlockPos(x, yBaseTop + dy, z);
                level.setBlock(pos, roof.defaultBlockState(), 3);
            }
        }
    }

    private void buildRoofConical(Set<Long> fill, int minOffset, int facadeBlocks, int roofBlocks,
                             Block roof, JsonObject tags, boolean unusedAirOnly, int fallbackSurfY) {
        if (roofBlocks <= 0) return;
        double[] c = centerOf(fill); double cx = c[0], cz = c[1];
        double R = maxChebyshevR(fill, cx, cz);

        for (long k : fill) {
            int x = BlockPos.getX(k), z = BlockPos.getZ(k);
            double r = Math.max(Math.abs(x - cx), Math.abs(z - cz)) / R; // 0..1
            int dh = Math.max(1, (int)Math.round((1.0 - r) * roofBlocks));
            for (int dy = 1; dy <= dh; dy++) {
                int yBaseTop = localWallTopYAt(x, z, minOffset, facadeBlocks, fallbackSurfY);
                BlockPos pos = new BlockPos(x, yBaseTop + dy, z);
                level.setBlock(pos, roof.defaultBlockState(), 3);
            }
        }
    }

    private void buildRoofSawtooth(Set<Long> fill, int minOffset, int facadeBlocks, int roofBlocks,
                             Block roof, JsonObject tags, boolean unusedAirOnly, int fallbackSurfY) {
        if (roofBlocks <= 0) return;
        double rad = resolveRoofDirectionRad(tags, fill);
        double vx = Math.cos(rad), vz = Math.sin(rad);
        double[] c = centerOf(fill); double cx = c[0], cz = c[1];

        double[] span = projSpan(fill, vx, vz, cx, cz);
        double minL = span[0], L = span[2];

        // период зубьев ~ 6 блоков высоты в плане
        double periodBlocks = 6.0;
        for (long k : fill) {
            int x = BlockPos.getX(k), z = BlockPos.getZ(k);
            double px = x - cx, pz = z - cz;
            double t = ((px*vx + pz*vz) - minL) / L; // 0..1 вдоль направления
            double wave = triangleWave(t * (L / periodBlocks));  // 0..1
            int dh = Math.max(1, (int)Math.round(wave * roofBlocks));
            for (int dy = 1; dy <= dh; dy++) {
                int yBaseTop = localWallTopYAt(x, z, minOffset, facadeBlocks, fallbackSurfY);
                BlockPos pos = new BlockPos(x, yBaseTop + dy, z);
                level.setBlock(pos, roof.defaultBlockState(), 3);
            }
        }
    }

    private void buildRoofButterfly(Set<Long> fill, int minOffset, int facadeBlocks, int roofBlocks,
                             Block roof, JsonObject tags, boolean unusedAirOnly, int fallbackSurfY) {
        if (roofBlocks <= 0) return;
        double rad = resolveRoofDirectionRad(tags, fill);
        double vx = Math.cos(rad), vz = Math.sin(rad);
        double nx = -vz, nz = vx;
        double[] c = centerOf(fill); double cx = c[0], cz = c[1];

        double maxAbs = 1.0;
        for (long k : fill) {
            double px = BlockPos.getX(k) - cx, pz = BlockPos.getZ(k) - cz;
            double t = Math.abs(px*nx + pz*nz);
            if (t > maxAbs) maxAbs = t;
        }

        for (long k : fill) {
            int x = BlockPos.getX(k), z = BlockPos.getZ(k);
            double px = x - cx, pz = z - cz;
            double t = Math.abs(px*nx + pz*nz) / maxAbs; // 0..1 от долины к краю
            int dh = Math.max(1, (int)Math.round(t * roofBlocks)); // выше у краёв, низ в центре
            for (int dy = 1; dy <= dh; dy++) {
                int yBaseTop = localWallTopYAt(x, z, minOffset, facadeBlocks, fallbackSurfY);
                BlockPos pos = new BlockPos(x, yBaseTop + dy, z);
                level.setBlock(pos, roof.defaultBlockState(), 3);
            }
        }
    }

    private void buildRoofBarrel(Set<Long> fill, int minOffset, int facadeBlocks, int roofBlocks,
                             Block roof, JsonObject tags, boolean unusedAirOnly, int fallbackSurfY) {
        if (roofBlocks <= 0) return;
        double rad = resolveRoofDirectionRad(tags, fill);
        double vx = Math.cos(rad), vz = Math.sin(rad);
        double nx = -vz, nz = vx;
        double[] c = centerOf(fill); double cx = c[0], cz = c[1];

        double maxAbs = 1.0;
        for (long k : fill) {
            double px = BlockPos.getX(k) - cx, pz = BlockPos.getZ(k) - cz;
            double t = Math.abs(px*nx + pz*nz);
            if (t > maxAbs) maxAbs = t;
        }

        for (long k : fill) {
            int x = BlockPos.getX(k), z = BlockPos.getZ(k);
            double px = x - cx, pz = z - cz;
            double u = Math.abs(px*nx + pz*nz) / maxAbs; // 0..1 от оси бочки
            double arch = Math.sin((1.0 - u) * Math.PI/2.0);     // плавная арка
            int dh = Math.max(1, (int)Math.round(arch * roofBlocks));
            for (int dy = 1; dy <= dh; dy++) {
                int yBaseTop = localWallTopYAt(x, z, minOffset, facadeBlocks, fallbackSurfY);
                BlockPos pos = new BlockPos(x, yBaseTop + dy, z);
                level.setBlock(pos, roof.defaultBlockState(), 3);
            }
        }
    }

    private void buildRoofSaltbox(Set<Long> fill, int minOffset, int facadeBlocks, int roofBlocks,
                             Block roof, JsonObject tags, boolean unusedAirOnly, int fallbackSurfY) {
        if (roofBlocks <= 0) return;
        double rad = resolveRoofDirectionRad(tags, fill);
        double vx = Math.cos(rad), vz = Math.sin(rad);
        double nx = -vz, nz = vx;
        double[] c = centerOf(fill); double cx = c[0], cz = c[1];

        double maxAbs = 1.0;
        for (long k : fill) {
            double px = BlockPos.getX(k) - cx, pz = BlockPos.getZ(k) - cz;
            double t = Math.abs(px*nx + pz*nz);
            if (t > maxAbs) maxAbs = t;
        }
        // смещаем "конёк" на 25% вдоль направления — асимметрия
        double[] span = projSpan(fill, vx, vz, cx, cz);
        double minL = span[0], maxL = span[1];
        double ridgeShift = minL + 0.25*(maxL - minL);

        for (long k : fill) {
            int x = BlockPos.getX(k), z = BlockPos.getZ(k);
            double px = x - cx, pz = z - cz;
            double across = Math.abs(px*nx + pz*nz) / maxAbs;    // 0..1
            double along  = (px*vx + pz*vz) - ridgeShift;
            // на одной стороне склон длиннее (меньше падение), на другой — короче
            double sideFactor = (along >= 0) ? 1.0 : 0.6; // “длинный” и “короткий” скат
            int dh = Math.max(1, (int)Math.round((1.0 - across) * roofBlocks * sideFactor));
            for (int dy = 1; dy <= dh; dy++) {
                int yBaseTop = localWallTopYAt(x, z, minOffset, facadeBlocks, fallbackSurfY);
                BlockPos pos = new BlockPos(x, yBaseTop + dy, z);
                level.setBlock(pos, roof.defaultBlockState(), 3);
            }
        }
    }

    // ====== СБОР ДАННЫХ ИЗ OSM ======

    private static boolean isBuildingPart(JsonObject tags) {
        return tags.has("building:part");
    }
    private static boolean isBuilding(JsonObject tags) {
        return tags.has("building");
    }
    private static boolean isCanopy(JsonObject tags) {
        String b = optString(tags, "building");
        String mm = optString(tags, "man_made");
        return "roof".equalsIgnoreCase(String.valueOf(b)) || "canopy".equalsIgnoreCase(String.valueOf(mm));
    }

    private List<int[]> collectEntrances(JsonArray elements,
                                         double centerLat, double centerLng,
                                         double east, double west, double north, double south,
                                         int sizeMeters, int centerX, int centerZ) {
        List<int[]> pts = new ArrayList<>();
        for (JsonElement je : elements) {
            JsonObject e = je.getAsJsonObject();
            if (!"node".equals(optString(e, "type"))) continue;
            JsonObject tags = (e.has("tags") && e.get("tags").isJsonObject()) ? e.getAsJsonObject("tags") : null;
            if (tags == null) continue;
            String ent = optString(tags, "entrance");
            if (ent == null) continue;
            double lat = e.get("lat").getAsDouble();
            double lon = e.get("lon").getAsDouble();
            int[] xz = latlngToBlock(lat, lon, centerLat, centerLng, east, west, north, south, sizeMeters, centerX, centerZ);
            pts.add(new int[]{xz[0], xz[1]});
        }
        return pts;
    }

    private boolean isNonNegativePassage(JsonObject tags) {
        Integer layer = parseFirstInt(optString(tags, "layer"));
        Integer level = parseFirstInt(optString(tags, "level"));
        Integer minLevel = parseFirstInt(optString(tags, "building:min_level"));
        Double ele = parseMeters(optString(tags, "ele"));
        if (layer != null && layer < 0) return false;
        if (level != null && level < 0) return false;
        if (minLevel != null && minLevel < 0) return false;
        if (ele != null && ele < 0) return false;
        return true;
    }
    private boolean isPassageCandidate(JsonObject tags) {
        String tunnel  = normalize(optString(tags, "tunnel"));
        String highway = normalize(optString(tags, "highway"));
        String covered = normalize(optString(tags, "covered"));
        String passage = normalize(optString(tags, "passage"));
        String indoor  = normalize(optString(tags, "indoor"));

        if ("building_passage".equals(tunnel)) return true;
        if ("corridor".equals(indoor)) return true;
        if (("footway".equals(highway) || "path".equals(highway) || "steps".equals(highway))) {
            if ("arcade".equals(passage)) return true;
            if ("arcade".equals(covered) || "yes".equals(covered)) return true;
        }
        return false;
    }

    private List<List<int[]>> collectPassages(JsonArray elements,
            double centerLat, double centerLng,
            double east, double west, double north, double south,
            int sizeMeters, int centerX, int centerZ) {
        List<List<int[]>> lines = new ArrayList<>();
        for (JsonElement je : elements) {
            JsonObject e = je.getAsJsonObject();
            if (!"way".equals(optString(e, "type"))) continue;
            JsonObject tags = (e.has("tags") && e.get("tags").isJsonObject()) ? e.getAsJsonObject("tags") : null;
            if (tags == null) continue;
            if (!isPassageCandidate(tags)) continue;
            if (!isNonNegativePassage(tags)) continue;

            JsonArray geom = e.getAsJsonArray("geometry");
            if (geom == null || geom.size() < 2) continue;
            List<int[]> line = new ArrayList<>();
            for (JsonElement gp : geom) {
                JsonObject p = gp.getAsJsonObject();
                int[] xz = latlngToBlock(p.get("lat").getAsDouble(), p.get("lon").getAsDouble(),
                        centerLat, centerLng, east, west, north, south, sizeMeters, centerX, centerZ);
                line.add(xz);
            }
            lines.add(line);
        }
        return lines;
    }

    private List<int[]> toRingTiles(JsonArray geom,
                                    double centerLat, double centerLng,
                                    double east, double west, double north, double south,
                                    int sizeMeters, int centerX, int centerZ) {
        List<int[]> ring = new ArrayList<>();
        for (JsonElement gp : geom) {
            JsonObject p = gp.getAsJsonObject();
            int[] xz = latlngToBlock(p.get("lat").getAsDouble(), p.get("lon").getAsDouble(),
                    centerLat, centerLng, east, west, north, south, sizeMeters, centerX, centerZ);
            ring.add(xz);
        }
        // убедимся, что замкнуты
        if (ring.size() >= 2) {
            int[] f = ring.get(0);
            int[] l = ring.get(ring.size() - 1);
            if (f[0] != l[0] || f[1] != l[1]) {
                ring.add(new int[]{f[0], f[1]});
            }
        }
        return ring;
    }

    // ====== ВЫСОТЫ/МАТЕРИАЛЫ/ЦВЕТ ======

    private int parseMinOffsetBlocks(JsonObject tags) {
        // этажи в приоритете (учитываем и building:min_level, и min_level)
        String ml = optString(tags, "building:min_level");
        if (ml == null) ml = optString(tags, "min_level");
        Integer minLevel = parseFirstInt(ml);
        if (minLevel != null && minLevel > 0) return minLevel * LEVEL_HEIGHT;

        // метры, если этажей нет
        String mh = optString(tags, "min_height");
        if (mh == null) mh = optString(tags, "building:min_height"); // синоним
        Double m = parseMeters(mh);
        if (m != null && m > 0) return (int)Math.round(m);

        return 0;
    }

    /** Высота стен: приоритет уровней, затем height/roof:height отнимаем крыши при наличии */
    private int parseFacadeHeightBlocks(JsonObject tags) {
        // 1) ЭТАЖИ — главный ориентир
        Integer levels = parseFirstInt(optString(tags, "building:levels"));
        if (levels == null) levels = parseFirstInt(optString(tags, "levels"));
        if (levels == null) levels = parseFirstInt(optString(tags, "building:levels:aboveground"));
        if (levels != null && levels > 0) {
            return Math.max(1, levels * LEVEL_HEIGHT);
        }

        // 2) Если этажей нет — берём метры до верха крыши и вычитаем крышу
        Double totalH = parseMeters(optString(tags, "height"));
        if (totalH == null) totalH = parseMeters(optString(tags, "building:height")); // синоним
        if (totalH != null && totalH > 0) {
            int roof = parseRoofHeightBlocks(tags);
            int body = (int)Math.round(totalH) - roof;
            return Math.max(1, body);
        }

        // 3) roof:levels есть, но этажей и высоты нет — отдаём дефолт по корпусу
        Integer roofLv = parseFirstInt(optString(tags, "roof:levels"));
        if (roofLv != null && roofLv > 0) return Math.max(1, 8);

        return 8; // запасной дефолт
    }

    private int parseRoofHeightBlocks(JsonObject tags) {
        // roof:levels * LEVEL_HEIGHT
        Integer roofLv = parseFirstInt(optString(tags, "roof:levels"));
        if (roofLv != null && roofLv > 0) return Math.max(1, roofLv * LEVEL_HEIGHT);

        // roof:height (м)
        Double rh = parseMeters(optString(tags, "roof:height"));
        if (rh != null && rh > 0) return Math.max(1, (int)Math.round(rh));

        // если roof:shape=flat — 1 блок, иначе 3 по умолчанию
        String shape = parseRoofShape(tags);
        return "flat".equals(shape) ? 1 : 3;
    }

    private int parseTotalHeightBlocks(JsonObject tags, int fallbackFacadeBlocks, int roofBlocks) {
        // ЭТАЖИ + крыша
        Integer levels = parseFirstInt(optString(tags, "building:levels"));
        if (levels == null) levels = parseFirstInt(optString(tags, "levels"));
        if (levels == null) levels = parseFirstInt(optString(tags, "building:levels:aboveground"));
        if (levels != null && levels > 0) {
            return Math.max(1, levels * LEVEL_HEIGHT + Math.max(0, roofBlocks));
        }

        // Если этажей нет — берём полную высоту в метрах
        Double totalMeters = parseMeters(optString(tags, "height"));
        if (totalMeters == null) totalMeters = parseMeters(optString(tags, "building:height")); // синоним
        if (totalMeters != null && totalMeters > 0) {
            return Math.max(1, (int)Math.round(totalMeters));
        }

        // Иначе — из уже посчитанного корпуса + крыша
        return Math.max(1, fallbackFacadeBlocks) + Math.max(1, roofBlocks);
    }

// Есть ли у здания уже структурные ключи высоты/этажей?
private boolean hasStructuralHeight(JsonObject tags) {
    if (tags == null) return false;
    for (String k : STRUCTURAL_HEIGHT_KEYS) {
        if (hasNonBlank(tags, k)) return true;
    }
    return false;
}

// Парсинг строки level: "1", "0;1;2", "1-9", "1-3;5;7-8" → множество целых уровней
private Set<Integer> parseLevelsSet(String s) {
    Set<Integer> out = new HashSet<>();
    if (s == null) return out;
    s = s.trim().toLowerCase(Locale.ROOT);
    if (s.isEmpty()) return out;

    // разделители ; или , (на всякий случай)
    String[] tokens = s.split("[;,]");
    for (String t : tokens) {
        t = t.trim();
        if (t.isEmpty()) continue;

        // диапазон вида "a-b"
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("^\\s*([-+]?\\d+)\\s*-\\s*([-+]?\\d+)\\s*$")
                .matcher(t);
        if (m.matches()) {
            try {
                int a = Integer.parseInt(m.group(1));
                int b = Integer.parseInt(m.group(2));
                if (a <= b) {
                    for (int v = a; v <= b; v++) out.add(v);
                } else {
                    for (int v = a; v >= b; v--) out.add(v);
                }
            } catch (Exception ignore) {}
            continue;
        }

        // одиночное число
        try {
            int v = Integer.parseInt(t);
            out.add(v);
        } catch (Exception ignore) {
            // игнорим ненumeric токены типа "mezzanine"
        }
    }
    return out;
}

// Большинство точек way внутри полигона здания?
private boolean isWayMostlyInside(JsonObject way, Set<Long> buildingFill,
                                  double centerLat, double centerLng,
                                  double east, double west, double north, double south,
                                  int sizeMeters, int centerX, int centerZ) {
    if (way == null || !"way".equals(optString(way, "type"))) return false;
    if (!way.has("geometry") || !way.get("geometry").isJsonArray()) return false;
    JsonArray geom = way.getAsJsonArray("geometry");
    if (geom.size() == 0) return false;

    int inside = 0, total = 0;
    for (JsonElement gp : geom) {
        JsonObject p = gp.getAsJsonObject();
        int[] xz = latlngToBlock(p.get("lat").getAsDouble(), p.get("lon").getAsDouble(),
                centerLat, centerLng, east, west, north, south, sizeMeters, centerX, centerZ);
        total++;
        if (buildingFill.contains(BlockPos.asLong(xz[0], 0, xz[1]))) inside++;
    }
    // >50% точек внутри — считаем, что объект «принадлежит» зданию
    return total > 0 && inside * 2 > total;
}

// Узел внутри полигона здания?
private boolean isNodeInside(JsonObject node, Set<Long> buildingFill,
                             double centerLat, double centerLng,
                             double east, double west, double north, double south,
                             int sizeMeters, int centerX, int centerZ) {
    if (node == null || !"node".equals(optString(node, "type"))) return false;
    if (!node.has("lat") || !node.has("lon")) return false;
    int[] xz = latlngToBlock(node.get("lat").getAsDouble(), node.get("lon").getAsDouble(),
            centerLat, centerLng, east, west, north, south, sizeMeters, centerX, centerZ);
    return buildingFill.contains(BlockPos.asLong(xz[0], 0, xz[1]));
}

    // Главная «фишка»: если у здания нет высоты/этажей — попытаться вывести их из внутренних объектов
    private JsonObject augmentHeightFromInteriorIfMissing(
            JsonObject buildingTags,
            Set<Long> buildingFill,
            JsonArray elements,
            double centerLat, double centerLng,
            double east, double west, double north, double south,
            int sizeMeters, int centerX, int centerZ) {

        if (buildingTags == null) buildingTags = new JsonObject();
        // Уже всё есть — ничего не делаем
        if (hasStructuralHeight(buildingTags)) return buildingTags;

        // Копия для безопасной записи результата
        JsonObject out = buildingTags.deepCopy();

        // 1) Сбор уровней и высоты из внутренних объектов
        Set<Integer> unionNonNegLevels = new HashSet<>();
        double maxHeightMeters = -1;

        for (JsonElement je : elements) {
            JsonObject e = je.getAsJsonObject();
            String t = optString(e, "type");
            if (t == null) continue;

            boolean inside = false;
            if ("node".equals(t)) {
                inside = isNodeInside(e, buildingFill, centerLat, centerLng, east, west, north, south, sizeMeters, centerX, centerZ);
            } else if ("way".equals(t)) {
                inside = isWayMostlyInside(e, buildingFill, centerLat, centerLng, east, west, north, south, sizeMeters, centerX, centerZ);
            } else {
                continue; // relation и прочее — пропустим
            }
            if (!inside) continue;

            JsonObject tags = (e.has("tags") && e.get("tags").isJsonObject()) ? e.getAsJsonObject("tags") : null;
            if (tags == null) continue;

            // Берём любые подсказки об этажности/высоте
            // a) level / level:ref — парсим списки/диапазоны и накапливаем уровни >= 0
            for (String key : new String[]{"level", "level:ref"}) {
                Set<Integer> lv = parseLevelsSet(optString(tags, key));
                for (int v : lv) if (v >= 0) unionNonNegLevels.add(v);
            }

            // b) min_level + max_level → добавляем целый диапазон
            Integer minL = parseFirstInt(optString(tags, "min_level"));
            if (minL == null) minL = parseFirstInt(optString(tags, "building:min_level"));
            Integer maxL = parseFirstInt(optString(tags, "max_level"));
            if (maxL == null) maxL = parseFirstInt(optString(tags, "building:max_level"));
            if (minL != null && maxL != null) {
                int a = Math.min(minL, maxL), b = Math.max(minL, maxL);
                for (int v = a; v <= b; v++) if (v >= 0) unionNonNegLevels.add(v);
            }

            // c) высота метрами (редко встречается внутри, но вдруг)
            Double h = parseMeters(optString(tags, "height"));
            if (h == null) h = parseMeters(optString(tags, "building:height"));
            if (h != null && h > 0) maxHeightMeters = Math.max(maxHeightMeters, h);
        }

        // 2) Выводим building:levels из union уровней >= 0
        if (!unionNonNegLevels.isEmpty()) {
            int levels = unionNonNegLevels.size(); // 0..N включительно → это и есть число надземных уровней
            if (levels > 0) {
                out.addProperty("building:levels", levels);
                return out;
            }
        }

        // 3) Иначе — если нашли высоту, подставим её
        if (maxHeightMeters > 0) {
            out.addProperty("height", (int)Math.round(maxHeightMeters));
            return out;
        }

        // Ничего не нашли — возвращаем исходные теги
        return buildingTags;
    }

    private String parseRoofShape(JsonObject tags) {
        String s = optString(tags, "roof:shape");
        if (s == null) return "flat";
        s = s.trim().toLowerCase(Locale.ROOT)
                .replace('-', '_').replace(' ', '_');

        // сводим синонимы к внутренним именам
        return switch (s) {
            case "flat", "terrace" -> "flat";
            case "gabled", "gable" -> "gabled";
            case "skillion", "shed", "lean_to" -> "skillion";
            case "pyramidal", "pyramid", "hipped_pyramid", "tented", "pavilion" -> "pyramidal";
            case "hip", "hipped" -> "hip";
            case "half_hip", "half_hipped", "jerkinhead" -> "halfhip";
            case "gambrel" -> "gambrel";
            case "mansard" -> "mansard";
            case "round", "dome", "domed" -> "dome";
            case "onion" -> "onion";
            case "conical", "cone", "spire" -> "conical";
            case "sawtooth" -> "sawtooth";
            case "butterfly", "inverted_gable", "vroof" -> "butterfly";
            case "barrel", "arched", "quonset" -> "barrel";
            case "saltbox" -> "saltbox";
            default -> "flat";
        };
    }

    // === NEW: вертикальная геометрия для building:part ===
    private static class PartVertical {
        int offsetBlocks; // с какого уровня над рельефом начинать (в блоках)
        int heightBlocks; // толщина части (в блоках), без учёта "всей высоты здания"
    }

    private PartVertical computePartVertical(JsonObject tags) {
        PartVertical pv = new PartVertical();

        // читаем min/max уровня и с building:*, и без префикса
        Integer minLevel = parseFirstInt(optString(tags, "building:min_level"));
        if (minLevel == null) minLevel = parseFirstInt(optString(tags, "min_level"));

        Integer maxLevel = parseFirstInt(optString(tags, "building:max_level"));
        if (maxLevel == null) maxLevel = parseFirstInt(optString(tags, "max_level"));

        Integer levels = parseFirstInt(optString(tags, "building:levels"));
        if (levels == null) levels = parseFirstInt(optString(tags, "levels"));
        if (levels == null) levels = parseFirstInt(optString(tags, "building:levels:aboveground"));

        Double hTop = parseMeters(optString(tags, "height"));
        if (hTop == null) hTop = parseMeters(optString(tags, "building:height"));
        Double hMin = parseMeters(optString(tags, "min_height"));
        if (hMin == null) hMin = parseMeters(optString(tags, "building:min_height"));

        // OFFSET (откуда начинаем часть): уважаем явные min_*; иначе от рельефа
        if (minLevel != null && minLevel >= 0) {
            pv.offsetBlocks = minLevel * LEVEL_HEIGHT;
        } else if (hMin != null && hMin > 0) {
            pv.offsetBlocks = (int)Math.round(hMin);
        } else {
            pv.offsetBlocks = 0;
        }

        // ВЫСОТА ЧАСТИ:
        if (minLevel != null && maxLevel != null && maxLevel >= minLevel) {
            // Явный диапазон уровней → ВКЛЮЧИТЕЛЬНО (классическая OSM-семантика)
            pv.heightBlocks = Math.max(1, (maxLevel - minLevel + 1) * LEVEL_HEIGHT);

        } else if (minLevel != null && levels != null) {
            // ВАЖНО: когда есть min_level, но НЕТ max_level, а "levels" указан —
            // трактуем "levels" как ВЕРХНИЙ УРОВЕНЬ части (top), а не "кол-во этажей".
            // Толщина = (top - min).
            int top = levels;
            int floors = Math.max(0, top - minLevel);
            pv.heightBlocks = Math.max(1, floors * LEVEL_HEIGHT);

            // Примечание: если вдруг в данных "levels" реально означает "кол-во этажей",
            // то можно явно задать building:max_level = min_level + levels,
            // и сработает ветка выше (inclusive).

        } else if (levels != null && levels > 0) {
            // Просто "levels" без min_* → от земли на N этажей
            pv.heightBlocks = levels * LEVEL_HEIGHT;

        } else if (hTop != null && hMin != null && hTop > hMin) {
            pv.heightBlocks = Math.max(1, (int)Math.round(hTop - hMin));

        } else if (hTop != null && hTop > 0) {
            pv.heightBlocks = Math.max(1, (int)Math.round(hTop));

        } else {
            // запасной вариант
            pv.heightBlocks = Math.max(1, parseFacadeHeightBlocks(tags));
        }

        if (pv.heightBlocks < 1) pv.heightBlocks = 1;
        if (pv.offsetBlocks < 0) pv.offsetBlocks = 0;
        return pv;
    }

    // === NEW: тот же minOffset, что реально используется для части в buildFromFill ===
    private int effectiveMinOffsetForPart(JsonObject tags) {
        PartVertical pv = computePartVertical(tags);
        int off = Math.max(0, pv.offsetBlocks);
        if (!hasExplicitMinAnchor(tags)) {
            // «якори» не заданы явно → прижимаем к земле, если это не подвес/слой
            if (FORCE_GROUND_ANCHOR && !isSuspended(tags)) off = 0;
        }
        return off;
    }

    // ЗАМЕНА parseDirection(...) — пусть вернёт null, если направления нет
    private Double parseDirectionNullable(JsonObject tags) {
        try {
            String d = optString(tags, "roof:direction");
            if (d == null || d.isBlank()) return null;
            return Double.parseDouble(d.trim());
        } catch (Exception ignore) {
            return null;
        }
    }

    // Новый хелпер: если направления нет, вычисляем по длинной стороне здания
    private double resolveRoofDirectionRad(JsonObject tags, Set<Long> fill) {
        Double deg = parseDirectionNullable(tags);
        if (deg != null) return Math.toRadians(deg);
        int[] bb = bounds(fill); // [minX,minZ,maxX,maxZ]
        int w = bb[2] - bb[0];
        int d = bb[3] - bb[1];
        // вдоль длинной стороны: 0° по X, 90° по Z
        return Math.toRadians(w >= d ? 0.0 : 90.0);
    }

    // --- НОВАЯ ЛОГИКА ПОДБОРА БЛОКОВ: МАТЕРИАЛ → ЦВЕТ ВНУТРИ СЕМЕЙСТВА ---
    @SuppressWarnings("unused")
    private String colorToGlass(String colour, String fallback) {
        String c = colour.trim().toLowerCase(Locale.ROOT)
                .replace(" ", "").replace("-", "").replace("_", "");
        if (GLASS_COLOR_NAME_TO_BLOCK.containsKey(c)) return GLASS_COLOR_NAME_TO_BLOCK.get(c);

        int[] rgb = parseHexRGB(c);
        if (rgb != null) return nearestGlassByRGB(rgb, fallback);
        return fallback;
    }

    private String nearestGlassByRGB(int[] rgb, String fallback) {
        double best = Double.MAX_VALUE;
        String bestId = fallback;
        for (Map.Entry<String,int[]> e : BLOCK_RGB.entrySet()) {
            String id = e.getKey();
            // ограничим поиск только стеклом (stained/tinted/clear)
            if (!(id.endsWith("_stained_glass") || id.equals("minecraft:glass") || id.equals("minecraft:tinted_glass")))
                continue;
            int[] c = e.getValue();
            double d = (c[0]-rgb[0])*(c[0]-rgb[0]) + (c[1]-rgb[1])*(c[1]-rgb[1]) + (c[2]-rgb[2])*(c[2]-rgb[2]);
            if (d < best) { best = d; bestId = id; }
        }
        return bestId;
    }

    private String pickFacadeBlock(JsonObject tags) {
        String mat = normalize(
            optString(tags, "building:material"),
            optString(tags, "material"),
            optString(tags, "facade:material"),
            optString(tags, "building:facade:material"),
            optString(tags, "cladding"),
            optString(tags, "building:cladding"),
            optString(tags, "facade:cladding"),
            optString(tags, "wall:material"),
            optString(tags, "building:wall:material")
        );
        String col = normalize(
            optString(tags, "building:colour"),
            optString(tags, "building:color"),
            optString(tags, "colour"),
            optString(tags, "color"),
            optString(tags, "facade:colour"),
            optString(tags, "facade:color"),
            optString(tags, "building:facade:colour"),
            optString(tags, "building:facade:color")
        );

        // Если материал не указан, но по типу здание обычно деревянное — считаем его деревянным
        if (mat == null) {
            String btype = normalize(optString(tags, "building"));
            if (isLikelyWoodenType(btype)) {
                mat = "wood"; // это приведёт к дубовым доскам по умолчанию
            }
        }

        // если есть и материал, и цвет — сначала уважаем материал
        if (mat != null && col != null) {
            // «жёсткие» материалы: цвет игнорируем
            if (METAL_MATERIALS.contains(mat) || STONE_LIKE_MATERIALS.contains(mat)) {
                String byMat = FACADE_MATERIAL.get(mat);
                return (byMat != null) ? byMat : DEFAULT_FACADE_BLOCK;
            }
            // иначе пробуем покрасить внутри семейства материала
            String family = familyFromMaterial(mat, /*roof=*/false);
            if (family != null && familySupportsColor(family)) {
                String fallback = FACADE_MATERIAL.getOrDefault(mat,
                        COLOR_BASE_FACADE.getOrDefault("default", DEFAULT_FACADE_BLOCK));
                String colored = resolveColoredBlockForFamily(family, col, fallback);
                if (colored != null) return colored;
            }
            // откат к маппингу по материалу
            if (FACADE_MATERIAL.containsKey(mat)) return FACADE_MATERIAL.get(mat);
        }

        // только материал
        if (mat != null) {
            String byMat = FACADE_MATERIAL.get(mat);
            if (byMat != null) return byMat;
        }

        // только цвет
        if (col != null) {
            String base = COLOR_BASE_FACADE.getOrDefault("default", DEFAULT_FACADE_BLOCK);
            return colorToBlock(col, base);
        }

        // дефолт теперь — взвешанный набор материалов
        return pickWeightedDefaultFacade();
    }

    private boolean isLikelyWoodenType(String btype) {
        if (btype == null) return false;
        switch (btype) {
            case "cabin":
            case "hut":
            case "shed":
            case "barn":
            case "stable":
            case "farm_auxiliary":
            case "boathouse":
            case "kiosk":
            case "pavilion":
            case "gazebo":
            case "chalet":
            case "tree_house":
            case "treehouse":
            case "lean_to":
            case "forest_hut":
                return true;
            default:
                return false;
        }
    }

    private String pickRoofBlock(JsonObject tags) {
        String mat = normalize(optString(tags, "roof:material"));
        String col = normalize(optString(tags, "roof:colour"));

        if (mat != null && col != null) {
            // металлы — цвет игнорируем
            if (METAL_MATERIALS.contains(mat)) {
                String byMat = ROOF_MATERIAL.get(mat);
                return (byMat != null) ? byMat : DEFAULT_ROOF_BLOCK;
            }
            // попытка покрасить внутри семейства крыши
            String family = familyFromMaterial(mat, /*roof=*/true);
            if (family != null && familySupportsColor(family)) {
                String fallback = ROOF_MATERIAL.getOrDefault(mat,
                        COLOR_BASE_ROOF.getOrDefault("default", DEFAULT_ROOF_BLOCK));
                String colored = resolveColoredBlockForFamily(family, col, fallback);
                if (colored != null) return colored;
            }
            if (ROOF_MATERIAL.containsKey(mat)) return ROOF_MATERIAL.get(mat);
        }

        if (mat != null) {
            String byMat = ROOF_MATERIAL.get(mat);
            if (byMat != null) return byMat;
        }

        if (col != null) {
            String base = COLOR_BASE_ROOF.getOrDefault("default", DEFAULT_ROOF_BLOCK);
            return colorToBlock(col, base);
        }

        return DEFAULT_ROOF_BLOCK;
    }

    private static String normalize(String... vs) {
        for (String v : vs) {
            if (v == null) continue;
            v = v.trim().toLowerCase(Locale.ROOT);
            if (!v.isBlank()) return v;
        }
        return null;
    }

    /** Базовый выбор цвета (без учёта семейства) — имя/HEX -> ближайший блок из общей палитры, иначе fallback. */
    private String colorToBlock(String colour, String fallback) {
        String c = colour.trim().toLowerCase(Locale.ROOT)
                .replace(" ", "")
                .replace("-", "")
                .replace("_", "");
        // 1) имя
        if (COLOR_NAME_TO_BLOCK.containsKey(c)) return COLOR_NAME_TO_BLOCK.get(c);
        // 2) hex
        if (c.startsWith("#") || c.matches("^[0-9a-f]{6}$")) {
            int[] rgb = parseHexRGB(c);
            if (rgb != null) return nearestBlockByRGB(rgb, fallback);
        }
        return fallback;
    }

    /** Выбор цвета внутри семейства (glass / concrete / terracotta / wool). */
    private String resolveColoredBlockForFamily(String family, String colour, String fallback) {
        String dye = null;

        if ("glass".equals(family)) {
            // спец-таблица имён стекла
            String norm = colour.trim().toLowerCase(Locale.ROOT).replace(" ","").replace("-","").replace("_","");
            String byName = GLASS_COLOR_NAME_TO_BLOCK.get(norm);
            if (byName != null) return byName;

            // HEX -> ближайшее stained glass
            int[] rgb = parseHexRGB(norm);
            if (rgb != null) {
                return nearestBlockByRGBInFamily(rgb, "stained_glass", fallback != null ? fallback : "minecraft:glass");
            }

            // иначе берём обычное имя → краситель
            dye = colorToDye(colour);
            if (dye != null) return blockIdForFamilyAndDye("stained_glass", dye);

            // последний шанс
            return fallback != null ? fallback : "minecraft:glass";
        }

        // не стекло: пробуем краситель по общему правилу
        dye = colorToDye(colour);
        if (dye != null) {
            String fam = switch (family) {
                case "concrete" -> "concrete";
                case "terracotta", "tile", "clay" -> "terracotta";
                case "wool" -> "wool";
                default -> null;
            };
            if (fam != null) return blockIdForFamilyAndDye(fam, dye);
        }

        // HEX «внутри семейства»
        String norm = colour.trim().toLowerCase(Locale.ROOT).replace(" ","").replace("-","").replace("_","");
        int[] rgb = parseHexRGB(norm);
        if (rgb != null) {
            String fam = switch (family) {
                case "concrete" -> "concrete";
                case "terracotta", "tile", "clay" -> "terracotta";
                case "wool" -> "wool";
                default -> null;
            };
            if (fam != null) return nearestBlockByRGBInFamily(rgb, fam, fallback);
        }

        return fallback;
    }

    /** Определяем «семейство» материала — нужно, чтобы красить внутри него. */
    private String familyFromMaterial(String mat, boolean roof) {
        if (mat == null) return null;
        switch (mat) {
            case "glass", "stained_glass", "window", "glazing" -> { return "glass"; }
            case "concrete", "plaster", "stucco", "render", "panel", "composite" -> { return "concrete"; }
            case "tile", "clay", "terracotta", "brick", "brickwork", "adobe" -> { return "terracotta"; }
            case "wool" -> { return "wool"; }
            // всё ниже — считаем «жёсткими» материалами без оттенков
            case "gold", "gilded", "gilt" -> { return "metal"; }
            case "copper", "verdigris" -> { return "metal"; }
            case "iron", "steel", "stainless_steel", "chrome", "chromium", "aluminium", "aluminum", "zinc", "tin", "silver", "nickel", "titanium", "lead" -> { return "metal"; }
            case "stone", "granite", "limestone", "sandstone", "marble", "deepslate" -> { return "stone"; }
            case "wood", "timber" -> { return "wood"; }
            case "mirror", "mirrored", "mirror_glass", "mirror glass",
                "reflective", "reflective_glass", "reflective glass" -> { return "glass"; }
            default -> {
                // крыши по умолчанию ближе к «черепице» (терракота)
                if (roof) return "terracotta";
                return null;
            }
        }
    }

    /** Поддерживает ли семейство цветовые оттенки. */
    private boolean familySupportsColor(String family) {
        return "glass".equals(family) || "concrete".equals(family) || "terracotta".equals(family) || "wool".equals(family);
    }

    /** Преобразуем имя/HEX в «dye» (white/light_gray/.../brown). */
    private String colorToDye(String colour) {
        // Сначала берём готовый мэппинг COLOR_NAME_TO_BLOCK и извлекаем dye из блока
        String by = colorToBlock(colour, null);
        if (by != null) {
            String dye = dyeFromBlockId(by);
            if (dye != null) return dye;
        }
        // Прямая попытка вычленить dye из текста
        String c = colour.trim().toLowerCase(Locale.ROOT).replace(" ","").replace("-","").replace("_","");
        for (String d : DYES) {
            if (c.contains(d.replace("_",""))) return d;
        }
        // HEX → ближайший бетон → краситель
        int[] rgb = parseHexRGB(c);
        if (rgb != null) {
            String block = nearestBlockByRGBInFamily(rgb, "concrete", null);
            if (block != null) return dyeFromBlockId(block);
        }
        return null;
    }

    private static final String[] DYES = new String[]{
            "white","light_gray","gray","black","red","orange","yellow","lime",
            "green","cyan","light_blue","blue","purple","magenta","pink","brown"
    };

    /** Извлекаем краситель из id вида minecraft:<dye>_concrete / _terracotta / _wool / _stained_glass */
    private String dyeFromBlockId(String id) {
        if (id == null) return null;
        int idx = id.indexOf(':');
        String s = (idx >= 0 ? id.substring(idx+1) : id);
        for (String d : DYES) {
            if (s.startsWith(d + "_")) return d;
        }
        return null;
    }

    /** Возвращает id блока семейства по красителю. family: concrete / terracotta / wool / stained_glass */
    private String blockIdForFamilyAndDye(String family, String dye) {
        if (dye == null) return null;
        switch (family) {
            case "concrete":      return "minecraft:" + dye + "_concrete";
            case "terracotta":    return "minecraft:" + dye + "_terracotta";
            case "wool":          return "minecraft:" + dye + "_wool";
            case "stained_glass": return "minecraft:" + dye + "_stained_glass";
            default: return null;
        }
    }

    /** Ближайший по RGB внутри конкретного семейства (фильтр по суффиксу) */
    private String nearestBlockByRGBInFamily(int[] rgb, String family, String fallback) {
        String suffix = switch (family) {
            case "concrete" -> "_concrete";
            case "terracotta" -> "_terracotta";
            case "wool" -> "_wool";
            case "stained_glass" -> "_stained_glass";
            case "glass" -> "_stained_glass";
            default -> "";
        };
        double best = Double.MAX_VALUE;
        String bestId = fallback;
        for (Map.Entry<String,int[]> e : BLOCK_RGB.entrySet()) {
            String id = e.getKey();
            if (!suffix.isEmpty() && !id.endsWith(suffix)) continue;
            int[] c = e.getValue();
            double d = (c[0]-rgb[0])*(c[0]-rgb[0]) + (c[1]-rgb[1])*(c[1]-rgb[1]) + (c[2]-rgb[2])*(c[2]-rgb[2]);
            if (d < best) { best = d; bestId = id; }
        }
        return bestId;
    }

    /** HEX/имя → ближайший блок из всей палитры (бетон/терракота/шерсть/стекло), иначе fallback. */
    private String nearestBlockByRGB(int[] rgb, String fallback) {
        double best = Double.MAX_VALUE;
        String bestId = fallback;
        for (Map.Entry<String,int[]> e : BLOCK_RGB.entrySet()) {
            int[] c = e.getValue();
            double d = (c[0]-rgb[0])*(c[0]-rgb[0]) + (c[1]-rgb[1])*(c[1]-rgb[1]) + (c[2]-rgb[2])*(c[2]-rgb[2]);
            if (d < best) { best = d; bestId = e.getKey(); }
        }
        return bestId != null ? bestId : fallback;
    }

    private int[] parseHexRGB(String s) {
        try {
            String hex = s.startsWith("#") ? s.substring(1) : s;
            if (hex.length() != 6) return null;
            int r = Integer.parseInt(hex.substring(0,2), 16);
            int g = Integer.parseInt(hex.substring(2,4), 16);
            int b = Integer.parseInt(hex.substring(4,6), 16);
            return new int[]{r,g,b};
        } catch (Exception ignore) {
            return null;
        }
    }

    // ====== РАСТЕР БУДУЩЕГО ОБЪЕМА ======

    private Set<Long> rasterizePolygon(List<int[]> ring, int minX, int maxX, int minZ, int maxZ) {
        // сканируем bbox полигона и применяем чёт-нечёт
        int xMin = Integer.MAX_VALUE, xMax = Integer.MIN_VALUE;
        int zMin = Integer.MAX_VALUE, zMax = Integer.MIN_VALUE;
        for (int[] p : ring) {
            xMin = Math.min(xMin, p[0]); xMax = Math.max(xMax, p[0]);
            zMin = Math.min(zMin, p[1]); zMax = Math.max(zMax, p[1]);
        }
        xMin = Math.max(xMin, minX); xMax = Math.min(xMax, maxX);
        zMin = Math.max(zMin, minZ); zMax = Math.min(zMax, maxZ);

        Set<Long> out = new HashSet<>();
        for (int z = zMin; z <= zMax; z++) {
            for (int x = xMin; x <= xMax; x++) {
                if (pointInPolygon(x + 0.5, z + 0.5, ring)) {
                    out.add(BlockPos.asLong(x, 0, z));
                }
            }
        }
        return out;
    }

    private boolean pointInPolygon(double x, double z, List<int[]> ring) {
        boolean inside = false;
        for (int i = 0, j = ring.size() - 1; i < ring.size(); j = i++) {
            int[] pi = ring.get(i);
            int[] pj = ring.get(j);
            double xi = pi[0], zi = pi[1];
            double xj = pj[0], zj = pj[1];
            boolean intersect = ((zi > z) != (zj > z))
                    && (x < (xj - xi) * (z - zi) / (zj - zi + 1e-9) + xi);
            if (intersect) inside = !inside;
        }
        return inside;
    }

    private Set<Long> edgeOfFill(Set<Long> fill) {
        Set<Long> edge = new HashSet<>();
        for (long k : fill) {
            int x = BlockPos.getX(k), z = BlockPos.getZ(k);
            boolean isEdge =
                    !fill.contains(BlockPos.asLong(x+1,0,z)) ||
                    !fill.contains(BlockPos.asLong(x-1,0,z)) ||
                    !fill.contains(BlockPos.asLong(x,0,z+1)) ||
                    !fill.contains(BlockPos.asLong(x,0,z-1));
            if (isEdge) edge.add(k);
        }
        return edge;
    }

    private boolean containsXZ(Set<Long> s, int x, int z) {
        return s.contains(BlockPos.asLong(x,0,z));
    }

    private int[] centroid(List<int[]> ring) {
        // простой bbox-центр (устойчивее для нерегулярных контуров)
        int[] bb = bounds(ring);
        return new int[]{ (bb[0]+bb[2])>>1, (bb[1]+bb[3])>>1 };
    }

    private int[] bounds(List<int[]> ring) {
        int xMin = Integer.MAX_VALUE, xMax = Integer.MIN_VALUE;
        int zMin = Integer.MAX_VALUE, zMax = Integer.MIN_VALUE;
        for (int[] p : ring) {
            xMin = Math.min(xMin, p[0]); xMax = Math.max(xMax, p[0]);
            zMin = Math.min(zMin, p[1]); zMax = Math.max(zMax, p[1]);
        }
        return new int[]{xMin, zMin, xMax, zMax};
    }
    private int[] bounds(Set<Long> fill) {
        int xMin = Integer.MAX_VALUE, xMax = Integer.MIN_VALUE;
        int zMin = Integer.MAX_VALUE, zMax = Integer.MIN_VALUE;
        for (long k : fill) {
            int x = BlockPos.getX(k), z = BlockPos.getZ(k);
            xMin = Math.min(xMin, x); xMax = Math.max(xMax, x);
            zMin = Math.min(zMin, z); zMax = Math.max(zMax, z);
        }
        return new int[]{xMin, zMin, xMax, zMax};
    }

    private List<long[]> pickFourCorners(Set<Long> edge) {
        if (edge.isEmpty()) return Collections.emptyList();
        int[] bb = bounds(edge);
        List<long[]> corners = new ArrayList<>();
        corners.add(new long[]{bb[0], bb[1]});
        corners.add(new long[]{bb[2], bb[1]});
        corners.add(new long[]{bb[0], bb[3]});
        corners.add(new long[]{bb[2], bb[3]});
        return corners;
    }

    // ====== УТИЛИТЫ МИРА / ВСПОМОГАТЕЛЬНЫЕ ======

    private void snapshotGround(int minX, int maxX, int minZ, int maxZ) {
        groundSnapshot.clear();

        // 1) Если есть terrainGrid в coords — СТРОГО используем его как источник рельефа.
        try {
            if (coords != null && coords.has("terrainGrid")) {
                JsonObject g = coords.getAsJsonObject("terrainGrid");
                int gridMinX = g.get("minX").getAsInt();
                int gridMinZ = g.get("minZ").getAsInt();
                int w = g.get("width").getAsInt();
                int h = g.get("height").getAsInt();
                JsonArray data = g.getAsJsonArray("data");

                for (int z = minZ; z <= maxZ; z++) {
                    for (int x = minX; x <= maxX; x++) {
                        int ix = x - gridMinX, iz = z - gridMinZ;
                        int y = Integer.MIN_VALUE;

                        if (ix >= 0 && ix < w && iz >= 0 && iz < h) {
                            // значение из грида — это верх рельефа (Y)
                            y = data.get(iz * w + ix).getAsInt();
                        }
                        // Вышли за пределы грида? Фолбэк на нормальный heightmap, а не «верхний не-air»
                        if (y == Integer.MIN_VALUE) {
                            y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z) - 1;
                        }
                        groundSnapshot.put(BlockPos.asLong(x, 0, z), y);
                    }
                }
                broadcast(level, "terrainGrid → snapshotGround: использую высоты рельефа из грида.");
                return;
            }
        } catch (Throwable t) {
            broadcast(level, "terrainGrid недоступен/бит — откат к heightmap.");
        }

        // 2) Нет грида — берём высоту из heightmap (земля), а НЕ «самый верхний блок».
        for (int z = minZ; z <= maxZ; z++) {
            for (int x = minX; x <= maxX; x++) {
                int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z) - 1;
                groundSnapshot.put(BlockPos.asLong(x, 0, z), y);
            }
        }
    }

    private int terrainYFromCoordsOrWorld(int x, int z, Integer hintY) {
        // 1) из coords.terrainGrid
        try {
            if (coords != null && coords.has("terrainGrid")) {
                JsonObject g = coords.getAsJsonObject("terrainGrid");
                int minX = g.get("minX").getAsInt();
                int minZ = g.get("minZ").getAsInt();
                int w    = g.get("width").getAsInt();
                int h    = g.get("height").getAsInt();
                int ix = x - minX, iz = z - minZ;
                if (ix >= 0 && ix < w && iz >= 0 && iz < h) {
                    JsonArray data = g.getAsJsonArray("data");
                    return data.get(iz * w + ix).getAsInt();
                }
            }
        } catch (Throwable ignore) {}

        // 2) из нашего снимка рельефа
        Integer snap = groundSnapshot.get(BlockPos.asLong(x, 0, z));
        if (snap != null) return snap;

        // 3) строго: НИ-ЧЕ-ГО из мира
        if (STRICT_TERRAIN) {
            return (hintY != null) ? hintY : Integer.MIN_VALUE;
        }

        // 4) нестрого: корректный heightmap (земля), не «верхний блок»
        return level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z) - 1;
    }

    private static String optString(JsonObject o, String k) {
        try { return (o != null && o.has(k) && !o.get(k).isJsonNull()) ? o.get(k).getAsString() : null; }
        catch (Throwable ignore) { return null; }
    }

    @SuppressWarnings("unused")
    private static Integer parseIntSafe(String s) {
        try {
            if (s == null) return null;
            s = s.trim();
            if (s.isEmpty()) return null;
            return Integer.parseInt(s);
        } catch (Exception ignore) { return null; }
    }
    private static Double parseMeters(String s) {
        if (s == null) return null;
        s = s.trim().toLowerCase(Locale.ROOT).replace(',', '.');
        // Берём ПЕРВОЕ число вида  -12.34  или  12  (с необязательным знаком и дробной частью)
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("[-+]?\\d+(?:\\.\\d+)?")
                .matcher(s);
        if (!m.find()) return null;
        try {
            return Double.parseDouble(m.group());
        } catch (Exception e) {
            return null;
        }
    }

    private static Integer parseFirstInt(String s) {
        if (s == null) return null;
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("[-+]?\\d+")
                .matcher(s.trim());
        if (!m.find()) return null;
        try {
            return Integer.parseInt(m.group());
        } catch (Exception e) {
            return null;
        }
    }
    private static Block resolveBlock(String id) {
        Block b = ForgeRegistries.BLOCKS.getValue(ResourceLocation.tryParse(id));
        return (b != null ? b : Blocks.STONE);
    }

    private static int[] latlngToBlock(double lat, double lng,
                                       double centerLat, double centerLng,
                                       double east, double west, double north, double south,
                                       int sizeMeters, int centerX, int centerZ) {
        double dx = (lng - centerLng) / (east - west) * sizeMeters;
        double dz = (lat - centerLat) / (south - north) * sizeMeters;
        int x = (int)Math.round(centerX + dx);
        int z = (int)Math.round(centerZ + dz);
        return new int[]{x, z};
    }

    private static List<int[]> bresenhamLine(int x0, int z0, int x1, int z1) {
        List<int[]> pts = new ArrayList<>();
        int dx = Math.abs(x1 - x0), dz = Math.abs(z1 - z0);
        int sx = x0 < x1 ? 1 : -1;
        int sz = z0 < z1 ? 1 : -1;
        int x = x0, z = z0;

        if (dx >= dz) {
            int err = dx / 2;
            while (x != x1) {
                pts.add(new int[]{x, z});
                err -= dz;
                if (err < 0) { z += sz; err += dx; }
                x += sx;
            }
        } else {
            int err = dz / 2;
            while (z != z1) {
                pts.add(new int[]{x, z});
                err -= dx;
                if (err < 0) { x += sx; err += dz; }
                z += sz;
            }
        }
        pts.add(new int[]{x1, z1});
        return pts;
    }

    private static void putAll(Map<String, String> map, Object[][] pairs) {
        for (Object[] p : pairs) {
            map.put(((String)p[0]).toLowerCase(Locale.ROOT), (String)p[1]);
        }
    }

    // ====== ПРОЧЕЕ ======

    private static int countBy(JsonArray arr, java.util.function.Predicate<JsonObject> pred) {
        int c = 0;
        for (JsonElement je : arr) {
            JsonObject e = je.getAsJsonObject();
            JsonObject tags = (e.has("tags") && e.get("tags").isJsonObject()) ? e.getAsJsonObject("tags") : null;
            if (tags == null) continue;
            if (pred.test(tags)) c++;
        }
        return c;
    }

    // === NEW: многоконтурная геометрия ===
    private static class MultiPoly {
        List<List<int[]>> outers = new ArrayList<>();
        List<List<int[]>> inners = new ArrayList<>();
        JsonObject tags;
    }

    // === NEW: очередь частей для сортировки по стартовой отметке (minOffset) ===
    private static final class PartTask {
        final Set<Long> fill;
        final List<List<int[]>> outers;
        final JsonObject tags;
        final int minOffsetSort;
        PartTask(Set<Long> fill, List<List<int[]>> outers, JsonObject tags, int minOffsetSort) {
            this.fill = fill; this.outers = outers; this.tags = tags; this.minOffsetSort = minOffsetSort;
        }
    }

    private boolean ringContainsRing(List<int[]> outer, List<int[]> inner) {
        int[] c = centroid(inner);
        return pointInPolygon(c[0] + 0.5, c[1] + 0.5, outer);
    }

    private int[] centroidAll(List<List<int[]>> rings) {
        int xMin=Integer.MAX_VALUE,xMax=Integer.MIN_VALUE,zMin=Integer.MAX_VALUE,zMax=Integer.MIN_VALUE;
        for (List<int[]> r: rings) {
            int[] bb = bounds(r);
            xMin=Math.min(xMin,bb[0]); zMin=Math.min(zMin,bb[1]);
            xMax=Math.max(xMax,bb[2]); zMax=Math.max(zMax,bb[3]);
        }
        return new int[]{ (xMin+xMax)>>1, (zMin+zMax)>>1 };
    }

    private Set<Long> rasterizeMultiPolygon(List<List<int[]>> outers, List<List<int[]>> inners,
                                            int minX, int maxX, int minZ, int maxZ) {
        Set<Long> out = new HashSet<>();
        // 1) union всех outer
        List<Set<Long>> outerFills = new ArrayList<>();
        for (List<int[]> o : outers) {
            outerFills.add(rasterizePolygon(o, minX, maxX, minZ, maxZ));
        }
        // 2) вычесть только те inner, которые реально лежат внутри соответствующего outer
        for (int i = 0; i < outers.size(); i++) {
            Set<Long> fill = outerFills.get(i);
            // какие inner относятся к этому outer
            for (List<int[]> in : inners) {
                if (ringContainsRing(outers.get(i), in)) {
                    Set<Long> cut = rasterizePolygon(in, minX, maxX, minZ, maxZ);
                    fill.removeAll(cut);
                }
            }
            out.addAll(fill);
        }
        return out;
    }

    private void buildFromFill(Set<Long> fill, List<List<int[]>> refRings, JsonObject tags,
                            List<int[]> entrances, List<List<int[]>> passages,
                            int minX, int maxX, int minZ, int maxZ, boolean airOnly) {
        if (fill == null || fill.isEmpty()) return;

        boolean isPart = isBuildingPart(tags);

        // базовая отметка по центроиду всех колец
        int[] c = centroidAll(refRings);
        int yBaseSurf = terrainYFromCoordsOrWorld(c[0], c[1], null);
        if (yBaseSurf == Integer.MIN_VALUE) return;

        String roofShape;
        int minOffset, facadeBlocks, roofBlocks;

        if (isPart) {
            PartVertical pv = computePartVertical(tags);
            roofBlocks = parseRoofHeightBlocksForPart(tags); // >=1 (flat=1)

            // входные якоря (уровни/метры)
            Integer lv = parseFirstInt(optString(tags, "building:levels"));
            if (lv == null) lv = parseFirstInt(optString(tags, "levels"));
            if (lv == null) lv = parseFirstInt(optString(tags, "building:levels:aboveground"));

            Integer minLv = parseFirstInt(optString(tags, "building:min_level"));
            if (minLv == null) minLv = parseFirstInt(optString(tags, "min_level"));

            Integer maxLv = parseFirstInt(optString(tags, "building:max_level"));
            if (maxLv == null) maxLv = parseFirstInt(optString(tags, "max_level"));

            Double hTop = parseMeters(optString(tags, "height"));
            if (hTop == null) hTop = parseMeters(optString(tags, "building:height"));
            Double hMin = parseMeters(optString(tags, "min_height"));
            if (hMin == null) hMin = parseMeters(optString(tags, "building:min_height"));

            boolean levelsRange = (minLv != null && maxLv != null);
            boolean metersRange = (hMin != null && hTop != null);

            // откуда стартует часть
            minOffset = hasExplicitMinAnchor(tags)
                    ? Math.max(0, pv.offsetBlocks)
                    : ((FORCE_GROUND_ANCHOR && !isSuspended(tags)) ? 0 : Math.max(0, pv.offsetBlocks));

            // ВАЖНО:
            // 1) если задан ИНТЕРВАЛ (min/max уровни ИЛИ min/height в метрах) — строим РОВНО толщину части, крышу НЕ вычитаем
            // 2) если задано просто кол-во уровней (levels) — так же не вычитаем крышу (крыша сверху)
            // 3) если задан только абсолютный height без min — считаем, что высота включает крышу, её вычитаем
            if (levelsRange || lv != null) {
                facadeBlocks = Math.max(1, pv.heightBlocks); // не вычитаем крышу
            } else if (metersRange) {
                facadeBlocks = Math.max(1, pv.heightBlocks); // не вычитаем крышу
            } else if (hTop != null && hTop > 0) {
                int sliceTotal = Math.max(1, (int)Math.round(hTop));
                if (sliceTotal <= roofBlocks) {
                    roofBlocks = Math.max(1, Math.min(roofBlocks, sliceTotal - 1));
                }
                facadeBlocks = Math.max(1, sliceTotal - roofBlocks);
            } else {
                int sliceTotal = Math.max(1, pv.heightBlocks);
                if (sliceTotal <= roofBlocks) {
                    roofBlocks = Math.max(1, Math.min(roofBlocks, sliceTotal - 1));
                }
                facadeBlocks = Math.max(1, sliceTotal - roofBlocks);
            }

            roofShape = parseRoofShape(tags);

        } else {
            // обычные building=* — ВСЕГДА от рельефа (+1 в localWallBaseYAt), без min_level/min_height
            minOffset    = (FORCE_GROUND_ANCHOR ? 0 : effectiveMinOffset(tags));
            facadeBlocks = parseFacadeHeightBlocks(tags);
            roofBlocks   = parseRoofHeightBlocks(tags);

            int totalBlocks = parseTotalHeightBlocks(tags, facadeBlocks, roofBlocks);
            if (totalBlocks <= roofBlocks) {
                roofBlocks = Math.max(1, Math.min(roofBlocks, totalBlocks - 1));
            }
            facadeBlocks = Math.max(1, totalBlocks - roofBlocks);
            roofShape = parseRoofShape(tags);
        }

        Set<Long> edge = edgeOfFill(fill);

        // подложка под здание (не идёт в этажи)
        if (minOffset == 0 && !fill.isEmpty()) {
            buildFoundation(fill, yBaseSurf);

            // Свет в полу (в фундаментной плите) тем же алгоритмом, что и на этажах
            FloorLightConfig flCfgBase = getFloorLightCfgFromCoords();
            if (minOffset == 0 && FOUNDATION_THICKNESS > 0 && flCfgBase.enabled) {
                placeFloorLightsForLevel(
                    fill,
                    /*minOffset=*/0,
                    /*facadeBlocks=*/facadeBlocks,
                    /*fallbackSurfY=*/yBaseSurf,
                    /*floorIndex=*/0,                    // «нулевой» пол
                    /*thickness=*/FOUNDATION_THICKNESS,  // толщина плиты
                    flCfgBase
                );
            }
        }

        // --- сохраняем id фасада, чтобы понимать «стекло это или нет»
        String facadeId = pickFacadeBlock(tags);
        Block facade = resolveBlock(facadeId);
        Block roof   = resolveBlock(pickRoofBlock(tags));

        // если часть стартует выше земли — ведём «подошву» вниз (не относится к навесам)
        if (shouldExtendDownToGround(tags) && minOffset > 0) {
            extendDownToFirstObstacleOnEdge(edge, minOffset, yBaseSurf, facade);
        }

        clearInteriorForBuilding(
                fill, edge, minOffset, facadeBlocks, yBaseSurf, roofBlocks,
                (minOffset == 0 ? FOUNDATION_THICKNESS : 0)
        );

        // Перекрытия по этажам — используем тот же материал/толщину, что и фундамент
        int floors = estimateFloors(tags, facadeBlocks);
        Block floorBlock = resolveBlock(FOUNDATION_BLOCK_ID);
        boolean skipBase = (minOffset == 0 && FOUNDATION_THICKNESS > 0); // первый «пол» уже положил buildFoundation
        buildPerLevelFloors(fill, edge, minOffset, facadeBlocks, yBaseSurf,
                            floors, floorBlock, FOUNDATION_THICKNESS, skipBase);
        buildInteriorPrivacyWalls(fill, minOffset, facadeBlocks, yBaseSurf, floors);

        // стены по границе
        final int worldMax = worldTopCap();
        for (long key : edge) {
            int x = BlockPos.getX(key), z = BlockPos.getZ(key);
            if (x < minX || x > maxX || z < minZ || z > maxZ) continue;

            int ySurf = terrainYFromCoordsOrWorld(x, z, yBaseSurf);
            if (ySurf == Integer.MIN_VALUE) continue;

            int y0 = ySurf + 1 + Math.max(0, minOffset);
            int y1 = Math.min(worldMax, y0 + Math.max(1, facadeBlocks) - 1);
            for (int y = y0; y <= y1; y++) {
                BlockPos pos = new BlockPos(x, y, z);
                level.setBlock(pos, facade.defaultBlockState(), 3);
            }
        }

        // --- ОКНА: пробиваем окна в стенах (см. блок «ЛОГИКА ОКОН» внизу файла)
        applyWindows(edge, fill, tags, minOffset, facadeBlocks, yBaseSurf, /*totalHeight=*/facadeBlocks + roofBlocks, facadeId);

        // крыша (она тоже не идёт в этажи)
        switch (roofShape) {
            case "gabled"    -> buildRoofGabled  (fill, minOffset, facadeBlocks, roofBlocks, roof, tags, false, yBaseSurf);
            case "skillion"  -> buildRoofSkillion(fill, minOffset, facadeBlocks, roofBlocks, roof, tags, false, yBaseSurf);
            case "pyramidal" -> buildRoofPyramidal(fill, minOffset, facadeBlocks, roofBlocks, roof, tags, false, yBaseSurf);
            case "hip"       -> buildRoofHip     (fill, minOffset, facadeBlocks, roofBlocks, roof, tags, false, yBaseSurf);
            case "halfhip"   -> buildRoofHalfHip (fill, minOffset, facadeBlocks, roofBlocks, roof, tags, false, yBaseSurf);
            case "gambrel"   -> buildRoofGambrel (fill, minOffset, facadeBlocks, roofBlocks, roof, tags, false, yBaseSurf);
            case "mansard"   -> buildRoofMansard (fill, minOffset, facadeBlocks, roofBlocks, roof, tags, false, yBaseSurf);
            case "dome"      -> buildRoofDome    (fill, minOffset, facadeBlocks, roofBlocks, roof, tags, false, yBaseSurf);
            case "onion"     -> buildRoofOnion   (fill, minOffset, facadeBlocks, roofBlocks, roof, tags, false, yBaseSurf);
            case "conical"   -> buildRoofConical (fill, minOffset, facadeBlocks, roofBlocks, roof, tags, false, yBaseSurf);
            case "sawtooth"  -> buildRoofSawtooth(fill, minOffset, facadeBlocks, roofBlocks, roof, tags, false, yBaseSurf);
            case "butterfly" -> buildRoofButterfly(fill, minOffset, facadeBlocks, roofBlocks, roof, tags, false, yBaseSurf);
            case "barrel"    -> buildRoofBarrel  (fill, minOffset, facadeBlocks, roofBlocks, roof, tags, false, yBaseSurf);
            case "saltbox"   -> buildRoofSaltbox (fill, minOffset, facadeBlocks, roofBlocks, roof, tags, false, yBaseSurf);
            default          -> buildRoofFlat    (fill, minOffset, facadeBlocks, roofBlocks, roof, false, yBaseSurf);
        }

        int buildingTotalForPassage = Math.max(1, facadeBlocks + Math.max(0, roofBlocks));

        carvePassages(
            fill, passages,
            /*minOffset=*/minOffset,
            /*buildingHeightBlocks=*/buildingTotalForPassage,
            /*fallbackSurfY=*/yBaseSurf
        );

        dressPassagesAsStoneSleeve(
            fill, passages,
            /*minOffset=*/minOffset,
            /*buildingHeightBlocks=*/buildingTotalForPassage,
            /*fallbackSurfY=*/yBaseSurf
        );

        placeDoorsOnEntrances(edge, fill, entrances, /*minOffset=*/minOffset, /*fallbackSurfY:*/ yBaseSurf);

        // Периметральные фонари (после дверей, чтобы не конфликтовать)
        placePerimeterLanterns(edge, fill, /*minOffset=*/minOffset, /*facadeBlocks=*/facadeBlocks, /*fallbackSurfY=*/yBaseSurf);

        if (shouldExtendDownToGround(tags) && minOffset > 0) {
            extendDownToFirstObstacleOnEdge(edge, minOffset, yBaseSurf, facade);
        }
    }

    @SuppressWarnings("unused")
    // === высота опорной «кровли» по реальному рельефу на границе здания ===
    private int refSurfaceYForEdge(Set<Long> edge, int fallbackSurfY) {
        if (edge == null || edge.isEmpty()) return fallbackSurfY;
        List<Integer> ys = new ArrayList<>(edge.size());
        for (long k : edge) {
            int x = BlockPos.getX(k), z = BlockPos.getZ(k);
            int ySurf = terrainYFromCoordsOrWorld(x, z, fallbackSurfY);
            if (ySurf != Integer.MIN_VALUE) ys.add(ySurf);
        }
        if (ys.isEmpty()) return fallbackSurfY;
        Collections.sort(ys);
        // берём «почти максимум» (90-й перцентиль), чтобы не реагировать на один странный пик
        int idx = Math.min(ys.size()-1, (int)Math.floor(0.90 * (ys.size()-1)));
        return ys.get(idx);
    }

    // локальная база стены в клетке с учётом рельефа и min_offset
    private int localWallBaseYAt(int x, int z, int minOffset, int fallbackSurfY) {
        int ySurf = terrainYFromCoordsOrWorld(x, z, fallbackSurfY);
        if (ySurf == Integer.MIN_VALUE) ySurf = fallbackSurfY;
        return ySurf + 1 + Math.max(0, minOffset);
    }

    // Локальная вершина стены (с учётом minOffset и facadeBlocks)
    private int localWallTopYAt(int x, int z, int minOffset, int facadeBlocks, int fallbackSurfY) {
        final int worldMax = worldTopCap();
        int y0 = localWallBaseYAt(x, z, minOffset, fallbackSurfY);
        int yTop = y0 + Math.max(1, facadeBlocks) - 1;
        return Math.min(worldMax, yTop);
    }

    @SuppressWarnings("unused")
    // оценка верхней точки крыши (для расчистки объёма)
    private int estimateRoofTopY(int yWallTop, int roofBlocks) {
        final int worldMax = worldTopCap();
        int yRoofTop = yWallTop + Math.max(1, roofBlocks);
        return Math.min(worldMax, yRoofTop);
    }

    // очистить внутренности здания (кроме стен) от локальной базы до кровли
    private void clearInteriorForBuilding(Set<Long> fill, Set<Long> edge,
                                        int minOffset, int facadeBlocks, int fallbackSurfY,
                                        int roofBlocks, int foundationThickness) {
        if (fill == null || fill.isEmpty()) return;
        final int worldMax = worldTopCap();

        for (long k : fill) {
            if (edge.contains(k)) continue; // стены не трогаем
            int x = BlockPos.getX(k), z = BlockPos.getZ(k);

            int y0 = localWallBaseYAt(x, z, minOffset, fallbackSurfY);
            // === NEW: если часть начинается выше земли, не трогаем САМЫЙ первый слой (стык с нижней частью)
            if (minOffset > 0) {
                y0 += 1; // сохраняем потолок нижней части / пол верхней
            } else if (foundationThickness > 0) {
                y0 += foundationThickness; // как и раньше для базового этажа
            }
            int yTopLocal = localWallTopYAt(x, z, minOffset, facadeBlocks, fallbackSurfY);
            int y1 = Math.min(worldMax, yTopLocal + Math.max(1, roofBlocks) + 1);

            for (int y = y0; y <= y1; y++) {
                level.setBlock(new BlockPos(x, y, z), Blocks.AIR.defaultBlockState(), 3);
            }
        }
    }


    private int effectiveMinOffset(JsonObject tags) {
        // Хотим ВСЁ от рельефа +1. Исключение — подвесы/мосты/arcade/слои.
        if (FORCE_GROUND_ANCHOR) {
            return isSuspended(tags) ? Math.max(0, parseMinOffsetBlocks(tags)) : 0;
        }

        // (старое поведение — если вдруг понадобится откатить)
        if (tags != null && isBuildingPart(tags)) {
            return isSuspended(tags) ? parseMinOffsetBlocks(tags) : 0;
        }
        return parseMinOffsetBlocks(tags);
    }



    private int parseRoofHeightBlocksForPart(JsonObject tags) {
        if (tags == null) return 1; // дефолт: хотя бы 1 блок плоской крыши

        Integer roofLv = parseFirstInt(optString(tags, "roof:levels"));
        if (roofLv != null && roofLv > 0) return Math.max(1, roofLv * LEVEL_HEIGHT);

        Double rh = parseMeters(optString(tags, "roof:height"));
        if (rh != null && rh > 0) return Math.max(1, (int)Math.round(rh));

        // ВАЖНО: всегда вычисляем форму через parseRoofShape(tags),
        // которая сама даёт "flat" по умолчанию
        String shape = parseRoofShape(tags);
        return "flat".equals(shape) ? 1 : 3;
    }


    // === helpers ===
    private boolean hasAny(JsonObject o, String... keys) {
        if (o == null) return false;
        for (String k : keys) if (o.has(k)) return true;
        return false;
    }

    // relation.tags ⊕ outer-ways.tags (relation главнее; заполняем только отсутствующие)
    private JsonObject mergeBuildingTags(JsonObject relTags, List<JsonObject> outerMemberTags) {
        JsonObject out = (relTags != null) ? relTags.deepCopy() : new JsonObject();
        boolean isPart = out.has("building:part");

        for (JsonObject mt : outerMemberTags) {
            if (mt == null) continue;
            for (Map.Entry<String, JsonElement> e : mt.entrySet()) {
                String k = e.getKey();

                // КРИТИЧНО: для building:part не тянем структурные высоты/этажи/кровлю из членов
                if (isPart && STRUCTURAL_HEIGHT_KEYS.contains(k) && !k.startsWith("roof:")) continue;


                if (!out.has(k) || out.get(k).isJsonNull() ||
                    (out.get(k).isJsonPrimitive() && out.get(k).getAsString().isBlank())) {
                    out.add(k, e.getValue());
                }
            }
        }

        // Старые «чистки» оставляем только для НЕ-частей
        if (!isPart && hasAny(out, "building:levels","levels","building:levels:aboveground")) {
            out.remove("height"); out.remove("building:height");
        }
        if (!isPart && out.has("building:min_level")) {
            out.remove("min_height"); out.remove("building:min_height");
        }
        return out;
    }

    // Явно задана стартовая отметка части (а не просто "уровни"/"высота"):
    private boolean hasExplicitMinAnchor(JsonObject tags) {
        // допускаем ещё редкий "min_level"
        if (parseFirstInt(optString(tags, "building:min_level")) != null) return true;
        if (parseFirstInt(optString(tags, "min_level")) != null) return true;
        if (parseMeters(optString(tags, "min_height")) != null) return true;
        if (parseMeters(optString(tags, "building:min_height")) != null) return true;
        return false;
    }

    // Подвесные/мостовые кейсы — для них min_* можно уважать
    private boolean isSuspended(JsonObject tags) {
        String bridge = normalize(optString(tags, "bridge"));
        String tunnel = normalize(optString(tags, "tunnel"));
        String layer  = normalize(optString(tags, "layer"));
        return "yes".equals(bridge) || "bridge".equals(bridge) || "building_passage".equals(tunnel) || (layer != null && !layer.isBlank());
    }

    // ── NEW: считать ли объект «явным навесом»
    private boolean isExplicitCanopy(JsonObject tags) {
        String b  = normalize(optString(tags, "building"));
        String mm = normalize(optString(tags, "man_made"));
        return "roof".equals(b) || "canopy".equals(mm);
    }

    // ── NEW: решаем, нужно ли вести часть вниз до земли
    private boolean shouldExtendDownToGround(JsonObject tags) {
        if (tags == null) return false;
        if (isExplicitCanopy(tags)) return false;

        boolean hasMin = hasExplicitMinAnchor(tags);
        boolean hasMax = hasExplicitMaxAnchor(tags); // см. новый хелпер ниже

        return isBuildingPart(tags) && hasMin && hasMax;
    }

    private boolean hasExplicitMaxAnchor(JsonObject tags) {
        if (parseFirstInt(optString(tags, "building:max_level")) != null) return true;
        if (parseFirstInt(optString(tags, "max_level")) != null) return true;
        if (parseMeters(optString(tags, "height")) != null) return true;
        if (parseMeters(optString(tags, "building:height")) != null) return true;
        return false;
    }

    @SuppressWarnings("unused")
    private void extendDownToGround(Set<Long> fill, int minOffset, int fallbackSurfY, Block material) {
        extendDownToFirstObstacleOnEdge(edgeOfFill(fill), minOffset, fallbackSurfY, material);
    }

    // Новый вариант: тянем "юбку" вниз по ПЕРИМЕТРУ до первого встречного блока (крыша/перекрытие и т.п.).
    // Не спускаемся «до земли» — если препятствий нет, ничего не делаем.
    private void extendDownToFirstObstacleOnEdge(Set<Long> edge, int minOffset, int fallbackSurfY, Block material) {

    if (edge == null || edge.isEmpty() || minOffset <= 0) return;
    final int worldMax = worldTopCap();

    for (long k : edge) {
        int x = BlockPos.getX(k), z = BlockPos.getZ(k);
        int ySurf = terrainYFromCoordsOrWorld(x, z, fallbackSurfY);
        if (ySurf == Integer.MIN_VALUE) continue;

        int yBase = localWallBaseYAt(x, z, minOffset, fallbackSurfY); // нижняя отметка части
        int yTopToFill = Math.min(worldMax, yBase - 1);
        if (yTopToFill < ySurf + 1) continue;

        // ищем первое НЕ-воздух препятствие сверху-вниз
        int yObstacle = Integer.MIN_VALUE;
        for (int yy = yTopToFill; yy >= ySurf + 1; yy--) {
            if (!level.getBlockState(new BlockPos(x, yy, z)).isAir()) {
                yObstacle = yy;
                break;
            }
        }
        // если препятствий нет — считаем препятствием сам грунт (ySurf)
        if (yObstacle == Integer.MIN_VALUE) yObstacle = ySurf;

        // заполняем воздух между препятствием и низом части
        for (int yy = yObstacle + 1; yy <= yTopToFill; yy++) {
            BlockPos p = new BlockPos(x, yy, z);
            if (level.getBlockState(p).isAir()) {
                level.setBlock(p, material.defaultBlockState(), 3);
            }
        }
    }
        
    }

    /** Подложка из каменного кирпича по всему fill на уровне земли. Не влияет на высоту этажей. */
    private void buildFoundation(Set<Long> fill, int fallbackSurfY) {
        if (FOUNDATION_THICKNESS <= 0 || fill == null || fill.isEmpty()) return;
        final int worldMax = worldTopCap();
        Block foundation = resolveBlock(FOUNDATION_BLOCK_ID);

        for (long k : fill) {
            int x = BlockPos.getX(k), z = BlockPos.getZ(k);
            int ySurf = terrainYFromCoordsOrWorld(x, z, fallbackSurfY);
            if (ySurf == Integer.MIN_VALUE) continue;

            int y0 = ySurf + 1;
            int y1 = Math.min(worldMax, y0 + FOUNDATION_THICKNESS - 1);
            for (int y = y0; y <= y1; y++) {
                level.setBlock(new BlockPos(x, y, z), foundation.defaultBlockState(), 3);
            }
        }
    }

    private int estimateFloors(JsonObject tags, int facadeBlocks) {
        Integer lv = parseFirstInt(optString(tags, "building:levels"));
        if (lv == null) lv = parseFirstInt(optString(tags, "levels"));
        if (lv == null) lv = parseFirstInt(optString(tags, "building:levels:aboveground"));

        Integer minLv = parseFirstInt(optString(tags, "building:min_level"));
        if (minLv == null) minLv = parseFirstInt(optString(tags, "min_level"));
        Integer maxLv = parseFirstInt(optString(tags, "building:max_level"));
        if (maxLv == null) maxLv = parseFirstInt(optString(tags, "max_level"));

        if (lv != null && lv > 0) return Math.max(1, lv);
        if (minLv != null && maxLv != null && maxLv >= minLv) return Math.max(1, maxLv - minLv + 1);

        // если этажи не заданы — приблизим по высоте фасада
        return Math.max(1, facadeBlocks / LEVEL_HEIGHT);
    }

    private void buildPerLevelFloors(Set<Long> fill, Set<Long> edge,
                                    int minOffset, int facadeBlocks, int fallbackSurfY,
                                    int floors, Block floorBlock, int thickness,
                                    boolean skipBaseIfFoundation) {
        if (fill == null || fill.isEmpty() || floors <= 0 || thickness <= 0) return;
        final int worldMax = worldTopCap();
        FloorLightConfig flCfg = getFloorLightCfgFromCoords();

        int startLevelIdx = skipBaseIfFoundation ? 1 : 0;

        for (int k = startLevelIdx; k < floors; k++) {
            // сначала заливаем сам пол
            for (long key : fill) {
                if (edge.contains(key)) continue; // не лезем в стену
                int x = BlockPos.getX(key), z = BlockPos.getZ(key);

                int yBase = localWallBaseYAt(x, z, minOffset, fallbackSurfY);
                int yTop  = localWallTopYAt(x, z, minOffset, facadeBlocks, fallbackSurfY);

                int y0 = yBase + k * LEVEL_HEIGHT;
                int y1 = Math.min(worldMax, Math.min(yTop, y0 + thickness - 1));
                for (int y = y0; y <= y1; y++) {
                    BlockPos pos = new BlockPos(x, y, z);
                    // === NEW: на стыке (k==0 и minOffset>0) не трогаем уже существующий потолок нижней части
                    if (k == 0 && minOffset > 0 && !level.getBlockState(pos).isAir()) {
                        continue;
                    }
                    level.setBlock(pos, floorBlock.defaultBlockState(), 3);
                }
            }

            // затем встраиваем свет (заменяем нужные блоки пола на световой блок)
            placeFloorLightsForLevel(fill, minOffset, facadeBlocks, fallbackSurfY, k, thickness, flCfg);
        }
    }


    private static class FloorLightConfig {
        boolean enabled;
        String blockId;
        int edgeMargin;
        int minSpacing;
        int period;
    }

    private FloorLightConfig getFloorLightCfgFromCoords() {
        FloorLightConfig cfg = new FloorLightConfig();
        cfg.enabled    = FLOOR_LIGHTING_ENABLED_DEF;
        cfg.blockId    = FLOOR_LIGHT_BLOCK_ID_DEF;
        cfg.edgeMargin = FLOOR_LIGHT_EDGE_MARGIN_DEF;
        cfg.minSpacing = FLOOR_LIGHT_MIN_SPACING_DEF;
        cfg.period     = FLOOR_LIGHT_PERIOD_DEF;

        try {
            JsonObject root = this.coords;
            JsonObject fl = null;
            if (root != null) {
                if (root.has("floorLighting") && root.get("floorLighting").isJsonObject())
                    fl = root.getAsJsonObject("floorLighting");
                else if (root.has("options") && root.getAsJsonObject("options").has("floorLighting"))
                    fl = root.getAsJsonObject("options").getAsJsonObject("floorLighting");
            }
            if (fl != null) {
                if (fl.has("enabled"))    cfg.enabled    = fl.get("enabled").getAsBoolean();
                if (fl.has("block"))      cfg.blockId    = fl.get("block").getAsString();
                if (fl.has("edgeMargin")) cfg.edgeMargin = Math.max(0, fl.get("edgeMargin").getAsInt());
                if (fl.has("minSpacing")) cfg.minSpacing = Math.max(1, fl.get("minSpacing").getAsInt());
                if (fl.has("period"))     cfg.period     = Math.max(1, fl.get("period").getAsInt());
            }
        } catch (Throwable ignore) {}
        return cfg;
    }

    private boolean isDeepInside(Set<Long> fill, int x, int z, int margin) {
        if (margin <= 0) return fill.contains(BlockPos.asLong(x,0,z));
        for (int dx = -margin; dx <= margin; dx++) {
            for (int dz = -margin; dz <= margin; dz++) {
                if (!fill.contains(BlockPos.asLong(x+dx, 0, z+dz))) return false;
            }
        }
        return true;
    }

    private boolean farEnoughFrom(List<int[]> placed, int x, int z, int minSpacing) {
        int r2 = minSpacing * minSpacing;
        for (int[] p : placed) {
            int dx = x - p[0], dz = z - p[1];
            if (dx*dx + dz*dz < r2) return false;
        }
        return true;
    }

    private void placeFloorLightsForLevel(Set<Long> fill,
                                        int minOffset, int facadeBlocks, int fallbackSurfY,
                                        int floorIndex, int thickness, FloorLightConfig cfg) {
        if (!cfg.enabled || fill == null || fill.isEmpty()) return;

        Block light = resolveBlock(cfg.blockId);
        int[] bb = bounds(fill);
        int fromX = bb[0] + cfg.edgeMargin;
        int toX   = bb[2] - cfg.edgeMargin;
        int fromZ = bb[1] + cfg.edgeMargin;
        int toZ   = bb[3] - cfg.edgeMargin;
        if (fromX > toX || fromZ > toZ) return;

        List<int[]> placed = new ArrayList<>();
        int step = Math.max(1, cfg.period);

        for (int z = fromZ; z <= toZ; z += step) {
            for (int x = fromX; x <= toX; x += step) {
                if (!containsXZ(fill, x, z)) continue;
                if (!isDeepInside(fill, x, z, cfg.edgeMargin)) continue;
                if (!farEnoughFrom(placed, x, z, cfg.minSpacing)) continue;

                int yBase = localWallBaseYAt(x, z, minOffset, fallbackSurfY);
                int yTop  = localWallTopYAt(x, z, minOffset, facadeBlocks, fallbackSurfY);
                int y0    = yBase + floorIndex * LEVEL_HEIGHT;
                int y     = Math.min(yTop, y0 + Math.max(0, thickness - 1)); // верхний слой пола

                BlockPos pos = new BlockPos(x, y, z);
                // === NEW: на стыке первого пола верхней части не заменяем чужой блок светом
                if (!(minOffset > 0 && floorIndex == 0 && !level.getBlockState(pos).isAir())) {
                    level.setBlock(pos, light.defaultBlockState(), 3);
                }
                placed.add(new int[]{x, z});
            }
        }
    }

    // "Эрозия" полигона: сдвигаем внутрь на 1 слой (убираем граничные клетки)
    private Set<Long> erodeByOne(Set<Long> cur) {
        if (cur == null || cur.isEmpty()) return Collections.emptySet();
        Set<Long> next = new HashSet<>();
        for (long k : cur) {
            int x = BlockPos.getX(k), z = BlockPos.getZ(k);
            if (cur.contains(BlockPos.asLong(x+1,0,z)) &&
                cur.contains(BlockPos.asLong(x-1,0,z)) &&
                cur.contains(BlockPos.asLong(x,0,z+1)) &&
                cur.contains(BlockPos.asLong(x,0,z-1))) {
                next.add(k);
            }
        }
        return next;
    }
    private Set<Long> erodeFill(Set<Long> src, int steps) {
        if (src == null || src.isEmpty() || steps <= 0) return Collections.emptySet();
        Set<Long> cur = new HashSet<>(src);
        for (int i = 0; i < steps && !cur.isEmpty(); i++) {
            cur = erodeByOne(cur);
        }
        return cur;
    }

    /** Внутренняя перегородка: кольцо на отступе PRIVACY_WALL_OFFSET_BLOCKS; на каждом этаже — 3 блока вверх от пола */
    private void buildInteriorPrivacyWalls(Set<Long> outerFill,
                                        int minOffset, int facadeBlocks, int fallbackSurfY,
                                        int floors) {
        if (!PRIVACY_WALLS_ENABLED || outerFill == null || outerFill.isEmpty() || floors <= 0) return;
        // сдвигаем периметр внутрь на PRIVACY_WALL_OFFSET_BLOCKS
        Set<Long> innerFill = erodeFill(outerFill, Math.max(1, PRIVACY_WALL_OFFSET_BLOCKS));
        if (innerFill.isEmpty()) return; // здание слишком узкое — не помещается
        Set<Long> ring = edgeOfFill(innerFill);
        if (ring.isEmpty()) return;

        Block wall = resolveBlock(PRIVACY_WALL_BLOCK_ID);
        final int worldMax = worldTopCap();
        final int thickness = Math.max(1, FOUNDATION_THICKNESS); // пол мы кладём этой же толщиной

        for (long key : ring) {
            int x = BlockPos.getX(key), z = BlockPos.getZ(key);
            int yBase = localWallBaseYAt(x, z, minOffset, fallbackSurfY);
            int yTop  = localWallTopYAt(x, z, minOffset, facadeBlocks, fallbackSurfY);

            for (int k = 0; k < floors; k++) {
                // верх текущего пола (включая фундаментный "0-й")
                int yFloorTop = Math.min(yTop, yBase + k * LEVEL_HEIGHT + thickness - 1);
                int y0 = Math.min(worldMax, yFloorTop + 1);
                int y1 = Math.min(worldMax, Math.min(yTop, y0 + Math.max(1, PRIVACY_WALL_HEIGHT_BLOCKS) - 1));

                for (int y = y0; y <= y1; y++) {
                    level.setBlock(new BlockPos(x, y, z), wall.defaultBlockState(), 3);
                }
            }
        }
    }

    // Есть ли стекло в id блока
    private boolean isGlassLikeBlock(Block b) {
        if (b == null) return false;
        ResourceLocation rl = ForgeRegistries.BLOCKS.getKey(b);
        String id = (rl != null ? rl.toString() : "").toLowerCase(Locale.ROOT);
        return id.contains("glass"); // стекло любого вида (в т.ч. stained/tinted/pane)
    }

    // Наружное направление для клетки периметра (куда нет fill)
    private Direction outwardDir(Set<Long> fill, int x, int z) {
        for (Direction d : new Direction[]{Direction.EAST, Direction.WEST, Direction.SOUTH, Direction.NORTH}) {
            int nx = x + d.getStepX(), nz = z + d.getStepZ();
            if (!containsXZ(fill, nx, nz)) return d;
        }
        return null;
    }

    private void placePerimeterLanterns(Set<Long> edge, Set<Long> fill,
                                        int minOffset, int facadeBlocks, int fallbackSurfY) {
        if (!PERIMETER_LANTERNS_ENABLED || edge == null || edge.isEmpty()) return;

        @SuppressWarnings("unused")
        final int worldMax = worldTopCap();

        // Кронштейн и подвесной фонарь
        BlockState bracketState = Blocks.CHISELED_STONE_BRICKS.defaultBlockState();
        BlockState lanternState = Blocks.LANTERN.defaultBlockState();
        if (lanternState.hasProperty(LanternBlock.HANGING)) {
            lanternState = lanternState.setValue(LanternBlock.HANGING, true);
        }

        // Чтобы не ставить дважды в одну точку периметра при «поиске ближайшего»
        Set<Long> usedEdgeSpots = new HashSet<>();

        for (long key : edge) {
            int x = BlockPos.getX(key), z = BlockPos.getZ(key);

            // дискретизация "каждые 4" как и в окнах/опорах (якорь по x+z)
            if (Math.floorMod(x + z, Math.max(1, PERIM_LANTERN_STEP)) != 0) continue;

            int ySurf = terrainYFromCoordsOrWorld(x, z, fallbackSurfY);
            if (ySurf == Integer.MIN_VALUE) continue;

            int yBase = localWallBaseYAt(x, z, minOffset, fallbackSurfY);
            int yTop  = localWallTopYAt(x, z, minOffset, facadeBlocks, fallbackSurfY);

            // желаемая высота 4 от земли; если здание выше/ниже — прижмём в диапазон стены
            int yDesired = ySurf + PERIM_LANTERN_HEIGHT;
            int y = Math.max(yBase, Math.min(yDesired, yTop));
            if (y < yBase || y > yTop) continue; // на этом участке стены высота недоступна

            boolean placed = false;

            // если место плохое (стекло/мешает), ищем ближайшее на периметре в радиусе
            for (int r = 0; r <= PERIM_LANTERN_SEARCH_RAD && !placed; r++) {
                // перебор манхэттен-окружности
                for (int dx = -r; dx <= r && !placed; dx++) {
                    int dzAbs = r - Math.abs(dx);
                    for (int sgn : new int[]{-1, 1}) {
                        int dz = dzAbs * sgn;
                        int cx = x + dx, cz = z + dz;
                        long ek = BlockPos.asLong(cx, 0, cz);
                        if (!edge.contains(ek) || usedEdgeSpots.contains(ek)) continue;

                        Direction out = outwardDir(fill, cx, cz);
                        if (out == null) continue;

                        BlockPos wallPos    = new BlockPos(cx, y, cz);
                        BlockPos bracketPos = wallPos.relative(out);
                        BlockPos lanternPos = bracketPos.below();

                        // стена должна быть не-стекло
                        if (isGlassLikeBlock(level.getBlockState(wallPos).getBlock())) continue;

                        // снаружи должно быть куда поставить кронштейн и фонарь
                        if (!level.getBlockState(bracketPos).isAir()) continue;
                        if (!level.getBlockState(lanternPos).isAir()) continue;

                        // ставим
                        level.setBlock(bracketPos, bracketState, 3);
                        level.setBlock(lanternPos, lanternState, 3);

                        usedEdgeSpots.add(ek);
                        placed = true;
                    }
                }
            }
            // если в радиусе ничего подходящего — просто пропускаем эту «четвёрку»
        }
    }

    // ============================================================================
    // ============================ ЛОГИКА ОКОН ===================================
    // ============================================================================

    /** Типы культовых зданий, где окна не ставим. */
    private static final Set<String> WORSHIP_TYPES = new HashSet<>(Arrays.asList(
            "church","cathedral","chapel","mosque","synagogue","temple","shrine","monastery","place_of_worship"
    ));

    /** Сам объект — культовый? (по его собственным тегам) */
    private boolean isPlaceOfWorship(JsonObject tags) {
        String amenity = normalize(optString(tags, "amenity"));
        String btype   = normalize(optString(tags, "building"));
        if ("place_of_worship".equals(amenity)) return true;
        return btype != null && WORSHIP_TYPES.contains(btype);
    }

    /** Похож ли id фасада на стеклянный (лента стекла — окна не ставим) */
    private boolean isGlassLikeBlockId(String id) {
        if (id == null) return false;
        String s = id.toLowerCase(Locale.ROOT);
        return s.endsWith(":glass") || s.endsWith("tinted_glass") || s.contains("_stained_glass");
    }

    /** Важно: часть принадлежит родителю из «запрещённых»? Тогда окна не ставим и на части. */
    private boolean isForbiddenForWindows(JsonObject tagsOfThisObject, Set<Long> thisFill) {
        // 1) сам объект запрещён?
        if (isPlaceOfWorship(tagsOfThisObject)) return true;

        // 2) если есть родительские оболочки, проверяем, лежит ли часть внутри культового родителя
        if (thisFill != null && !thisFill.isEmpty() && parentShells != null) {
            for (Shell sh : parentShells) {
                if (sh == null || sh.fill == null || sh.fill.isEmpty()) continue;
                // быстрый тест: есть ли пересечение по клеткам XZ
                boolean overlaps = false;
                // итерируемся по меньшему множеству — обычно fill части меньше
                Set<Long> a = thisFill.size() <= sh.fill.size() ? thisFill : sh.fill;
                Set<Long> b = thisFill.size() <= sh.fill.size() ? sh.fill   : thisFill;
                for (long k : a) {
                    if (b.contains(k)) { overlaps = true; break; }
                }
                if (!overlaps) continue;

                // если теги родителя — культовые, то запрещаем окна на части
                if (isPlaceOfWorship(sh.tags)) return true;
            }
        }
        return false;
    }

    /**
     * Пробивка окон на фасаде.
     * ВАЖНО: теперь окна не ставятся, если объект сам культовый ИЛИ лежит внутри культового родителя.
     * Ширина «окна» по факту — 2 столбца на прямых участках (шаблон 2 on → 2 off по манхэттен-индексу).
     */
    private void applyWindows(Set<Long> edge,
                            Set<Long> fill,
                            JsonObject tags,
                            int minOffset,
                            int facadeBlocks,
                            int fallbackSurfY,
                            int totalHeightBlocks,
                            String facadeBlockId) {
        if (edge == null || edge.isEmpty()) return;

        // ГЛАВНОЕ: стоп для культовых и для частей, принадлежащих культовому родителю
        if (isForbiddenForWindows(tags, fill)) return;

        // Если сам фасад уже «стеклянный» — не размазываем окна
        if (isGlassLikeBlockId(facadeBlockId)) return;

        // Вертикальные параметры
        boolean tall = totalHeightBlocks >= 100;
        final int winH   = tall ? 3 : 2;   // высота окна
        final int vStart = tall ? 1 : 2;   // отступ снизу
        final int vGap   = tall ? 1 : 2;   // зазор между рядами
        final int vStep  = winH + vGap;

        // Горизонтальный рисунок «WW..WW..» через манхэттен-индекс
        final int ON_COLS  = 2;  // ширина «окна» по X/Z в столбцах
        final int OFF_COLS = 2;  // ширина пропуска
        final int PERIOD   = ON_COLS + OFF_COLS; // 4

        Block glass = resolveBlock("minecraft:glass");

        // общий якорь паттерна для всего ребра
        int[] bb  = bounds(edge);
        int minX  = bb[0];
        int minZ  = bb[1];

        for (long key : edge) {
            int x = BlockPos.getX(key);
            int z = BlockPos.getZ(key);

            // два столбца подряд — «окно», два — «пропуск»
            int idx = Math.floorMod((x - minX) + (z - minZ), PERIOD);
            if (idx >= ON_COLS) continue; // попали в «пропуск»

            int yBase = localWallBaseYAt(x, z, minOffset, fallbackSurfY);
            int yTop  = localWallTopYAt(x, z, minOffset, facadeBlocks, fallbackSurfY);

            for (int yStart = yBase + vStart; yStart + winH - 1 <= yTop; yStart += vStep) {
                for (int dy = 0; dy < winH; dy++) {
                    int y = yStart + dy;
                    level.setBlock(new BlockPos(x, y, z), glass.defaultBlockState(), 3);
                }
            }
        }
    }


}
