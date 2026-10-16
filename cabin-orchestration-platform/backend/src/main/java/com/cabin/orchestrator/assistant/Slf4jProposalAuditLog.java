package com.cabin.orchestrator.assistant;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/** One JSON line per propose call on the {@code assistant.proposal.audit} logger. Aggregate with jq over the container log. */
@Component
public class Slf4jProposalAuditLog implements ProposalAuditLog {

    private static final Logger audit = LoggerFactory.getLogger("assistant.proposal.audit");

    private final ObjectMapper json = new ObjectMapper().registerModule(new JavaTimeModule())
        .disable(com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    @Override
    public void record(Entry entry) {
        try {
            audit.info("{}", json.writeValueAsString(entry));
        } catch (JsonProcessingException e) {
            audit.warn("could not serialise audit entry {}: {}", entry.requestId(), e.getMessage());
        }
    }
}
