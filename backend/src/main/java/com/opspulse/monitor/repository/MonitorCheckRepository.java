package com.opspulse.monitor.repository;

import com.opspulse.monitor.domain.MonitorCheck;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MonitorCheckRepository extends JpaRepository<MonitorCheck, UUID> {

    List<MonitorCheck> findTop50ByMonitorIdOrderByCheckedAtDesc(UUID monitorId);
}
