package org.folio.inventory.support.http.client;

import io.vertx.core.json.JsonObject;

import java.util.UUID;

public class IndividualResource {

  private final Response response;

  public IndividualResource(Response response) {
    this.response = response;
  }

  public UUID getId() {
    return UUID.fromString(response.getJson().getString("id"));
  }

  public JsonObject getJson() {
    return response.getJson().copy();
  }

  public JsonObject copyJson() {
    return response.getJson().copy();
  }

  public String getLocation() {
    return response.getLocation();
  }
}
