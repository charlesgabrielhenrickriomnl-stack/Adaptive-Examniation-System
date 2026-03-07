package com.exam.repository;

import com.exam.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

@Repository
public interface UserRepository extends JpaRepository<User, Long> {
    Optional<User> findByEmail(String email);
    List<User> findByEmailIn(Collection<String> emails);
    boolean existsByEmail(String email);
    Optional<User> findByVerificationToken(String token);
}
