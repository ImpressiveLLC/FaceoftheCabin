package com.cabin.orchestrator.devices.model;

/**
 * Coarser than DeviceType -- what a device is FOR, not what it physically
 * is. Formalizes groupings DeviceType.java already stated as plain Java
 * comments (// Safety, // Security, etc.) into a real, queryable field
 * (DeviceType.category()) instead of text a reader has to already know to
 * look at. Added 2026-08-19 specifically to close a real gap: the frontend
 * had its own hand-maintained, client-side-only shadow of this exact
 * taxonomy (WORKFLOW_BY_TYPE, App.jsx) because DeviceCapability -- the one
 * place a category-ish concept lived server-side -- was never serialized
 * to the client at all (see DeviceStatus). See DeviceRegistry's read-path
 * enrichment for where this actually reaches the API response.
 */
public enum DeviceCategory {
    SAFETY, SECURITY, CLIMATE, UTILITIES, APPLIANCES, NETWORK, PLATFORM
}
