package dev.argus.client.utils.movement.volytra;

import com.google.gson.JsonObject;

/** A destination saved on ElytraFly's Autonav trip card. */
public record AutonavWaypoint(String name, int x, int z) {
   public JsonObject toJson() {
      JsonObject json = new JsonObject();
      json.addProperty("name", this.name);
      json.addProperty("x", this.x);
      json.addProperty("z", this.z);
      return json;
   }
}
