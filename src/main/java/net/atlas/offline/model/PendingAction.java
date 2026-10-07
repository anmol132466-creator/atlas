// src/main/java/net/atlas/offline/model/PendingAction.java
package net.atlas.offline.model;

import com.google.gson.JsonObject;

import java.util.UUID;

public final class PendingAction {

    private final String id;
    private final UUID targetUuid;
    private final String targetName;
    private final String actionType;
    private final JsonObject parameters;
    private final String issuer;
    private final long createdAt;
    private ActionStatus status;

    public PendingAction(String id, UUID targetUuid, String actionType, JsonObject parameters, String issuer, long createdAt, ActionStatus status) {
        this(id, targetUuid, null, actionType, parameters, issuer, createdAt, status);
    }

    public PendingAction(String id, UUID targetUuid, String targetName, String actionType, JsonObject parameters, String issuer, long createdAt, ActionStatus status) {
        this.id = id;
        this.targetUuid = targetUuid;
        this.targetName = targetName;
        this.actionType = actionType;
        this.parameters = parameters != null ? parameters : new JsonObject();
        this.issuer = issuer;
        this.createdAt = createdAt;
        this.status = status != null ? status : ActionStatus.PENDING;
    }

    public String getId() {
        return id;
    }

    public UUID getTargetUuid() {
        return targetUuid;
    }

    public String getTargetName() {
        return targetName;
    }

    public String getActionType() {
        return actionType;
    }

    public JsonObject getParameters() {
        return parameters;
    }

    public String getIssuer() {
        return issuer;
    }

    public long getCreatedAt() {
        return createdAt;
    }

    public ActionStatus getStatus() {
        return status;
    }

    public void setStatus(ActionStatus status) {
        this.status = status;
    }

    public JsonObject toJsonObject() {
        JsonObject obj = new JsonObject();
        obj.addProperty("id", id);
        obj.addProperty("targetUuid", targetUuid.toString());
        if (targetName != null && !targetName.isBlank()) {
            obj.addProperty("targetName", targetName);
        } else if (parameters != null && parameters.has("targetName")) {
            obj.addProperty("targetName", parameters.get("targetName").getAsString());
        }
        obj.addProperty("actionType", actionType);
        obj.add("parameters", parameters);
        obj.addProperty("issuer", issuer);
        obj.addProperty("createdAt", createdAt);
        obj.addProperty("status", status.name());
        return obj;
    }

    public static PendingAction fromJsonObject(JsonObject obj) {
        String targetName = obj.has("targetName") ? obj.get("targetName").getAsString() : null;
        return new PendingAction(
                obj.get("id").getAsString(),
                UUID.fromString(obj.get("targetUuid").getAsString()),
                targetName,
                obj.get("actionType").getAsString(),
                obj.has("parameters") ? obj.getAsJsonObject("parameters") : new JsonObject(),
                obj.get("issuer").getAsString(),
                obj.get("createdAt").getAsLong(),
                ActionStatus.valueOf(obj.get("status").getAsString())
        );
    }
}
