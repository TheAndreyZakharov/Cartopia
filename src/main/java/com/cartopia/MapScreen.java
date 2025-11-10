package com.cartopia;

import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.screens.ConfirmLinkScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Player;

import javax.annotation.Nonnull;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class MapScreen extends Screen {

    // --- РЕСУРСЫ -------------------------------------------------------------

    private static final String LOGO_PATH = "textures/gui/cartopia_logo_globe.png";
    private ResourceLocation logoRl;

    // --- ЗАГОЛОВОК -----------------------------------------------------------

    // коэффициент увеличения шрифта заголовка
    private static final float TITLE_SCALE = 1.6f;
    private static final int LOGO_W = 24, LOGO_H = 24, LOGO_GAP = 6;

    // --- ЛЕЙАУТ --------------------------------------------------------------

    private int splitX;                 // вертикальная линия раздела (середина)
    private final int leftPad = 18;
    private final int rightPad = 18;

    // Область для скролла слева (обновляется каждый кадр в render)
    private int leftViewportTop;
    private int leftViewportBottom;
    private int leftViewportX;
    private int leftViewportW;

    // Скролл
    private int leftScrollY = 0;
    private int leftContentHeight = 0;

    // --- СОСТОЯНИЕ -----------------------------------------------------------

    private boolean realtimeEnabled = true; // подтягиваем с локального сервера

    // --- ВИДЖЕТЫ -------------------------------------------------------------

    private Button openMapBtn;
    private CycleButton<Boolean> realtimeToggle;

    // Кликабельные ссылки в футере
    private static class LinkRegion {
        int x, y, w, h;
        String url;
        LinkRegion(int x, int y, int w, int h, String url) {
            this.x = x; this.y = y; this.w = w; this.h = h; this.url = url;
        }
        boolean hit(int mx, int my) {
            return mx >= x && mx <= x + w && my >= y && my <= y + h;
        }
    }
    private final List<LinkRegion> linkRegions = new ArrayList<>();

    private static class Token {
        final String text;
        final String url; // null = просто текст
        Token(String text, String url) { this.text = text; this.url = url; }
        boolean isLink() { return url != null; }
    }

    protected MapScreen() {
        super(Component.literal("Cartopia"));
    }

    @Override
    protected void init() {
        // Разделим экран пополам
        this.splitX = this.width / 2;

        // Логотип: безопасный парсинг без deprecated-конструктора
        try {
            this.logoRl = ResourceLocation.tryParse(CartopiaMod.MODID + ":" + LOGO_PATH);
        } catch (Exception e) {
            this.logoRl = null;
        }

        // 1) Узнаём состояние realtime
        try {
            HttpURLConnection conn = (HttpURLConnection) new URL("http://127.0.0.1:4567/realtime").openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(1000);
            conn.setReadTimeout(1000);
            if (conn.getResponseCode() / 100 == 2) {
                String s = new String(conn.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
                this.realtimeEnabled = s.contains("\"enabled\":true");
            }
        } catch (Exception ignore) {}

        // 2) Правая колонка — две кнопки (уже и подвинем их в render)
        int btnW = 180, btnH = 20; // уже!
        int rightCenterX = splitX + (this.width - splitX) / 2;
        int btnX = rightCenterX - btnW / 2;
        int tmpY1 = this.height / 2 - 10;
        int tmpY2 = tmpY1 + 30;

        this.openMapBtn = this.addRenderableWidget(
                Button.builder(Component.literal("Open Map"), btn -> openMapWithConfirm())
                    .pos(btnX, tmpY1).size(btnW, btnH).build()
        );

        this.realtimeToggle = this.addRenderableWidget(
                CycleButton.<Boolean>builder(val -> val ? Component.literal("ON") : Component.literal("OFF"))
                        .withValues(Boolean.TRUE, Boolean.FALSE)
                        .withInitialValue(realtimeEnabled)
                        .create(
                                btnX, tmpY2, btnW, btnH,
                                Component.literal("Real-world time/weather"),
                                (btn, value) -> {
                                    this.realtimeEnabled = value;
                                    try {
                                        String json = "{\"enabled\":" + (value ? "true" : "false") + "}";
                                        HttpURLConnection conn = (HttpURLConnection) new URL("http://127.0.0.1:4567/realtime").openConnection();
                                        conn.setDoOutput(true);
                                        conn.setRequestMethod("POST");
                                        conn.setRequestProperty("Content-Type", "application/json");
                                        try (OutputStream os = conn.getOutputStream()) {
                                            os.write(json.getBytes(StandardCharsets.UTF_8));
                                        }
                                        conn.getResponseCode();
                                    } catch (Exception e) {
                                        e.printStackTrace();
                                    }
                                }
                        )
        );

        // Сбросим скролл при переинициализации
        this.leftScrollY = 0;

        // Левая колонка по X фиксирована
        this.leftViewportX = leftPad;
        this.leftViewportW = splitX - 2 * leftPad;
    }

    @Override
    public void render(@Nonnull GuiGraphics gfx, int mouseX, int mouseY, float partialTick) {
        this.renderBackground(gfx);

        // --- Заголовок: центр "логотип + Cartopia" с увеличенным шрифтом ------

        String title = "Cartopia";
        int titleY = 12;

        boolean logoAvailable = false;
        try {
            if (this.logoRl != null && Minecraft.getInstance().getResourceManager().getResource(this.logoRl).isPresent()) {
                logoAvailable = true;
            }
        } catch (Throwable ignore) {}

        int rawTitleW = this.font.width(title);
        int scaledTitleW = Math.round(rawTitleW * TITLE_SCALE);
        int totalW = logoAvailable ? (LOGO_W + LOGO_GAP + scaledTitleW) : scaledTitleW;
        int startX = (this.width - totalW) / 2;

        // Координаты логотипа
        int logoX = startX;
        int logoY = titleY - 6;

        // Высота строки шрифта
        int fontH = this.font.lineHeight;
        int scaledTextH = Math.round(fontH * TITLE_SCALE);

        if (logoAvailable) {
            // рисуем логотип
            RenderSystem.enableBlend();
            gfx.blit(this.logoRl, logoX, logoY, 0, 0, LOGO_W, LOGO_H, LOGO_W, LOGO_H);
            RenderSystem.disableBlend();

            // выравниваем текст по центру логотипа по вертикали
            int textX = logoX + LOGO_W + LOGO_GAP;
            int textY = logoY + LOGO_H / 2 - scaledTextH / 2;

            // масштабируем текст
            gfx.pose().pushPose();
            gfx.pose().scale(TITLE_SCALE, TITLE_SCALE, 1f);
            gfx.drawString(this.font, title, Math.round(textX / TITLE_SCALE), Math.round(textY / TITLE_SCALE), 0xFFFFFFFF, true);
            gfx.pose().popPose();

            // нижняя граница блока заголовка
            int blockBottom = Math.max(logoY + LOGO_H, textY + scaledTextH);

            // --- Динамический футер с переносами ----------------------------------
            linkRegions.clear();
            int footX = 10;
            int footW = this.width - 20;
            int footerPadTop = 8, footerPadBot = 8, lineH = 12;
            int footerLines = measureFooterLines(footW);
            int footerHeight = footerPadTop + footerLines * lineH + footerPadBot;
            int footerTopY = this.height - footerHeight;

            // Нарисуем футер (многострочно, с кликабельными ссылками)
            renderFooterWrapped(gfx, footX, footerTopY + footerPadTop, footW, lineH, mouseX, mouseY);

            // --- Левая колонка: рассчитываем границы чуть выше футера -------------
            int lineTop = blockBottom + 12; // ниже заголовка с учётом масштаба
            int bottomMarginAboveFooter = 10;
            this.leftViewportTop = lineTop;
            this.leftViewportBottom = footerTopY - bottomMarginAboveFooter;

            // Фон под левый текст + рамка
            gfx.fill(leftViewportX - 6, leftViewportTop - 6, leftViewportX + leftViewportW + 6, leftViewportBottom + 6, 0x66000000);
            gfx.fill(leftViewportX - 6, leftViewportTop - 6, leftViewportX + leftViewportW + 6, leftViewportTop - 5, 0x55FFFFFF);
            gfx.fill(leftViewportX - 6, leftViewportBottom + 5, leftViewportX + leftViewportW + 6, leftViewportBottom + 6, 0x55FFFFFF);
            gfx.fill(leftViewportX - 6, leftViewportTop - 6, leftViewportX - 5, leftViewportBottom + 6, 0x55FFFFFF);
            gfx.fill(leftViewportX + leftViewportW + 5, leftViewportTop - 6, leftViewportX + leftViewportW + 6, leftViewportBottom + 6, 0x55FFFFFF);

            // Вертикальная линия разделения (до низа левого бокса)
            gfx.fill(splitX - 1, lineTop, splitX + 1, leftViewportBottom, 0x44FFFFFF);

            // Посчитаем контентную высоту и нарисуем со скроллом
            int availW = leftViewportW;
            int contentH = measureLeftContentHeight(availW);
            this.leftContentHeight = contentH;

            int viewportH = leftViewportBottom - leftViewportTop;
            int maxScroll = Math.max(0, contentH - viewportH);
            if (leftScrollY > maxScroll) leftScrollY = maxScroll;
            if (leftScrollY < 0) leftScrollY = 0;

            gfx.enableScissor(leftViewportX, leftViewportTop, leftViewportX + leftViewportW, leftViewportBottom);

            int y = leftViewportTop - leftScrollY;

            y = drawWrapped(gfx, "Guide:", leftViewportX, y, availW, 0xFFEEDDAA, false);
            y += 4;

            y = drawList(gfx, new String[]{
                    "1. Click “Open Map”.",
                    "2. Find an area using search.",
                    "3. Click “Select Area”.",
                    "4. Adjust the dashed area (the filled square in the center marks the approximate spawn point and the time/weather anchor).",
                    "5. Click “Confirm”.",
                    "6. Wait until your browser shows a window confirming the data download.",
                    "7. Wait for generation to finish in the game, and do not leave the world until it’s done.",
                    "8. Enjoy exploring!"
            }, leftViewportX, y, availW, 0xFFFFFFFF);

            y += 6;
            y = drawWrapped(gfx,
                    "Tip: the “Real-world time/weather” toggle turns synchronization with real conditions on or off. When disabled, everything reverts to the state it had at the moment it was enabled.",
                    leftViewportX, y, availW, 0xFFBBDDEE, false);
            
            y += 6;
            y = drawWrapped(gfx,
                    "Warning: it’s recommended not to generate areas that are too large—this can take a long time, use a lot of system resources, and place heavy load on the data providers.",
                    leftViewportX, y, availW, 0xFFFF5555, false);

            y += 6;
            y = drawWrapped(gfx,
                    "Help: if you have questions or run into issues, please visit the project repository and read the README — it contains a lot of information and troubleshooting tips.",
                    leftViewportX, y, availW, 0xFFBBDDEE, false);

            gfx.disableScissor();

            // Скроллбар (справа от вьюпорта)
            if (contentH > viewportH) {
                int trackX1 = leftViewportX + leftViewportW - 4;
                int trackX2 = leftViewportX + leftViewportW;
                gfx.fill(trackX1, leftViewportTop, trackX2, leftViewportBottom, 0x33000000);

                int knobH = Math.max(20, (int) ((float) viewportH * viewportH / (float) contentH));
                int knobMaxTravel = viewportH - knobH;
                int knobY = leftViewportTop + (int) ((maxScroll == 0) ? 0 : (leftScrollY * (knobMaxTravel / (float) maxScroll)));
                gfx.fill(trackX1, knobY, trackX2, knobY + knobH, 0x66FFFFFF);
            }

            // --- Правая колонка: заголовок и динамическая позиция кнопок ----------
            gfx.drawString(this.font, "Actions", splitX + rightPad, lineTop, 0xFFFFFFFF, true);

            int rightTop = lineTop + 18;
            int rightBottom = leftViewportBottom;
            int rightH = Math.max(0, rightBottom - rightTop);

            // немного выше середины
            int anchorY = rightTop + (int) (rightH * 0.40f);

            int btnW = this.openMapBtn.getWidth(); // 180
            int rightCenterX = splitX + (this.width - splitX) / 2;
            int btnX = rightCenterX - btnW / 2;

            this.openMapBtn.setX(btnX);
            this.openMapBtn.setY(anchorY - 12);

            this.realtimeToggle.setX(btnX);
            this.realtimeToggle.setY(this.openMapBtn.getY() + 28);

        } else {
            // без логотипа — просто рисуем крупный текст по центру
            int textX = startX;
            int textY = titleY;

            gfx.pose().pushPose();
            gfx.pose().scale(TITLE_SCALE, TITLE_SCALE, 1f);
            gfx.drawString(this.font, title, Math.round(textX / TITLE_SCALE), Math.round(textY / TITLE_SCALE), 0xFFFFFFFF, true);
            gfx.pose().popPose();

            // дальше логика такая же, как выше (для краткости не дублирую).
            // Можно оставить как в предыдущей версии: lineTop = 48; и далее как было.
            // Но лучше вычислить точно, как в ветке с логотипом, если понадобится.
            // Для простоты:
            int blockBottom = textY + Math.round(fontH * TITLE_SCALE);

            // футер
            linkRegions.clear();
            int footX = 10;
            int footW = this.width - 20;
            int footerPadTop = 8, footerPadBot = 8, lineH = 12;
            int footerLines = measureFooterLines(footW);
            int footerHeight = footerPadTop + footerLines * lineH + footerPadBot;
            int footerTopY = this.height - footerHeight;
            renderFooterWrapped(gfx, footX, footerTopY + footerPadTop, footW, lineH, mouseX, mouseY);

            int lineTop = blockBottom + 12;
            int bottomMarginAboveFooter = 10;
            this.leftViewportTop = lineTop;
            this.leftViewportBottom = footerTopY - bottomMarginAboveFooter;

            // фон + рамка
            gfx.fill(leftViewportX - 6, leftViewportTop - 6, leftViewportX + leftViewportW + 6, leftViewportBottom + 6, 0x66000000);
            gfx.fill(leftViewportX - 6, leftViewportTop - 6, leftViewportX + leftViewportW + 6, leftViewportTop - 5, 0x55FFFFFF);
            gfx.fill(leftViewportX - 6, leftViewportBottom + 5, leftViewportX + leftViewportW + 6, leftViewportBottom + 6, 0x55FFFFFF);
            gfx.fill(leftViewportX - 6, leftViewportTop - 6, leftViewportX - 5, leftViewportBottom + 6, 0x55FFFFFF);
            gfx.fill(leftViewportX + leftViewportW + 5, leftViewportTop - 6, leftViewportX + leftViewportW + 6, leftViewportBottom + 6, 0x55FFFFFF);

            gfx.fill(splitX - 1, lineTop, splitX + 1, leftViewportBottom, 0x44FFFFFF);

            int availW = leftViewportW;
            int contentH = measureLeftContentHeight(availW);
            this.leftContentHeight = contentH;

            int viewportH = leftViewportBottom - leftViewportTop;
            int maxScroll = Math.max(0, contentH - viewportH);
            if (leftScrollY > maxScroll) leftScrollY = maxScroll;
            if (leftScrollY < 0) leftScrollY = 0;

            gfx.enableScissor(leftViewportX, leftViewportTop, leftViewportX + leftViewportW, leftViewportBottom);

            int y = leftViewportTop - leftScrollY;
            y = drawWrapped(gfx, "Guide:", leftViewportX, y, availW, 0xFFEEDDAA, false);
            y += 4;
            y = drawList(gfx, new String[]{
                    "1. Click “Open Map”.",
                    "2. Find an area using search.",
                    "3. Click “Select Area”.",
                    "4. Adjust the dashed area (the filled square in the center marks the approximate spawn point and the time/weather anchor).",
                    "5. Click “Confirm”.",
                    "6. Wait until your browser shows a window confirming the data download.",
                    "7. Wait for generation to finish in the game, and do not leave the world until it’s done.",
                    "8. Enjoy exploring!"
            }, leftViewportX, y, availW, 0xFFFFFFFF);
            y += 6;
            y = drawWrapped(gfx,
                    "Tip: the “Real-world time/weather” toggle turns synchronization with real conditions on or off. When disabled, everything reverts to the state it had at the moment it was enabled.",
                    leftViewportX, y, availW, 0xFFBBDDEE, false);

            y += 6;
            y = drawWrapped(gfx,
                    "Warning: it’s recommended not to generate areas that are too large—this can take a long time, use a lot of system resources, and place heavy load on the data providers.",
                    leftViewportX, y, availW, 0xFFFF5555, false);

            y += 6;
            y = drawWrapped(gfx,
                    "Help: if you have questions or run into issues, please visit the project repository and read the README — it contains a lot of information and troubleshooting tips.",
                    leftViewportX, y, availW, 0xFFBBDDEE, false);


            gfx.disableScissor();

            if (contentH > viewportH) {
                int trackX1 = leftViewportX + leftViewportW - 4;
                int trackX2 = leftViewportX + leftViewportW;
                gfx.fill(trackX1, leftViewportTop, trackX2, leftViewportBottom, 0x33000000);

                int knobH = Math.max(20, (int) ((float) viewportH * viewportH / (float) contentH));
                int knobMaxTravel = viewportH - knobH;
                int knobY = leftViewportTop + (int) ((maxScroll == 0) ? 0 : (leftScrollY * (knobMaxTravel / (float) maxScroll)));
                gfx.fill(trackX1, knobY, trackX2, knobY + knobH, 0x66FFFFFF);
            }

            gfx.drawString(this.font, "Actions", splitX + rightPad, lineTop, 0xFFFFFFFF, true);

            int rightTop = lineTop + 18;
            int rightBottom = leftViewportBottom;
            int rightH = Math.max(0, rightBottom - rightTop);

            int anchorY = rightTop + (int) (rightH * 0.40f);

            int btnW = this.openMapBtn.getWidth(); // 180
            int rightCenterX = splitX + (this.width - splitX) / 2;
            int btnX = rightCenterX - btnW / 2;

            this.openMapBtn.setX(btnX);
            this.openMapBtn.setY(anchorY - 12);
            this.realtimeToggle.setX(btnX);
            this.realtimeToggle.setY(this.openMapBtn.getY() + 28);
        }

        super.render(gfx, mouseX, mouseY, partialTick);
    }

    // --- ФУТЕР: токены, измерение и отрисовка --------------------------------

    private List<Token> footerTokens() {
        List<Token> t = new ArrayList<>();
        t.add(new Token("Author: Andrey Zakharov  •  Website: ", null));
        t.add(new Token("https://theandreyzakharov.github.io/", "https://theandreyzakharov.github.io/"));
        t.add(new Token("  •  GitHub: ", null));
        t.add(new Token("https://github.com/TheAndreyZakharov", "https://github.com/TheAndreyZakharov"));
        t.add(new Token("  •  Project: ", null));
        t.add(new Token("https://github.com/TheAndreyZakharov/Cartopia", "https://github.com/TheAndreyZakharov/Cartopia"));
        t.add(new Token("  •  Credits: OpenStreetMap, OpenLandMap, Open-Meteo, OpenTopography, GMRT", null));
        return t;
    }

    private int measureFooterLines(int footW) {
        int lines = 1;
        int x = 0;
        for (Token token : footerTokens()) {
            String rest = token.text;
            while (!rest.isEmpty()) {
                int remaining = footW - x;
                if (remaining <= 0) { lines++; x = 0; remaining = footW; }
                String slice = this.font.plainSubstrByWidth(rest, remaining);
                if (slice.isEmpty()) { lines++; x = 0; continue; }
                x += this.font.width(slice);
                rest = rest.substring(slice.length());
                if (!rest.isEmpty()) { lines++; x = 0; }
            }
        }
        return Math.max(lines, 1);
    }

    private void renderFooterWrapped(GuiGraphics gfx, int footX, int startY, int footW, int lineH, int mouseX, int mouseY) {
        int x = footX;
        int y = startY;
        for (Token token : footerTokens()) {
            String rest = token.text;
            while (!rest.isEmpty()) {
                int remaining = footW - (x - footX);
                if (remaining <= 0) { x = footX; y += lineH; remaining = footW; }
                String slice = this.font.plainSubstrByWidth(rest, remaining);
                if (slice.isEmpty()) { x = footX; y += lineH; continue; }

                if (token.isLink()) {
                    int w = this.font.width(slice);
                    boolean hover = mouseX >= x && mouseX <= x + w && mouseY >= y && mouseY <= y + 10;
                    int color = hover ? 0xFFFFFF55 : 0xFF8CC8FF;
                    gfx.drawString(this.font, slice, x, y, color, false);
                    int underlineY = y + 9;
                    gfx.fill(x, underlineY, x + w, underlineY + 1, color & 0xAAFFFFFF);
                    linkRegions.add(new LinkRegion(x, y, w, 10, token.url));
                    x += w;
                } else {
                    int color = 0xFFCCCCCC;
                    gfx.drawString(this.font, slice, x, y, color, false);
                    x += this.font.width(slice);
                }

                rest = rest.substring(slice.length());
                if (!rest.isEmpty()) { x = footX; y += lineH; }
            }
        }
    }

    // --- ВСПОМОГАТЕЛЬНОЕ РИСОВАНИЕ -------------------------------------------

    private int drawList(GuiGraphics gfx, String[] lines, int x, int y, int maxW, int color) {
        int curY = y;
        for (String s : lines) {
            curY = drawWrapped(gfx, s, x, curY, maxW, color, false);
            curY += 2;
        }
        return curY;
    }

    private int drawWrapped(GuiGraphics gfx, String text, int x, int y, int maxW, int color, boolean shadow) {
        List<net.minecraft.util.FormattedCharSequence> seq = this.font.split(Component.literal(text), maxW);
        int yy = y;
        for (var line : seq) {
            gfx.drawString(this.font, line, x, yy, color, shadow);
            yy += 10;
        }
        return yy;
    }

    // === ИЗМЕРЕНИЕ высоты левого контента ====================================

    private int measureLeftContentHeight(int maxW) {
        int h = 0;
        h += measureWrappedHeight("Guide:", maxW) + 4;

        String[] lines = new String[]{
                "1. Click “Open Map”.",
                "2. Find an area using search.",
                "3. Click “Select Area”.",
                "4. Adjust the dashed area (the filled square in the center marks the approximate spawn point and the time/weather anchor).",
                "5. Click “Confirm”.",
                "6. Wait until your browser shows a window confirming the data download.",
                "7. Wait for generation to finish in the game, and do not leave the world until it’s done.",
                "8. Enjoy exploring!"
        };
        for (String s : lines) {
            h += measureWrappedHeight(s, maxW) + 2;
        }
        h += 6;
        h += measureWrappedHeight(
                "Tip: the “Real-world time/weather” toggle turns synchronization with real conditions on or off. When disabled, everything reverts to the state it had at the moment it was enabled.",
                maxW
        );

        h += 6;
        h += measureWrappedHeight(
                "Warning: it’s recommended not to generate areas that are too large—this can take a long time, use a lot of system resources, and place heavy load on the data providers.",
                maxW
        );

        h += 6;
        h += measureWrappedHeight(
                "Help: if you have questions or run into issues, please visit the project repository and read the README — it contains a lot of information and troubleshooting tips.",
                maxW
        );

        return h;
    }

    private int measureWrappedHeight(String text, int maxW) {
        List<net.minecraft.util.FormattedCharSequence> seq = this.font.split(Component.literal(text), maxW);
        return seq.size() * 10; // высота строки = 10px, как в drawWrapped
    }

    // --- ВВОД ----------------------------------------------------------------

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        for (LinkRegion r : linkRegions) {
            if (r.hit((int) mouseX, (int) mouseY)) {
                openLink(r.url);
                return true;
            }
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double delta) {
        if (mouseX >= leftViewportX && mouseX <= leftViewportX + leftViewportW &&
            mouseY >= leftViewportTop && mouseY <= leftViewportBottom) {
            int viewportH = leftViewportBottom - leftViewportTop;
            int maxScroll = Math.max(0, leftContentHeight - viewportH);
            leftScrollY = (int) Math.max(0, Math.min(maxScroll, leftScrollY - (int)(delta * 24)));
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, delta);
    }

    // --- ССЫЛКИ --------------------------------------------------------------

    private void openLink(String url) {
        Minecraft mc = Minecraft.getInstance();
        mc.setScreen(new ConfirmLinkScreen(confirmed -> {
            if (confirmed) Util.getPlatform().openUri(url);
            mc.setScreen(this);
        }, url, true));
    }

    private void openMapWithConfirm() {
        Minecraft mc = Minecraft.getInstance();
        final String url = "http://127.0.0.1:4567"; // используем 127.0.0.1 единообразно

        mc.setScreen(new ConfirmLinkScreen(confirmed -> {
            if (confirmed) {
                try {
                    // Кроссплатформенно открыть браузер
                    Util.getPlatform().openUri(url);
                } catch (Exception ignored) {}

                // После открытия — отправим координаты игрока
                sendPlayerCoords();
            }
            mc.setScreen(this);
        }, url, true));
    }

    private void sendPlayerCoords() {
        try {
            Player player = Minecraft.getInstance().player;
            if (player == null) return;

            double px = player.getX();
            double pz = player.getZ();

            String json = String.format(Locale.ROOT, "{\"x\": %.2f, \"z\": %.2f}", px, pz);
            HttpURLConnection conn = (HttpURLConnection) new URL("http://127.0.0.1:4567/player").openConnection();
            conn.setDoOutput(true);
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "application/json");
            try (OutputStream os = conn.getOutputStream()) {
                os.write(json.getBytes(StandardCharsets.UTF_8));
            }
            conn.getResponseCode(); // просто триггерим запрос
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    // --- API -----------------------------------------------------------------

    public static void open() {
        Minecraft.getInstance().setScreen(new MapScreen());
    }
}