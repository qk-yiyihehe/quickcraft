package com.yiyihehe.quickcraft.litematica;

import fi.dy.masa.litematica.gui.GuiMaterialList;
import fi.dy.masa.litematica.materials.MaterialListBase;
import fi.dy.masa.malilib.gui.GuiBase;
import fi.dy.masa.malilib.gui.GuiListBase;
import fi.dy.masa.malilib.gui.GuiTextFieldGeneric;
import fi.dy.masa.malilib.gui.interfaces.IGuiIcon;
import fi.dy.masa.malilib.gui.widgets.WidgetListBase;
import fi.dy.masa.malilib.gui.widgets.WidgetListEntryBase;
import fi.dy.masa.malilib.gui.widgets.WidgetListEntrySortable;
import fi.dy.masa.malilib.render.RenderUtils;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.item.ItemStack;
import net.minecraft.text.Text;
import java.util.List;

/** 原生 GUI 的绘制与事件边界；材料规则、布局与选择状态由轨道核心维护。 */
public final class QuickLitematicaMaterialGui {
    private QuickLitematicaMaterialGui() {}
    public static final class Context {
        final DrawContext nativeContext;
        public Context(DrawContext context) { this.nativeContext = context; }
        public void drawText(TextRenderer renderer, String text, int x, int y, int color, boolean shadow) { this.nativeContext.drawText(renderer, text, x, y, color, shadow); }
        public void drawItem(ItemStack stack, int x, int y) { this.nativeContext.drawItem(stack, x, y); }
        public void drawItemInSlot(TextRenderer renderer, ItemStack stack, int x, int y) { this.nativeContext.drawStackOverlay(renderer, stack, x, y); }
        public void drawItemTooltip(TextRenderer renderer, ItemStack stack, int x, int y) { this.nativeContext.drawItemTooltip(renderer, stack, x, y); }
        public void drawTooltip(TextRenderer renderer, List<Text> lines, int x, int y) { this.nativeContext.drawTooltip(renderer, lines, x, y); }
        public void rect(int x, int y, int width, int height, int color) { RenderUtils.drawRect(x, y, width, height, color); }
        public void outline(int x, int y, int width, int height, int color) { RenderUtils.drawOutline(x, y, width, height, color); }
        public void box(int x, int y, int width, int height, int fill, int outline) { RenderUtils.drawOutlinedBox(x, y, width, height, fill, outline); }
        public void textField(GuiTextFieldGeneric field, int x, int y, float ticks) { field.renderWrapper(this.nativeContext, x, y, ticks); }
        public void parent(Screen screen, int x, int y, float ticks) {
            screen.render(this.nativeContext, x, y, ticks);
            this.nativeContext.draw();
            com.mojang.blaze3d.platform.GlStateManager._clearDepth(1.0D);
            com.mojang.blaze3d.platform.GlStateManager._clear(org.lwjgl.opengl.GL11.GL_DEPTH_BUFFER_BIT);
        }
    }
    public static boolean controlDown() { return Screen.hasControlDown(); }
    abstract static class BaseScreen extends GuiBase {
        protected void drawMaterialContents(Context context, int x, int y, float ticks) {}
        protected void drawMaterialButtons(int x, int y, float ticks, Context context) { super.drawButtons(x, y, ticks, context.nativeContext); }
        protected void drawMaterialFields(int x, int y, Context context) { super.drawTextFields(x, y, context.nativeContext); }
        public boolean materialMouseClicked(int x, int y, int button) { return super.onMouseClicked(x, y, button); }
        public boolean materialMouseReleased(int x, int y, int button) { return super.onMouseReleased(x, y, button); }
        public boolean materialMouseScrolled(int x, int y, double horizontal, double vertical) { return super.onMouseScrolled(x, y, horizontal, vertical); }
        public boolean materialMouseDragged(double x, double y, int button, double dx, double dy) { return super.mouseDragged(x,y,button,dx,dy); }
        @Override public boolean mouseDragged(double x, double y, int button, double dx, double dy) { return this.materialMouseDragged(x,y,button,dx,dy); }
        public boolean materialKeyTyped(int key, int scan, int mods) { return super.onKeyTyped(key, scan, mods); }
        @Override protected void drawContents(DrawContext c, int x, int y, float ticks) { this.drawMaterialContents(new Context(c), x, y, ticks); }
        @Override protected void drawButtons(int x, int y, float ticks, DrawContext c) { this.drawMaterialButtons(x, y, ticks, new Context(c)); }
        @Override protected void drawTextFields(int x, int y, DrawContext c) { this.drawMaterialFields(x, y, new Context(c)); }
        @Override public boolean onMouseClicked(int x, int y, int button) { return this.materialMouseClicked(x, y, button); }
        @Override public boolean onMouseReleased(int x, int y, int button) { return this.materialMouseReleased(x, y, button); }
        @Override public boolean onMouseScrolled(int x, int y, double h, double v) { return this.materialMouseScrolled(x, y, h, v); }
        @Override public boolean onKeyTyped(int key, int scan, int mods) { return this.materialKeyTyped(key, scan, mods); }
    }
    abstract static class ListScreen<R, E extends WidgetListEntryBase<R>, W extends WidgetListBase<R,E>> extends GuiListBase<R,E,W> {
        ListScreen(int x, int y) { super(x,y); }
        public void drawMaterialContents(Context context, int x, int y, float ticks) { super.drawContents(context.nativeContext, x, y, ticks); }
        @Override public void drawContents(DrawContext c, int x, int y, float ticks) { this.drawMaterialContents(new Context(c), x, y, ticks); }
    }
    abstract static class Entry<R> extends WidgetListEntrySortable<R> {
        Entry(int x,int y,int w,int h,R row,int index) { super(x,y,w,h,row,index); }
        public void renderMaterial(int x,int y,boolean selected,Context context) { super.render(x,y,selected,context.nativeContext); }
        public void hoverMaterial(int x,int y,boolean selected,Context context) { super.postRenderHovered(x,y,selected,context.nativeContext); }
        public boolean selectMaterialAt(int x,int y,int button) { return super.canSelectAt(x,y,button); }
        protected boolean clickMaterial(int x,int y,int button) { return super.onMouseClickedImpl(x,y,button); }
        protected void drawMaterialString(int x,int y,int color,String text,Context context) { super.drawString(x,y,color,text,context.nativeContext); }
        protected void renderMaterialHeader(int x,int y,IGuiIcon down,IGuiIcon up,Context context) { super.renderColumnHeader(x,y,down,up,context.nativeContext); }
        @Override public void render(int x,int y,boolean selected,DrawContext c) { this.renderMaterial(x,y,selected,new Context(c)); }
        @Override public void postRenderHovered(int x,int y,boolean selected,DrawContext c) { this.hoverMaterial(x,y,selected,new Context(c)); }
        @Override public boolean canSelectAt(int x,int y,int button) { return this.selectMaterialAt(x,y,button); }
        @Override protected boolean onMouseClickedImpl(int x,int y,int button) { return this.clickMaterial(x,y,button); }
    }
}
