package com.cabin.orchestrator.security;

import java.util.List;
import java.util.Optional;

public interface ManagedUserStore {
    List<ManagedUser> loadAll();
    Optional<ManagedUser> findById(String id);
    Optional<ManagedUser> findByEmail(String email);
    void save(ManagedUser user);
}
