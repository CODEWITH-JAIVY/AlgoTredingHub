package com.algotradehub.backend.risk;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.UUID;

public interface AccountControlRepository extends JpaRepository<AccountControl, UUID> { }