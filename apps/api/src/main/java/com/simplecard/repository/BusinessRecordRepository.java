package com.simplecard.repository;
import com.simplecard.entity.BusinessRecord;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import java.util.UUID;
public interface BusinessRecordRepository extends JpaRepository<BusinessRecord, UUID>, JpaSpecificationExecutor<BusinessRecord> {}
