package org.folio.inventory.support;

import java.util.Collection;
import java.util.Optional;

import org.folio.inventory.domain.items.Item;

import io.vertx.core.json.JsonObject;

public class HoldingsSupport {
  private HoldingsSupport() { }

  public static Optional<JsonObject> holdingForItem(
    Item item,
    Collection<JsonObject> holdings) {

    String holdingsRecordId = item.getHoldingId();

    return holdings.stream()
      .filter(holding -> holding.getString("id").equals(holdingsRecordId))
      .findFirst();
  }

  public static Optional<JsonObject> instanceForHolding(
    JsonObject holding,
    Collection<JsonObject> instances) {

    if(holding == null || !holding.containsKey("instanceId")) {
      return Optional.empty();
    }

    String instanceId = holding.getString("instanceId");

    return instances.stream()
      .filter(instance -> instance.getString("id").equals(instanceId))
      .findFirst();
  }
}
