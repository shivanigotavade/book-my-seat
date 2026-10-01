package com.bookmyseat.repository;

import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.bookmyseat.entity.ShowEntity;

public interface ShowRepository extends JpaRepository<ShowEntity, UUID> {
}
