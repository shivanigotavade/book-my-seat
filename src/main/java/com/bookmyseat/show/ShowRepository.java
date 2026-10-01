package com.bookmyseat.show;

import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

public interface ShowRepository extends JpaRepository<ShowEntity, UUID> {
}
