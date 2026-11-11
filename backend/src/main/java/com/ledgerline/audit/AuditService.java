package com.ledgerline.audit;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ledgerline.domain.AuditLog;
import com.ledgerline.domain.User;
import com.ledgerline.repository.AuditLogRepository;
import com.ledgerline.repository.UserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;

@Service
public class AuditService {

    public static final String TARGET_USER = "USER";
    public static final String TARGET_ACCOUNT = "ACCOUNT";
    public static final String TARGET_FLAG = "FLAG";

    private final AuditLogRepository auditLogRepository;
    private final UserRepository userRepository;
    private final ObjectMapper objectMapper;

    public AuditService(AuditLogRepository auditLogRepository,
                        UserRepository userRepository,
                        ObjectMapper objectMapper) {
        this.auditLogRepository = auditLogRepository;
        this.userRepository = userRepository;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public void record(Long actorId, AuditAction action, String targetType, Long targetId, Map<String, ?> details) {
        User actor = actorId == null ? null : userRepository.getReferenceById(actorId);
        auditLogRepository.save(new AuditLog(actor, action.name(), targetType, targetId, toJson(details)));
    }

    private String toJson(Map<String, ?> details) {
        if (details == null || details.isEmpty()) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(details);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Could not serialize audit details", e);
        }
    }
}
