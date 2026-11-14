package dev.argus.client.settings;

import dev.argus.client.ClientContext;
import dev.argus.client.NamedFeature;
import dev.argus.client.config.EnumJsonConverter;
import java.util.Objects;
import java.util.function.Predicate;

public abstract class Setting<T> extends NamedFeature implements ValueProvider<T>, JsonConfigurable {
   private T defaultValue;
   public boolean expanded;
   public boolean visibilityDependency;
   public boolean opaqueColor;
   public boolean groupOnly;
   /** For a group: the icon on its card in the settings page (null for the default). */
   public String groupIcon;
   /** For a group: a short note shown under its card's title, or null. */
   public String groupNote;
   /** For a group: its card starts folded each time the page opens. */
   public boolean startsFolded;
   /**
    * For a group: the tab its card (and every group after it, until the next group naming a tab) sits under in the
    * settings page. A page shows tabs once two or more of its visible groups name different ones.
    */
   public String groupSection;
   private Predicate<? super T> visibilityPredicate;
   private String configName = this.getName();
   private String boundaryLabel = "";
   protected Runnable changeCallback;
   private T value;
   private T stagedValue;
   private T minimumValue;
   private T maximumValue;
   private T previousValue;
   private boolean numericBoundsEnabled;
   private boolean excludedFromConfig;
   private SettingLabelCondition boundaryLabelCondition;

   public boolean isAtMaximum() {
      Object maximum = this.getMaximumValue();
      return maximum.equals(this.getValue());
   }

   public Setting<T> markVisibilityDependency() {
      this.visibilityDependency = true;
      return this;
   }

   public String getBoundaryLabel() {
      return this.boundaryLabel;
   }

   public Setting(String name, T defaultValue, T minimumValue, T maximumValue, Predicate<? super T> visibilityPredicate) {
      super(name);
      this.defaultValue = defaultValue;
      this.minimumValue = minimumValue;
      this.maximumValue = maximumValue;
      this.visibilityPredicate = visibilityPredicate;
      this.value = defaultValue;
      this.stagedValue = defaultValue;
      this.previousValue = defaultValue;
      this.numericBoundsEnabled = true;
   }

   public Setting(String name, T defaultValue, Predicate<? super T> visibilityPredicate) {
      super(name);
      this.defaultValue = defaultValue;
      this.visibilityPredicate = visibilityPredicate;
      this.value = defaultValue;
      this.stagedValue = defaultValue;
      this.previousValue = defaultValue;
   }

   public Setting(String name, T defaultValue) {
      super(name);
      this.defaultValue = defaultValue;
      this.value = defaultValue;
      this.stagedValue = defaultValue;
      this.previousValue = defaultValue;
   }

   public Setting(String name, T defaultValue, T minimumValue, T maximumValue) {
      super(name);
      this.defaultValue = defaultValue;
      this.minimumValue = minimumValue;
      this.maximumValue = maximumValue;
      this.value = defaultValue;
      this.stagedValue = defaultValue;
      this.previousValue = defaultValue;
      this.numericBoundsEnabled = true;
   }

   public void reset() {
      this.setValue(this.defaultValue);
   }

   @Override
   public boolean equals(Object other) {
      if (this == other) {
         return true;
      }

      if (other == null || this.getClass() != other.getClass()) {
         return false;
      }

      if (!super.equals(other)) {
         return false;
      }

      Setting<?> otherSetting = (Setting<?>)other;
      return Objects.equals(this.configName, otherSetting.configName);
   }

   @Override
   public int hashCode() {
      return Objects.hash(super.hashCode(), this.configName);
   }

   @Override
   public T getValue() {
      return this.value;
   }

   public String getValueTypeName(Object candidate) {
      return candidate.getClass().getSimpleName();
   }

   public boolean hasStringValue() {
      return this.value instanceof String;
   }

   public T getPreviousValue() {
      return this.previousValue;
   }

   public T getStagedValue() {
      return this.stagedValue;
   }

   public boolean shouldDisplayBoundaryLabel() {
      return this.hasNumericValue() && !this.boundaryLabel.isEmpty() ? this.boundaryLabelCondition.matches(this) : false;
   }

   @Override
   public String getConfigName() {
      return this.configName;
   }

   public T getMaximumValue() {
      return this.maximumValue;
   }

   public boolean isAtMinimum() {
      Object minimum = this.getMinimumValue();
      return minimum.equals(this.getValue());
   }

   public T getDefaultValue() {
      return this.defaultValue;
   }

   @SafeVarargs
   public final Setting<T> dependsOn(Setting<?>... dependencies) {
      Predicate<T> dependencyVisibility = ignoredValue -> {
         for (Setting<?> dependency : dependencies) {
            if (!dependency.groupOnly && !dependency.visibilityDependency) {
               Boolean dependencyValue = (Boolean)dependency.getValue();
               if (!dependencyValue) {
                  return false;
               }
            } else {
               if (!dependency.isExpanded()) {
                  return false;
               }

               if (!dependency.isVisible()) {
                  return false;
               }
            }
         }

         return true;
      };
      this.addVisibilityPredicate(dependencyVisibility);
      return this;
   }

   public final Setting<T> setBoundaryLabel(String label, SettingLabelCondition condition) {
      this.boundaryLabel = label;
      this.boundaryLabelCondition = condition;
      return this;
   }

   public void hideWithoutBaritone() {
      Predicate<T> baritoneAvailable = ignoredValue -> ClientContext.baritoneBridge.isAvailable();
      this.addVisibilityPredicate(baritoneAvailable);
   }

   public void setExcludedFromConfig(boolean excluded) {
      this.excludedFromConfig = excluded;
   }

   public Setting<T> forceOpaqueColor() {
      this.opaqueColor = true;
      return this;
   }

   public void runChangeCallback() {
      if (this.changeCallback != null) {
         try {
            this.changeCallback.run();
         } catch (Throwable var2) {
         }
      }
   }

   public boolean isGroupOnly() {
      return this.groupOnly;
   }

   public boolean isExpanded() {
      return this.expanded && this.visibilityDependency;
   }

   public boolean isVisible() {
      if (this.visibilityPredicate == null) {
         return true;
      }

      Predicate<? super T> predicate = this.visibilityPredicate;
      return predicate.test(this.getValue());
   }

   public void setConfigName(String configName) {
      this.configName = configName;
   }

   public boolean isNumericBoundsEnabled() {
      return this.numericBoundsEnabled;
   }

   public void disableNumericBounds() {
      this.numericBoundsEnabled = false;
   }

   public boolean isExcludedFromConfig() {
      return this.excludedFromConfig;
   }

   public boolean hasNumericValue() {
      return this.value instanceof Number;
   }

   public void setChangeCallback(Runnable callback) {
      this.changeCallback = callback;
   }

   public void setStagedValue(T stagedValue) {
      this.stagedValue = stagedValue;
   }

   public String getStagedEnumDisplayName() {
      Enum stagedChoice = (Enum)this.stagedValue;
      return EnumJsonConverter.getName(stagedChoice);
   }

   public boolean hasEnumValue() {
      return this.value instanceof Enum;
   }

   public void setValue(T newValue) {
      this.setStagedValue(newValue);
      if (this.numericBoundsEnabled) {
         Number minimum = (Number)this.minimumValue;
         float minimumAsFloat = minimum.floatValue();
         Number requestedMinimum = (Number)newValue;
         if (minimumAsFloat > requestedMinimum.floatValue()) {
            T lowerBound = this.minimumValue;
            this.setStagedValue(lowerBound);
         }

         Number maximum = (Number)this.maximumValue;
         float maximumAsFloat = maximum.floatValue();
         Number requestedMaximum = (Number)newValue;
         if (maximumAsFloat < requestedMaximum.floatValue()) {
            T upperBound = this.maximumValue;
            this.setStagedValue(upperBound);
         }
      }

      this.value = this.stagedValue;
      if (this.value != this.previousValue) {
         this.runChangeCallback();
      }

      this.previousValue = this.value;
   }

   public abstract void parseValue(String var1);

   public void addVisibilityPredicate(Predicate<? super T> predicate) {
      if (this.visibilityPredicate == null) {
         this.visibilityPredicate = predicate;
      } else {
         Predicate<? super T> previous = this.visibilityPredicate;
         Objects.requireNonNull(predicate);
         this.visibilityPredicate = value -> previous.test(value) && predicate.test(value);
      }
   }

   public Setting<T> markAsGroup() {
      this.groupOnly = true;
      this.markVisibilityDependency();
      return this;
   }

   public T getMinimumValue() {
      return this.minimumValue;
   }

   public void setDefaultValue(T defaultValue) {
      this.defaultValue = defaultValue;
   }
}
