package com.lunartag.app.model;

import com.google.firebase.firestore.ServerTimestamp;
import java.util.Date;
import java.util.Map;

/**
 * A simple data model class (POJO) to represent an audit log entry.
 * This object structure will be used to save data to the Firestore 'auditLogs' collection.
 */
public class AuditLog {

    private String photoId;
    private String action; // e.g., "CAPTURE", "ASSIGN", "SEND_ATTEMPT"
    private String actorId; // A unique device ID, since there is no user login
    private Map<String, Object> details; // A flexible map for extra details

    @ServerTimestamp
    private Date timestamp;

    // A no-argument constructor is required for Firestore data mapping
    public AuditLog() {}

    // --- Getters and Setters for all fields ---

    public String getPhotoId() {
        return photoId;
    }

    public void setPhotoId(String photoId) {
        this.photoId = photoId;
    }

    public String getAction() {
        return action;
    }

    public void setAction(String action) {
        this.action = action;
    }

    public String getActorId() {
        return actorId;
    }

    public void setActorId(String actorId) {
        this.actorId = actorId;
    }

    public Map<String, Object> getDetails() {
        return details;
    }

    public void setDetails(Map<String, Object> details) {
        this.details = details;
    }

    public Date getTimestamp() {
        return timestamp;
    }

    public void setTimestamp(Date timestamp) {
        this.timestamp = timestamp;
    }
}
