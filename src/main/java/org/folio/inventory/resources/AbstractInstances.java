package org.folio.inventory.resources;

import static java.lang.String.format;
import static org.folio.inventory.common.FutureAssistance.allOf;

import io.vertx.core.http.HttpClient;
import io.vertx.core.json.Json;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.core.logging.Logger;
import io.vertx.core.logging.LoggerFactory;
import io.vertx.ext.web.RoutingContext;
import org.folio.inventory.common.WebContext;
import org.folio.inventory.common.domain.MultipleRecords;
import org.folio.inventory.config.InventoryConfiguration;
import org.folio.inventory.config.InventoryConfigurationImpl;
import org.folio.inventory.domain.instances.AlternativeTitle;
import org.folio.inventory.domain.instances.Classification;
import org.folio.inventory.domain.instances.Contributor;
import org.folio.inventory.domain.instances.Identifier;
import org.folio.inventory.domain.instances.Instance;
import org.folio.inventory.domain.instances.InstanceRelationship;
import org.folio.inventory.domain.instances.InstanceRelationshipToChild;
import org.folio.inventory.domain.instances.InstanceRelationshipToParent;
import org.folio.inventory.domain.instances.Note;
import org.folio.inventory.domain.instances.Publication;
import org.folio.inventory.domain.instances.titles.PrecedingSucceedingTitle;
import org.folio.inventory.domain.sharedproperties.ElectronicAccess;
import org.folio.inventory.storage.Storage;
import org.folio.inventory.storage.external.CollectionResourceClient;
import org.folio.inventory.support.JsonArrayHelper;
import org.folio.inventory.support.http.client.OkapiHttpClient;
import org.folio.inventory.support.http.client.Response;
import org.folio.inventory.support.http.server.ServerErrorResponse;

import java.lang.invoke.MethodHandles;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.stream.Collectors;

public abstract class AbstractInstances {

  protected static final String INVENTORY_PATH = "/inventory";
  protected static final String INSTANCES_PATH = INVENTORY_PATH + "/instances";
  protected static final Logger log = LoggerFactory.getLogger(MethodHandles.lookup().lookupClass());
  protected final Storage storage;
  protected final HttpClient client;
  protected final InventoryConfiguration config;


  public AbstractInstances(final Storage storage, final HttpClient client) {
    this.storage = storage;
    this.client = client;
    this.config = new InventoryConfigurationImpl();
  }

  /**
   * Fetch existing relationships for the instance from storage, compare them to the request. Delete, add, and modify relations as needed.
   *
   * @param instance       The instance request containing relationship arrays to persist.
   * @param routingContext
   * @param context
   */
  protected void updateInstanceRelationships(Instance instance,
    RoutingContext routingContext, WebContext context, Consumer respond) {

    CollectionResourceClient relatedInstancesClient = createInstanceRelationshipsClient(routingContext, context);
    List<String> instanceId = Arrays.asList(instance.getId());
    String query = createQueryForRelatedInstances(instanceId);

    if (relatedInstancesClient != null) {
      relatedInstancesClient.getMany(query, (Response result) -> {
        List<CompletableFuture<Response>> allFutures = new ArrayList<>();
        if (result.getStatusCode() == 200) {
          JsonObject json = result.getJson();
          List<JsonObject> relationsList = JsonArrayHelper.toList(json.getJsonArray("instanceRelationships"));
          Map<String, InstanceRelationship> existingRelationships = new HashMap();
          relationsList.stream().map(rel -> new InstanceRelationship(rel)).forEachOrdered(relObj ->
            existingRelationships.put(relObj.id, relObj));
          Map<String, InstanceRelationship> updatingRelationships = new HashMap();
          if (instance.getParentInstances() != null) {
            instance.getParentInstances().forEach(parent -> {
              String id = (parent.id == null ? UUID.randomUUID().toString() : parent.id);
              updatingRelationships.put(id,
                new InstanceRelationship(
                  id,
                  parent.superInstanceId,
                  instance.getId(),
                  parent.instanceRelationshipTypeId));
            });
          }
          if (instance.getChildInstances() != null) {
            instance.getChildInstances().forEach(child -> {
              String id = (child.id == null ? UUID.randomUUID().toString() : child.id);
              updatingRelationships.put(id,
                new InstanceRelationship(
                  id,
                  instance.getId(),
                  child.subInstanceId,
                  child.instanceRelationshipTypeId));
            });
          }
          update(relatedInstancesClient, allFutures, existingRelationships, updatingRelationships);

          CompletableFuture.allOf(allFutures.toArray(new CompletableFuture<?>[]{}))
            .thenAccept(respond);
        }
      });
    }
  }

  protected void updateRelatedRecords(RoutingContext routingContext, WebContext context,
    Instance instance, Consumer consumer) {
    List<CompletableFuture<Response>> allFutures = new ArrayList<>();

    CompletableFuture updateInstanceRelationshipFuture = new CompletableFuture();
    allFutures.add(updateInstanceRelationshipFuture);
    updateInstanceRelationships(instance, routingContext, context, updateInstanceRelationshipFuture::complete);

    CompletableFuture updatePrecedingSucceedingFuture = new CompletableFuture();
    allFutures.add(updatePrecedingSucceedingFuture);
    updatePrecedingSucceedingTitles(instance, routingContext, context, updatePrecedingSucceedingFuture::complete);

    allResultsOf(allFutures).thenAccept(consumer);
  }

  protected void updatePrecedingSucceedingTitles(Instance instance, RoutingContext routingContext,
    WebContext context, Consumer respond) {

    CollectionResourceClient precedingSucceedingTitlesClient = createPrecedingSucceedingTitlesClient(routingContext, context);
    List<String> instanceId = Arrays.asList(instance.getId());
    String query = createQueryForPrecedingSucceedingInstances(instanceId);

    if (precedingSucceedingTitlesClient != null) {
      precedingSucceedingTitlesClient.getMany(query, (Response result) -> {
        List<CompletableFuture<Response>> allFutures = new ArrayList<>();
        if (result.getStatusCode() == 200) {
          JsonObject json = result.getJson();
          List<JsonObject> relationsList = JsonArrayHelper.toList(json.getJsonArray("precedingSucceedingTitles"));
          Map<String, PrecedingSucceedingTitle> existingPrecedingSucceedingTitles =
            getExistedPrecedingSucceedingTitles(relationsList);
          Map<String, PrecedingSucceedingTitle> updatingPrecedingSucceedingTitles =
            getUpdatingPrecedingSucceedingTitles(instance);

          update(precedingSucceedingTitlesClient, allFutures,
            existingPrecedingSucceedingTitles, updatingPrecedingSucceedingTitles);

          allOf(allFutures).thenAccept(respond);
        }
      });
    }
  }

  private Map<String, PrecedingSucceedingTitle> getExistedPrecedingSucceedingTitles(
    List<JsonObject> relationsList) {

    Map<String, PrecedingSucceedingTitle> existingPrecedingSucceedingTitles = new HashMap();
    relationsList.stream().map(PrecedingSucceedingTitle::from).forEachOrdered(relObj ->
      existingPrecedingSucceedingTitles.put(relObj.id, relObj));

    return existingPrecedingSucceedingTitles;
  }

  private <T> void update(CollectionResourceClient resourceClient,
    List<CompletableFuture<Response>> allFutures,
    Map<String, T> existingObjects, Map<String, T> updatingObjects) {

    createOrEdit(resourceClient, allFutures, existingObjects, updatingObjects);
    delete(resourceClient, allFutures, existingObjects, updatingObjects);
  }

  private <T> void delete(CollectionResourceClient resourceClient,
   List<CompletableFuture<Response>> allFutures, Map<String, T> existingObjects,
   Map<String, T> updatingObjects) {

    existingObjects.keySet().stream()
      .filter(key -> !updatingObjects.containsKey(key)).forEach(existingKey -> {
      CompletableFuture<Response> newFuture = new CompletableFuture<>();
      allFutures.add(newFuture);
      resourceClient.delete(existingKey, newFuture::complete);
    });
  }

  private <T> void createOrEdit(CollectionResourceClient resourceClient,
    List<CompletableFuture<Response>> allFutures, Map<String, T> existingObjects,
    Map<String, T> updatingObjects) {

    updatingObjects.keySet().forEach(updatingKey -> {
      T object = updatingObjects.get(updatingKey);
      if (existingObjects.containsKey(updatingKey)) {
        if (!updatingObjects.get(updatingKey).equals(existingObjects.get(updatingKey))) {
          CompletableFuture<Response> newFuture = new CompletableFuture<>();
          allFutures.add(newFuture);
          resourceClient.put(updatingKey, object, newFuture::complete);
        }
      } else {
        CompletableFuture<Response> newFuture = new CompletableFuture<>();
        allFutures.add(newFuture);
        resourceClient.post(object, newFuture::complete);
      }
    });
  }

  private Map<String, PrecedingSucceedingTitle> getUpdatingPrecedingSucceedingTitles(Instance instance) {
    Map<String, PrecedingSucceedingTitle> updatingPrecedingSucceedingTitles = new HashMap();

    updatePrecedingTitles(instance, updatingPrecedingSucceedingTitles);
    updateSucceedingTitles(instance, updatingPrecedingSucceedingTitles);

    return updatingPrecedingSucceedingTitles;
  }

  private void updateSucceedingTitles(Instance instance,
    Map<String, PrecedingSucceedingTitle> updatingPrecedingSucceedingTitles) {

    if (instance.getSucceedingTitles() != null) {
      instance.getSucceedingTitles().forEach(child -> {
        String id = (child.id == null ? UUID.randomUUID().toString() : child.id);
        updatingPrecedingSucceedingTitles.put(id,
          new PrecedingSucceedingTitle(
            id,
            instance.getId(),
            child.succeedingInstanceId,
            child.title,
            child.hrid,
            child.identifiers));
      });
    }
  }

  private void updatePrecedingTitles(Instance instance,
    Map<String, PrecedingSucceedingTitle> updatingPrecedingSucceedingTitles) {

    if (instance.getPrecedingTitles() != null) {
      instance.getPrecedingTitles().forEach(parent -> {
        String id = (parent.id == null ? UUID.randomUUID().toString() : parent.id);
        PrecedingSucceedingTitle precedingSucceedingTitle = new PrecedingSucceedingTitle(
          id,
          parent.precedingInstanceId,
          instance.getId(),
          parent.title,
          parent.hrid,
          parent.identifiers);

        updatingPrecedingSucceedingTitles.put(id, precedingSucceedingTitle);
      });
    }
  }

  /**
   * Populates multiple Instances representation (downwards)
   *
   * @param instancesResponse Set of Instances to transform to representations
   * @param context
   * @return
   */
  protected JsonObject toRepresentation(InstancesResponse instancesResponse,
    WebContext context) {

    JsonObject representation = new JsonObject();

    JsonArray results = new JsonArray();

    MultipleRecords<Instance> wrappedInstances = instancesResponse.getSuccess().getResult();
    List<Instance> instances = wrappedInstances.records;

    instances.stream().forEach(instance -> {
      List<InstanceRelationshipToParent> parentInstances = instancesResponse.getParentInstanceMap().get(instance.getId());
      List<InstanceRelationshipToChild> childInstances = instancesResponse.getChildInstanceMap().get(instance.getId());
      List<PrecedingSucceedingTitle> precedingTitles = instancesResponse.getPrecedingTitlesMap().get(instance.getId());
      List<PrecedingSucceedingTitle> succeedingTitles = instancesResponse.getSucceedingTitlesMap().get(instance.getId());
      results.add(toRepresentation(instance, parentInstances, childInstances, precedingTitles, succeedingTitles, context));
    });

    representation
      .put("instances", results)
      .put("totalRecords", wrappedInstances.totalRecords);

    return representation;
  }

  /**
   * Populates an Instance record representation (downwards)
   *
   * @param instance        Instance result to transform to representation
   * @param parentInstances Super instances for this Instance
   * @param childInstances  Sub instances for this Instance
   * @param context
   * @return
   */
  protected JsonObject toRepresentation(Instance instance,
    List<InstanceRelationshipToParent> parentInstances, List<InstanceRelationshipToChild> childInstances,
    List<PrecedingSucceedingTitle> precedingTitles, List<PrecedingSucceedingTitle> succeedingTitles,
    WebContext context) {
    JsonObject resp = new JsonObject();

    try {
      resp.put("@context", context.absoluteUrl(
        INSTANCES_PATH + "/context").toString());
    } catch (MalformedURLException e) {
      log.warn(
        format("Failed to create context link for instance: %s", e.toString()));
    }

    resp.put("id", instance.getId());
    resp.put("hrid", instance.getHrid());
    resp.put(Instance.SOURCE_KEY, instance.getSource());
    resp.put(Instance.TITLE_KEY, instance.getTitle());
    putIfNotNull(resp, Instance.INDEX_TITLE_KEY, instance.getIndexTitle());
    putIfNotNull(resp, Instance.PARENT_INSTANCES_KEY, parentInstances);
    putIfNotNull(resp, Instance.CHILD_INSTANCES_KEY, childInstances);
    putIfNotNull(resp, Instance.PRECEDING_TITLES_KEY, precedingTitles);
    putIfNotNull(resp, Instance.SUCCEEDING_TITLES_KEY, succeedingTitles);
    putIfNotNull(resp, Instance.ALTERNATIVE_TITLES_KEY, instance.getAlternativeTitles());
    putIfNotNull(resp, Instance.EDITIONS_KEY, instance.getEditions());
    putIfNotNull(resp, Instance.SERIES_KEY, instance.getSeries());
    putIfNotNull(resp, Instance.IDENTIFIERS_KEY, instance.getIdentifiers());
    putIfNotNull(resp, Instance.CONTRIBUTORS_KEY, instance.getContributors());
    putIfNotNull(resp, Instance.SUBJECTS_KEY, instance.getSubjects());
    putIfNotNull(resp, Instance.CLASSIFICATIONS_KEY, instance.getClassifications());
    putIfNotNull(resp, Instance.PUBLICATION_KEY, instance.getPublication());
    putIfNotNull(resp, Instance.PUBLICATION_FREQUENCY_KEY, instance.getPublicationFrequency());
    putIfNotNull(resp, Instance.PUBLICATION_RANGE_KEY, instance.getPublicationRange());
    putIfNotNull(resp, Instance.ELECTRONIC_ACCESS_KEY, instance.getElectronicAccess());
    putIfNotNull(resp, Instance.INSTANCE_TYPE_ID_KEY, instance.getInstanceTypeId());
    putIfNotNull(resp, Instance.INSTANCE_FORMAT_IDS_KEY, instance.getInstanceFormatIds());
    putIfNotNull(resp, Instance.PHYSICAL_DESCRIPTIONS_KEY, instance.getPhysicalDescriptions());
    putIfNotNull(resp, Instance.LANGUAGES_KEY, instance.getLanguages());
    putIfNotNull(resp, Instance.NOTES_KEY, instance.getNotes());
    putIfNotNull(resp, Instance.MODE_OF_ISSUANCE_ID_KEY, instance.getModeOfIssuanceId());
    putIfNotNull(resp, Instance.CATALOGED_DATE_KEY, instance.getCatalogedDate());
    putIfNotNull(resp, Instance.PREVIOUSLY_HELD_KEY, instance.getPreviouslyHeld());
    putIfNotNull(resp, Instance.STAFF_SUPPRESS_KEY, instance.getStaffSuppress());
    putIfNotNull(resp, Instance.DISCOVERY_SUPPRESS_KEY, instance.getDiscoverySuppress());
    putIfNotNull(resp, Instance.STATISTICAL_CODE_IDS_KEY, instance.getStatisticalCodeIds());
    putIfNotNull(resp, Instance.SOURCE_RECORD_FORMAT_KEY, instance.getSourceRecordFormat());
    putIfNotNull(resp, Instance.STATUS_ID_KEY, instance.getStatusId());
    putIfNotNull(resp, Instance.STATUS_UPDATED_DATE_KEY, instance.getStatusUpdatedDate());
    putIfNotNull(resp, Instance.METADATA_KEY, instance.getMetadata());
    putIfNotNull(resp, Instance.TAGS_KEY, new JsonObject().put(Instance.TAG_LIST_KEY, new JsonArray(instance.getTags())));
    putIfNotNull(resp, Instance.NATURE_OF_CONTENT_TERM_IDS_KEY, instance.getNatureOfContentIds());

    try {
      URL selfUrl = context.absoluteUrl(format("%s/%s",
        INSTANCES_PATH, instance.getId()));

      resp.put("links", new JsonObject().put("self", selfUrl.toString()));
    } catch (MalformedURLException e) {
      log.warn(
        format("Failed to create self link for instance: %s", e.toString()));
    }

    return resp;
  }

  protected Instance requestToInstance(JsonObject instanceRequest) {

    List<InstanceRelationshipToParent> parentInstances = instanceRequest.containsKey(Instance.PARENT_INSTANCES_KEY)
      ? JsonArrayHelper.toList(instanceRequest.getJsonArray(Instance.PARENT_INSTANCES_KEY)).stream()
      .map(json -> new InstanceRelationshipToParent(json))
      .collect(Collectors.toList())
      : new ArrayList<>();

    List<InstanceRelationshipToChild> childInstances = instanceRequest.containsKey(Instance.CHILD_INSTANCES_KEY)
      ? JsonArrayHelper.toList(instanceRequest.getJsonArray(Instance.CHILD_INSTANCES_KEY)).stream()
      .map(json -> new InstanceRelationshipToChild(json))
      .collect(Collectors.toList())
      : new ArrayList<>();

    List<PrecedingSucceedingTitle> precedingTitles = instanceRequest.containsKey(Instance.PRECEDING_TITLES_KEY)
      ? JsonArrayHelper.toList(instanceRequest.getJsonArray(Instance.PRECEDING_TITLES_KEY)).stream()
      .map(PrecedingSucceedingTitle::from)
      .collect(Collectors.toList())
      : new ArrayList<>();

    List<PrecedingSucceedingTitle> succeedingTitles = instanceRequest.containsKey(Instance.SUCCEEDING_TITLES_KEY)
      ? JsonArrayHelper.toList(instanceRequest.getJsonArray(Instance.SUCCEEDING_TITLES_KEY)).stream()
      .map(PrecedingSucceedingTitle::from)
      .collect(Collectors.toList())
      : new ArrayList<>();

    List<Identifier> identifiers = instanceRequest.containsKey(Instance.IDENTIFIERS_KEY)
      ? JsonArrayHelper.toList(instanceRequest.getJsonArray(Instance.IDENTIFIERS_KEY)).stream()
      .map(json -> new Identifier(json))
      .collect(Collectors.toList())
      : new ArrayList<>();

    List<AlternativeTitle> alternativeTitles = instanceRequest.containsKey(Instance.ALTERNATIVE_TITLES_KEY)
      ? JsonArrayHelper.toList(instanceRequest.getJsonArray(Instance.ALTERNATIVE_TITLES_KEY)).stream()
      .map(json -> new AlternativeTitle(json))
      .collect(Collectors.toList())
      : new ArrayList<>();

    List<Contributor> contributors = instanceRequest.containsKey(Instance.CONTRIBUTORS_KEY)
      ? JsonArrayHelper.toList(instanceRequest.getJsonArray(Instance.CONTRIBUTORS_KEY)).stream()
      .map(json -> new Contributor(json))
      .collect(Collectors.toList())
      : new ArrayList<>();

    List<Classification> classifications = instanceRequest.containsKey(Instance.CLASSIFICATIONS_KEY)
      ? JsonArrayHelper.toList(instanceRequest.getJsonArray(Instance.CLASSIFICATIONS_KEY)).stream()
      .map(json -> new Classification(json))
      .collect(Collectors.toList())
      : new ArrayList<>();

    List<Publication> publications = instanceRequest.containsKey(Instance.PUBLICATION_KEY)
      ? JsonArrayHelper.toList(instanceRequest.getJsonArray(Instance.PUBLICATION_KEY)).stream()
      .map(json -> new Publication(json))
      .collect(Collectors.toList())
      : new ArrayList<>();

    List<ElectronicAccess> electronicAccess = instanceRequest.containsKey(Instance.ELECTRONIC_ACCESS_KEY)
      ? JsonArrayHelper.toList(instanceRequest.getJsonArray(Instance.ELECTRONIC_ACCESS_KEY)).stream()
      .map(json -> new ElectronicAccess(json))
      .collect(Collectors.toList())
      : new ArrayList<>();

    List<Note> notes = instanceRequest.containsKey(Instance.NOTES_KEY)
      ? JsonArrayHelper.toList(instanceRequest.getJsonArray(Instance.NOTES_KEY)).stream()
      .map(json -> new Note(json))
      .collect(Collectors.toList())
      : new ArrayList<>();

    List<String> tags = instanceRequest.containsKey(Instance.TAGS_KEY)
      ? getTags(instanceRequest) : new ArrayList<>();

    return new Instance(
      instanceRequest.getString("id"),
      instanceRequest.getString("hrid"),
      instanceRequest.getString(Instance.SOURCE_KEY),
      instanceRequest.getString(Instance.TITLE_KEY),
      instanceRequest.getString(Instance.INSTANCE_TYPE_ID_KEY))
      .setIndexTitle(instanceRequest.getString(Instance.INDEX_TITLE_KEY))
      .setParentInstances(parentInstances)
      .setChildInstances(childInstances)
      .setPrecedingTitles(precedingTitles)
      .setSucceedingTitles(succeedingTitles)
      .setAlternativeTitles(alternativeTitles)
      .setEditions(toListOfStrings(instanceRequest, Instance.EDITIONS_KEY))
      .setSeries(toListOfStrings(instanceRequest, Instance.SERIES_KEY))
      .setIdentifiers(identifiers)
      .setContributors(contributors)
      .setSubjects(toListOfStrings(instanceRequest, Instance.SUBJECTS_KEY))
      .setClassifications(classifications)
      .setPublication(publications)
      .setPublicationFrequency(toListOfStrings(instanceRequest, Instance.PUBLICATION_FREQUENCY_KEY))
      .setPublicationRange(toListOfStrings(instanceRequest, Instance.PUBLICATION_RANGE_KEY))
      .setElectronicAccess(electronicAccess)
      .setInstanceFormatIds(toListOfStrings(instanceRequest, Instance.INSTANCE_FORMAT_IDS_KEY))
      .setPhysicalDescriptions(toListOfStrings(instanceRequest, Instance.PHYSICAL_DESCRIPTIONS_KEY))
      .setLanguages(toListOfStrings(instanceRequest, Instance.LANGUAGES_KEY))
      .setNotes(notes)
      .setModeOfIssuanceId(instanceRequest.getString(Instance.MODE_OF_ISSUANCE_ID_KEY))
      .setCatalogedDate(instanceRequest.getString(Instance.CATALOGED_DATE_KEY))
      .setPreviouslyHeld(instanceRequest.getBoolean(Instance.PREVIOUSLY_HELD_KEY))
      .setStaffSuppress(instanceRequest.getBoolean(Instance.STAFF_SUPPRESS_KEY))
      .setDiscoverySuppress(instanceRequest.getBoolean(Instance.DISCOVERY_SUPPRESS_KEY))
      .setStatisticalCodeIds(toListOfStrings(instanceRequest, Instance.STATISTICAL_CODE_IDS_KEY))
      .setSourceRecordFormat(instanceRequest.getString(Instance.SOURCE_RECORD_FORMAT_KEY))
      .setStatusId(instanceRequest.getString(Instance.STATUS_ID_KEY))
      .setStatusUpdatedDate(instanceRequest.getString(Instance.STATUS_UPDATED_DATE_KEY))
      .setTags(tags)
      .setNatureOfContentIds(toListOfStrings(instanceRequest, Instance.NATURE_OF_CONTENT_TERM_IDS_KEY));

  }

  private List<String> getTags(JsonObject instanceRequest) {

    final JsonObject tags = instanceRequest.getJsonObject(Instance.TAGS_KEY);
    return tags.containsKey(Instance.TAG_LIST_KEY) ?
      JsonArrayHelper.toListOfStrings(tags.getJsonArray(Instance.TAG_LIST_KEY)) : new ArrayList<>();
  }

  // Utilities

  protected CollectionResourceClient createInstanceRelationshipsClient(RoutingContext routingContext, WebContext context) {
    CollectionResourceClient relatedInstancesClient = null;
    try {
      OkapiHttpClient okapiClient = createHttpClient(routingContext, context);
      relatedInstancesClient
        = new CollectionResourceClient(
        okapiClient,
        new URL(context.getOkapiLocation() + "/instance-storage/instance-relationships"));
    } catch (MalformedURLException mfue) {
      log.error(mfue);
    }
    return relatedInstancesClient;
  }

  protected CollectionResourceClient createPrecedingSucceedingTitlesClient(
    RoutingContext routingContext, WebContext context) {

    CollectionResourceClient relatedInstancesClient = null;
    try {
      OkapiHttpClient okapiClient = createHttpClient(routingContext, context);
      relatedInstancesClient
        = new CollectionResourceClient(
        okapiClient,
        new URL(context.getOkapiLocation() + "/preceding-succeeding-titles"));
    } catch (MalformedURLException mfue) {
      log.error(mfue);
    }
    return relatedInstancesClient;
  }

  protected String createQueryForPrecedingSucceedingInstances(List<String> instanceIds) {
    String idList = instanceIds.stream().distinct().collect(Collectors.joining(" or "));
    return format("query=succeedingInstanceId==(%s)+or+precedingInstanceId==(%s)", idList, idList);
  }

  protected <T> CompletableFuture<List<T>> allResultsOf(
    List<CompletableFuture<T>> allFutures) {

    return allOf(allFutures)
      .thenApply(v -> allFutures.stream()
        .map(CompletableFuture::join).collect(Collectors.toList()));
  }

  protected String createQueryForRelatedInstances(List<String> instanceIds) {
    String idList = instanceIds.stream().distinct().collect(Collectors.joining(" or "));
    String query = format("query=(subInstanceId==(%s)+or+superInstanceId==(%s))", idList, idList);
    return query;
  }

  private void putIfNotNull(JsonObject target, String propertyName, String value) {
    if (value != null) {
      target.put(propertyName, value);
    }
  }

  private void putIfNotNull(JsonObject target, String propertyName, List<String> value) {
    if (value != null) {
      target.put(propertyName, value);
    }
  }

  private void putIfNotNull(JsonObject target, String propertyName, Object value) {
    if (value != null) {
      if (value instanceof List) {
        target.put(propertyName, value);
      } else if (value instanceof Boolean) {
        target.put(propertyName, value);
      } else {
        target.put(propertyName, new JsonObject(Json.encode(value)));
      }
    }
  }

  private List<String> toListOfStrings(JsonObject source, String propertyName) {
    return source.containsKey(propertyName)
      ? JsonArrayHelper.toListOfStrings(source.getJsonArray(propertyName))
      : new ArrayList<>();
  }

  protected OkapiHttpClient createHttpClient(
    RoutingContext routingContext,
    WebContext context)
    throws MalformedURLException {

    return new OkapiHttpClient(client, context,
      exception -> {
        ServerErrorResponse.internalError(routingContext.response(), format("Failed to contact storage module: %s",
          exception.toString()));
      });
  }
}
