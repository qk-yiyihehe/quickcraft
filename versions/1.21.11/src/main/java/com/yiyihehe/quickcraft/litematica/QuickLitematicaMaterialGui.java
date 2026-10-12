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
import fi.dy.masa.malilib.render.GuiContext;
import net.minecraft.client.gui.Click;
import net.minecraft.client.input.KeyInput;
import net.minecraft.client.input.MouseInput;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.item.ItemStack;
import net.minecraft.text.Text;
import java.util.List;

/** 原生 GUI 的绘制与事件边界；材料规则、布局与选择状态由轨道核心维护。 */
public final class QuickLitematicaMaterialGui {
    private QuickLitematicaMaterialGui() {}
    public static final class Context {
        final GuiContext nativeContext;
        public Context(GuiContext context) { this.nativeContext = context; }
        public void drawText(TextRenderer renderer, String text, int x, int y, int color, boolean shadow) { this.nativeContext.drawText(renderer, text, x, y, color, shadow); }
        public void drawItem(ItemStack stack, int x, int y) { this.nativeContext.drawItem(stack, x, y); }
        public void drawItemInSlot(TextRenderer renderer, ItemStack stack, int x, int y) { this.nativeContext.drawStackOverlay(renderer, stack, x, y); }
        public void drawItemTooltip(TextRenderer renderer, ItemStack stack, int x, int y) { this.nativeContext.drawItemTooltip(renderer, stack, x, y); }
        public void drawTooltip(TextRenderer renderer, List<Text> lines, int x, int y) { this.nativeContext.drawTooltip(renderer, lines, x, y); }
        public void rect(int x, int y, int width, int height, int color) { RenderUtils.drawRect(this.nativeContext, x, y, width, height, color); }
        public void outline(int x, int y, int width, int height, int color) { RenderUtils.drawOutline(this.nativeContext, x, y, width, height, color); }
        public void box(int x, int y, int width, int height, int fill, int outline) { RenderUtils.drawOutlinedBox(this.nativeContext, x, y, width, height, fill, outline); }
        public void textField(GuiTextFieldGeneric field, int x, int y, float ticks) { field.renderWrapper(this.nativeContext, x, y, ticks); }
        public void parent(Screen screen, int x, int y, float ticks) {
            screen.render(this.nativeContext, x, y, ticks);

        }
    }
    public static boolean controlDown() { return net.minecraft.client.MinecraftClient.getInstance().isCtrlPressed(); }
    abstract static class BaseScreen extends GuiBase {
        protected void drawMaterialContents(Context context, int x, int y, float ticks) {}
        protected void drawMaterialButtons(int x, int y, float ticks, Context context) { super.drawButtons(context.nativeContext, x, y, ticks); }
        protected void drawMaterialFields(int x, int y, Context context) { super.drawTextFields(context.nativeContext, x, y); }
        public boolean materialMouseClicked(int x, int y, int button) { return super.onMouseClicked(new Click(x,y,new MouseInput(button,0)), false); }
        public boolean materialMouseReleased(int x, int y, int button) { return super.onMouseReleased(new Click(x,y,new MouseInput(button,0))); }
        public boolean materialMouseScrolled(int x, int y, double horizontal, double vertical) { return super.onMouseScrolled(x, y, horizontal, vertical); }
        public boolean materialMouseDragged(double x, double y, int button, double dx, double dy) { return super.onMouseDragged(new Click(x,y,new MouseInput(button,0)),dx,dy); }
        @Override public boolean onMouseDragged(Click click, double dx, double dy) { return this.materialMouseDragged(click.x(),click.y(),click.button(),dx,dy); }
        public boolean materialKeyTyped(int key, int scan, int mods) { return super.onKeyTyped(new KeyInput(key,scan,mods)); }
        @Override protected void drawContents(GuiContext c, int x, int y, float ticks) { this.drawMaterialContents(new Context(c), x, y, ticks); }
        @Override protected void drawButtons(GuiContext c, int x, int y, float ticks) { this.drawMaterialButtons(x, y, ticks, new Context(c)); }
        @Override protected void drawTextFields(GuiContext c, int x, int y) { this.drawMaterialFields(x, y, new Context(c)); }
        @Override public boolean onMouseClicked(Click c, boolean doubleClick) { return this.materialMouseClicked((int)c.x(), (int)c.y(), c.button()); }
        @Override public boolean onMouseReleased(Click c) { return this.materialMouseReleased((int)c.x(), (int)c.y(), c.button()); }
        @Override public boolean onMouseScrolled(double x, double y, double h, double v) { return this.materialMouseScrolled((int)x, (int)y, h, v); }
        @Override public boolean onKeyTyped(KeyInput input) { return this.materialKeyTyped(input.key(), input.scancode(), input.modifiers()); }
    }
    abstract static class ListScreen<R, E extends WidgetListEntryBase<R>, W extends WidgetListBase<R,E>> extends GuiListBase<R,E,W> {
        ListScreen(int x, int y) { super(x,y); }
        public void drawMaterialContents(Context context, int x, int y, float ticks) { super.drawContents(context.nativeContext, x, y, ticks); }
        @Override public void drawContents(GuiContext c, int x, int y, float ticks) { this.drawMaterialContents(new Context(c), x, y, ticks); }
    }
    abstract static class Entry<R> extends WidgetListEntrySortable<R> {
        Entry(int x,int y,int w,int h,R row,int index) { super(x,y,w,h,row,index); }
        public void renderMaterial(int x,int y,boolean selected,Context context) { super.render(context.nativeContext,x,y,selected); }
        public void hoverMaterial(int x,int y,boolean selected,Context context) { super.postRenderHovered(context.nativeContext,x,y,selected); }
        public boolean selectMaterialAt(int x,int y,int button) { return super.canSelectAt(new Click(x,y,new MouseInput(button,0))); }
        protected boolean clickMaterial(int x,int y,int button) { return super.onMouseClickedImpl(new Click(x,y,new MouseInput(button,0)),false); }
        protected void drawMaterialString(int x,int y,int color,String text,Context context) { super.drawString(context.nativeContext,x,y,color,text); }
        protected void renderMaterialHeader(int x,int y,IGuiIcon down,IGuiIcon up,Context context) { super.renderColumnHeader(context.nativeContext,x,y,down,up); }
        @Override public void render(GuiContext c,int x,int y,boolean selected) { this.renderMaterial(x,y,selected,new Context(c)); }
        @Override public void postRenderHovered(GuiContext c,int x,int y,boolean selected) { this.hoverMaterial(x,y,selected,new Context(c)); }
        @Override public boolean canSelectAt(Click c) { return this.selectMaterialAt((int)c.x(),(int)c.y(),c.button()); }
        @Override protected boolean onMouseClickedImpl(Click c,boolean doubleClick) { return this.clickMaterial((int)c.x(),(int)c.y(),c.button()); }
    }
}
