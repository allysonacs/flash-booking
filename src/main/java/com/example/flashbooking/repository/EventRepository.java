package com.example.flashbooking.repository;

import com.example.flashbooking.entity.Event;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/**
 * Acesso a {@link Event}.
 *
 * <p>Apenas as operações de CRUD herdadas de {@link JpaRepository}: métodos de consulta são
 * adicionados quando um caso de uso real precisar deles, não por antecipação.
 */
@Repository
public interface EventRepository extends JpaRepository<Event, UUID> {
}
