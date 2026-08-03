package com.cabin.orchestrator.family;

/**
 * Mirrors the profile shape family-hub.html already used for its
 * localStorage PROFILES_KEY entries — role-dependent fields (age/color for
 * kid, color for parent, type for pet, relation/color for guest) all live
 * on one flexible record rather than a subtype per role, same as the
 * frontend's own loose object shape. Nullable fields are simply absent
 * for roles that don't use them.
 */
public record FamilyProfile(
    String id,
    String name,
    String role,
    String birthday,
    String avatar,
    String color,
    Integer age,
    String type,
    String relation,
    int sortOrder,
    boolean active,
    long createdAt,
    long updatedAt
) {}
