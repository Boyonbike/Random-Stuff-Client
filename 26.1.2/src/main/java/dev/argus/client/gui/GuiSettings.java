package dev.argus.client.gui;

import dev.argus.client.settings.*;
import java.util.List;
import java.util.Objects;

/** The editor respects the core's group expansion and single-entry collection semantics. */
final class GuiSettings {
    static List<Setting<?>> rows(List<Setting<?>> settings,String focus){
        return settings.stream().filter(setting->setting.isVisible()||Objects.equals(focus,setting.getName())).toList();
    }
    static void expandGroups(List<Setting<?>> settings){
        for(Setting<?> setting:settings)if(setting.isGroupOnly()||setting.visibilityDependency)setting.expanded=!(setting.isGroupOnly()&&setting.startsFolded);
    }
    static String editText(Setting<?> setting){return setting instanceof SetSetting<?>?"":String.valueOf(setting.getValue());}
    static void toggleCollectionEntry(SetSetting<?> setting,String text){
        if(text.isBlank())throw new IllegalArgumentException("Enter one entry");
        setting.parseValue(text.trim());
    }
}
