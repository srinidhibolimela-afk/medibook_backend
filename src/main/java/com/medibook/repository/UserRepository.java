package com.medibook.repository;

import com.medibook.entity.Role;
import com.medibook.entity.UserAccount;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import java.util.Optional;

public interface UserRepository extends JpaRepository<UserAccount, Long>, JpaSpecificationExecutor<UserAccount> {
    Optional<UserAccount> findByEmailIgnoreCase(String email);
    boolean existsByEmailIgnoreCase(String email);
    long countByRole(Role role);
    boolean existsByRole(Role role);
    boolean existsByDemoTrue();
    java.util.List<UserAccount> findByRoleAndActiveTrue(Role role);
}
