package com.yiyihehe.quickcraft.gui;

import fi.dy.masa.malilib.config.IConfigBase;
import fi.dy.masa.malilib.gui.GuiConfigsBase;
import fi.dy.masa.malilib.gui.widgets.WidgetConfigOption;
import fi.dy.masa.malilib.gui.widgets.WidgetListConfigOptions;

abstract class QuickCraftConfigOptionAccess extends WidgetConfigOption {
    QuickCraftConfigOptionAccess(int x, int y, int width, int height, int labelWidth, int configWidth,
            GuiConfigsBase.ConfigOptionWrapper wrapper, int index, GuiConfigsBase host, WidgetListConfigOptions parent) {
        super(x,y,width,height,labelWidth,configWidth,wrapper,index,host,parent);
    }
    protected void configureMaterialOption(int x,int y,int labelWidth,int configWidth,IConfigBase config) {
        super.addConfigOption(x,y,0,labelWidth,configWidth,config);
    }
    @Override protected void addConfigOption(int x,int y,float z,int labelWidth,int configWidth,IConfigBase config) {
        this.configureMaterialOption(x,y,labelWidth,configWidth,config);
    }
}
