package com.opspulse.alert.repository;

import com.opspulse.alert.domain.AlertChannel;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AlertChannelRepository extends JpaRepository<AlertChannel, UUID> {

    List<AlertChannel> findAllByWorkspaceIdOrderByCreatedAtDesc(UUID workspaceId);

    Optional<AlertChannel> findByIdAndWorkspaceId(UUID id, UUID workspaceId);
}
